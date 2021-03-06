/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.google.android.apps.exposurenotification.home;

import android.app.Activity;
import android.app.PendingIntent;
import android.util.Log;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.hilt.lifecycle.ViewModelInject;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.google.android.apps.exposurenotification.common.SingleLiveEvent;
import com.google.android.apps.exposurenotification.common.time.Clock;
import com.google.android.apps.exposurenotification.logging.AnalyticsLogger;
import com.google.android.apps.exposurenotification.nearby.ExposureNotificationClientWrapper;
import com.google.android.apps.exposurenotification.nearby.PackageConfigurationHelper;
import com.google.android.apps.exposurenotification.proto.UiInteraction.EventType;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationSharedPreferences;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationSharedPreferences.NotificationInteraction;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationSharedPreferences.OnboardingStatus;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatus;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatusCodes;
import java.util.Set;

/**
 * View model for the {@link ExposureNotificationActivity} and fragments.
 */
public class ExposureNotificationViewModel extends ViewModel {

  private static final String TAG = "ExposureNotificationVM";

  private final MediatorLiveData<RefreshUiData> refreshUiLiveData = new MediatorLiveData<>();
  private final MutableLiveData<ExposureNotificationState> stateLiveData = new MutableLiveData<>();
  private final MutableLiveData<Boolean> inFlightLiveData = new MutableLiveData<>(false);
  private final MutableLiveData<Boolean> inFlightResolutionLiveData = new MutableLiveData<>(false);
  private final MutableLiveData<Boolean> enEnabledLiveData;
  private final ExposureNotificationSharedPreferences exposureNotificationSharedPreferences;

  private final SingleLiveEvent<Void> apiErrorLiveEvent = new SingleLiveEvent<>();
  private final SingleLiveEvent<ApiException> resolutionRequiredLiveEvent = new SingleLiveEvent<>();

  private final ExposureNotificationClientWrapper exposureNotificationClientWrapper;
  private final AnalyticsLogger logger;
  private final PackageConfigurationHelper packageConfigurationHelper;
  private final Clock clock;

  private final RefreshUiData refreshUiData;
  private boolean inFlightIsEnabled = false;

  /**
   * Enum to denote a status (i.e. state) of the EN service. This enum allows an easier handling of
   * the EN service status on the UI as opposed to the set of {@link ExposureNotificationStatus}
   * objects, which is returned by the EN module API. Currently, not all the statuses reported by
   * the API are handled in the UI. So, the list of EN state values below is not exhaustive.
   */
  public enum ExposureNotificationState {
    DISABLED,
    ENABLED,
    PAUSED_BLE,
    PAUSED_LOCATION,
    PAUSED_LOCATION_BLE,
    STORAGE_LOW
  }

  /**
   * A simple helper object that holds the data about both the EN state and whether there is an API
   * request in flight.
   */
  public static class RefreshUiData {

    private ExposureNotificationState state;
    private boolean isInFlight;

    RefreshUiData(ExposureNotificationState state, boolean isInFlight) {
      this.state = state;
      this.isInFlight = isInFlight;
    }

    public ExposureNotificationState getState() {
      return state;
    }

    public boolean isInFlight() {
      return isInFlight;
    }

    public void setState(ExposureNotificationState state) {
      this.state = state;
    }

    public void setInFlight(boolean inFlight) {
      this.isInFlight = inFlight;
    }
  }

