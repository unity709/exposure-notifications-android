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

package com.google.android.apps.exposurenotification.edgecases;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.google.android.apps.exposurenotification.R;
import com.google.android.apps.exposurenotification.databinding.FragmentEdgeCasesAboutBinding;
import com.google.android.apps.exposurenotification.home.ExposureNotificationViewModel.ExposureNotificationState;
import com.google.android.apps.exposurenotification.proto.UiInteraction.EventType;
import dagger.hilt.android.AndroidEntryPoint;

/**
 * Fragment definition for the edge cases layout in the Settings -> About screen.
 */
@AndroidEntryPoint
public class AboutEdgeCaseFragment extends AbstractEdgeCaseFragment {

  private FragmentEdgeCasesAboutBinding binding;

  public static AboutEdgeCaseFragment newInstance(boolean handleApiErrorLiveEvents,
      boolean handleResolutions) {
    return (AboutEdgeCaseFragment) newInstance(
        new AboutEdgeCaseFragment(), handleApiErrorLiveEvents, handleResolutions);
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent,
      Bundle savedInstanceState) {
    binding = FragmentEdgeCasesAboutBinding.inflate(inflater, parent, false);
    return binding.getRoot();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }

  @Override
  protected void fillUIContent(View rootView, View containerView, ExposureNotificationState state,
      boolean isInFlight) {
    TextView title = binding.edgecaseAboutTitle;
    TextView text = binding.edgecaseAboutText;
    Button button = binding.edgecaseAboutButton;
    button.setEnabled(true);

    switch (state) {
      case ENABLED:
      case DISABLED:
        setContainerVisibility(containerView, false);
        break;
      case PAUSED_LOCATION_BLE:
        setContainerVisibility(containerView, true);
        title.setText(R.string.exposure_notifications_currently_inactive);
        text.setText(R.string.location_ble_off_warning);
        button.setText(R.string.device_settings);
        configureButtonForOpenSettings(button);
        logUiInteraction(EventType.LOCATION_PERMISSION_WARNING_SHOWN);
        logUiInteraction(EventType.BLUETOOTH_DISABLED_WARNING_SHOWN);
        break;
      case PAUSED_BLE:
        setContainerVisibility(containerView, true);
        title.setText(R.string.exposure_notifications_currently_inactive);
        text.setText(R.string.ble_off_warning);
        button.setText(R.string.device_settings);
        configureButtonForOpenSettings(button);
        logUiInteraction(EventType.BLUETOOTH_DISABLED_WARNING_SHOWN);
        break;
      case PAUSED_LOCATION:
        setContainerVisibility(containerView, true);
        title.setText(R.string.exposure_notifications_currently_inactive);
        text.setText(R.string.location_off_warning);
        button.setText(R.string.device_settings);
        configureButtonForOpenSettings(button);
        logUiInteraction(EventType.LOCATION_PERMISSION_WARNING_SHOWN);
        break;
      case STORAGE_LOW:
        setContainerVisibility(containerView, true);
        containerView.setVisibility(View.VISIBLE);
        title.setText(R.string.exposure_notifications_currently_inactive);
        text.setText(R.string.storage_low_warning);
        button.setText(R.string.manage_storage);
        configureButtonForManageStorage(button);
        logUiInteraction(EventType.LOW_STORAGE_WARNING_SHOWN);
        break;
    }
  }

}