  @ViewModelInject
  public ExposureNotificationViewModel(
      ExposureNotificationSharedPreferences exposureNotificationSharedPreferences,
      ExposureNotificationClientWrapper exposureNotificationClientWrapper,
      AnalyticsLogger logger,
      PackageConfigurationHelper packageConfigurationHelper,
      Clock clock) {
    this.exposureNotificationSharedPreferences = exposureNotificationSharedPreferences;
    this.exposureNotificationClientWrapper = exposureNotificationClientWrapper;
    this.logger = logger;
    this.packageConfigurationHelper = packageConfigurationHelper;
    this.clock = clock;

    boolean isEnabled = exposureNotificationSharedPreferences.getIsEnabledCache();
    enEnabledLiveData = new MutableLiveData<>(isEnabled);

    // Instantiate this object with dummy values for now. Should be immediately updated with
    // real values once refreshUiLiveData object is set properly.
    refreshUiData = new RefreshUiData(null, false);
    // Add source LiveData objects for refreshUiLiveData.
    refreshUiLiveData.addSource(getStateLiveData(), state -> {
      refreshUiData.setState(state);
      refreshUiLiveData.setValue(refreshUiData);
    });
    refreshUiLiveData.addSource(getInFlightLiveData(), isInFlight -> {
      refreshUiData.setInFlight(isInFlight);
      refreshUiLiveData.setValue(refreshUiData);
    });
  }

  /**
   * A {@link LiveData} of the {@link ExposureNotificationState} of the API.
   */
  public LiveData<ExposureNotificationState> getStateLiveData() {
    return stateLiveData;
  }

  /**
   * Returns whether there is an in flight API request. Should be used to disable buttons when
   * true.
   */
  public LiveData<Boolean> getInFlightLiveData() {
    return inFlightLiveData;
  }

  /**
   * Returns whether en_module is on/off, irrespective of its functional state. Should be used if
   * there is a EN on/off button.
   */
  public LiveData<Boolean> getEnEnabledLiveData() {
    return enEnabledLiveData;
  }

  /**
   * Returns {@link LiveData} to observe changes in the EN state or in the 'in-flight' status of the
   * API request as both these pieces of information may be needed to refresh the UI properly.
   */
  public LiveData<RefreshUiData> getRefreshUiLiveData() {
    return refreshUiLiveData;
  }

  /**
   * An event that requests a resolution with the given {@link ApiException}.
   */
  public SingleLiveEvent<ApiException> getResolutionRequiredLiveEvent() {
    return resolutionRequiredLiveEvent;
  }

  /**
   * An event that triggers when there is an error in the API.
   */
  public SingleLiveEvent<Void> getApiErrorLiveEvent() {
    return apiErrorLiveEvent;
  }

  public OnboardingStatus getOnboardedState() {
    return exposureNotificationSharedPreferences.getOnboardedState();
  }

  /**
   * Refresh isEnabled state, EN service status, and getExposureWindows from Exposure Notification
   * API.
   */
  public void refreshState() {
    maybeRefreshIsEnabled();
    maybeRefreshAnalytics();
  }

  private synchronized void maybeRefreshIsEnabled() {
    if (inFlightIsEnabled) {
      return;
    }
    inFlightIsEnabled = true;
    exposureNotificationClientWrapper.isEnabled()
        .addOnSuccessListener(
            isEnabled -> {
              maybeRefreshStatus(isEnabled);
              enEnabledLiveData.setValue(isEnabled);
              exposureNotificationSharedPreferences.setIsEnabledCache(isEnabled);
              inFlightIsEnabled = false;
            })
        .addOnCanceledListener(() -> {
          Log.i(TAG, "Call isEnabled is canceled");
          inFlightIsEnabled = false;
        })
        .addOnFailureListener(t -> {
          maybeRefreshStatus(false);
          enEnabledLiveData.setValue(false);
          exposureNotificationSharedPreferences.setIsEnabledCache(false);
          inFlightIsEnabled = false;
        });
  }

  private synchronized void maybeRefreshStatus(boolean isEnabled) {
    inFlightLiveData.setValue(true);
    exposureNotificationClientWrapper.getStatus()
        .addOnSuccessListener(status -> {
          stateLiveData.setValue(getStateForStatusAndIsEnabled(status, isEnabled));
          inFlightLiveData.setValue(false);
        })
        .addOnCanceledListener(() -> {
          Log.i(TAG, "Call getStatus is canceled");
          inFlightLiveData.setValue(false);
        })
        .addOnFailureListener(t -> {
          Log.e(TAG, "Error calling getStatus", t);
          inFlightLiveData.setValue(false);
        });
  }

  private synchronized void maybeRefreshAnalytics() {
    exposureNotificationClientWrapper.getPackageConfiguration()
        .addOnSuccessListener(
            packageConfigurationHelper::maybeUpdateAnalyticsState)
        .addOnCanceledListener(() -> Log.i(TAG, "Call getPackageConfiguration is canceled"))
        .addOnFailureListener(t -> Log.e(TAG, "Error calling getPackageConfiguration", t));
  }

  /**
   * Returns a {@link ExposureNotificationState}, which is a 'mapping' for the given set of {@link
   * ExposureNotificationStatus} objects.
   * <p>
   * We do the mapping as ExposureNotificationState object is easier to handle on the UI when
   * compared to a set of ExposureNotificationStatus objects. The given set is retrieved by calling
   * the EN module's getStatus() API. This set always contains at least one element.
   *
   * @param statusSet a set of ExposureNotificationStatus objects denoting the status of the EN API
   * @param isEnabled a boolean to check if the EN module is enabled/disabled, irrespective of its
   *                  operational state
   * @return The enum state that denotes the status of the EN API.
   */
  private ExposureNotificationState getStateForStatusAndIsEnabled(
      Set<ExposureNotificationStatus> statusSet, boolean isEnabled) {
    if (!isEnabled) {
      /*
       * The EN is disabled. However, if we also hit a Low Storage error, display it first, as EN
       * can only be (re-)enabled with enough storage space available.
       */
      if (statusSet.contains(ExposureNotificationStatus.LOW_STORAGE)) {
        return ExposureNotificationState.STORAGE_LOW;
      }
      return ExposureNotificationState.DISABLED;
    }

    if (statusSet.contains(ExposureNotificationStatus.ACTIVATED)) {
      // The EN is enabled and operational.
      return ExposureNotificationState.ENABLED;
    } else if (statusSet.contains(ExposureNotificationStatus.INACTIVATED)) {
      // The EN is enabled but non-operational.
      if (statusSet.contains(ExposureNotificationStatus.LOW_STORAGE)) {
        return ExposureNotificationState.STORAGE_LOW;
      } else if (statusSet.contains(ExposureNotificationStatus.BLUETOOTH_DISABLED)
          && statusSet.contains(ExposureNotificationStatus.LOCATION_DISABLED)) {
        return ExposureNotificationState.PAUSED_LOCATION_BLE;
      } else if (statusSet.contains(ExposureNotificationStatus.BLUETOOTH_DISABLED)) {
        return ExposureNotificationState.PAUSED_BLE;
      } else if (statusSet.contains(ExposureNotificationStatus.LOCATION_DISABLED)) {
        return ExposureNotificationState.PAUSED_LOCATION;
      }
    }
    // For all the remaining scenarios, return the DISABLED state as this is the most suitable one
    // among those currently supported in the app.
    return ExposureNotificationState.DISABLED;
  }

  /**
   * Helper methods to make it easy for activities and fragments to add resolution handling.
   * <p>
   * Instead of starting resolution handling with apiException.startResolutionForResult and
   * listening for results in an Activity with onActivityResult, these methods create an AndroidX
   * ActivityResultLauncher and attach it to a fragment/activity via registerForActivityResult.
   */
  public void registerResolutionForActivityResult(AppCompatActivity activity) {
    ActivityResultLauncher<IntentSenderRequest> resolutionActivityResultLauncher =
        activity.registerForActivityResult(
            new StartIntentSenderForResult(), this::onResolutionComplete);

    getResolutionRequiredLiveEvent()
        .observe(
            activity,
            apiException -> startResolution(apiException, resolutionActivityResultLauncher));
  }

  public void registerResolutionForActivityResult(Fragment fragment) {
    ActivityResultLauncher<IntentSenderRequest> resolutionActivityResultLauncher =
        fragment.registerForActivityResult(
            new StartIntentSenderForResult(), this::onResolutionComplete);

    getResolutionRequiredLiveEvent()
        .observe(
            fragment,
            apiException -> startResolution(apiException, resolutionActivityResultLauncher));
  }

  public void onResolutionComplete(ActivityResult activityResult) {
    Log.d(TAG, "onResolutionComplete");
    if (activityResult.getResultCode() == Activity.RESULT_OK) {
      startResolutionResultOk();
    } else {
      startResolutionResultNotOk();
    }
  }

  private void startResolution(ApiException apiException,
      ActivityResultLauncher<IntentSenderRequest> resolutionActivityResultLauncher) {
    Log.d(TAG, "startResolutionForResult");
    PendingIntent resolution = apiException.getStatus().getResolution();
    if (resolution != null) {
      IntentSenderRequest intentSenderRequest =
          new IntentSenderRequest.Builder(resolution).build();
      resolutionActivityResultLauncher.launch(intentSenderRequest);
    }
  }

  /**
   * Calls start on the Exposure Notifications API.
   */
  public void startExposureNotifications() {
    inFlightLiveData.setValue(true);
    exposureNotificationClientWrapper
        .start()
        .addOnSuccessListener(
            unused -> {
              maybeRefreshStatus(true);
              enEnabledLiveData.setValue(true);
              inFlightLiveData.setValue(false);
              refreshState();
            })
        .addOnFailureListener(
            exception -> {
              if (!(exception instanceof ApiException)) {
                inFlightLiveData.setValue(false);
                apiErrorLiveEvent.call();
                return;
              }
              ApiException apiException = (ApiException) exception;
              if (apiException.getStatusCode()
                  == ExposureNotificationStatusCodes.RESOLUTION_REQUIRED) {
                if (inFlightResolutionLiveData.getValue()) {
                  Log.e(TAG, "Error, has in flight resolution", exception);
                } else {
                  inFlightResolutionLiveData.setValue(true);
                  resolutionRequiredLiveEvent.postValue(apiException);
                }
              } else {
                Log.w(TAG, "No RESOLUTION_REQUIRED in result", apiException);
                apiErrorLiveEvent.call();
                inFlightLiveData.setValue(false);
              }
            })
        .addOnCanceledListener(() -> inFlightLiveData.setValue(false));
  }

  /**
   * Handles {@value android.app.Activity#RESULT_OK} for a resolution. User accepted opt-in.
   */
  public void startResolutionResultOk() {
    inFlightResolutionLiveData.setValue(false);
    exposureNotificationClientWrapper
        .start()
        .addOnSuccessListener(
            unused -> {
              maybeRefreshStatus(true);
              enEnabledLiveData.setValue(true);
              inFlightLiveData.setValue(false);
              refreshState();
            })
        .addOnFailureListener(
            exception -> {
              apiErrorLiveEvent.call();
              inFlightLiveData.setValue(false);
            })
        .addOnCanceledListener(() -> inFlightLiveData.setValue(false));
  }

  /**
   * Handles not {@value android.app.Activity#RESULT_OK} for a resolution. User rejected opt-in.
   */
  public void startResolutionResultNotOk() {
    inFlightResolutionLiveData.setValue(false);
    inFlightLiveData.setValue(false);
  }

  /**
   * Calls stop on the Exposure Notifications API.
   */
  public void stopExposureNotifications() {
    inFlightLiveData.setValue(true);
    exposureNotificationClientWrapper
        .stop()
        .addOnSuccessListener(
            unused -> {
              refreshState();
              inFlightLiveData.setValue(false);
            })
        .addOnFailureListener(
            exception -> inFlightLiveData.setValue(false))
        .addOnCanceledListener(() -> inFlightLiveData.setValue(false));
  }

  public void updateLastExposureNotificationLastClickedTime() {
    // We assume it clicked on the last notification received.
    int classificationIndex = exposureNotificationSharedPreferences
        .getExposureNotificationLastShownClassification();
    exposureNotificationSharedPreferences
        .setExposureNotificationLastInteraction(clock.now(), NotificationInteraction.CLICKED,
            classificationIndex);
  }

  public void logUiInteraction(EventType event) {
    logger.logUiInteraction(event);
  }
}
