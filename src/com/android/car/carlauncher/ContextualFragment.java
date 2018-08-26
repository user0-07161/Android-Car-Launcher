/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.carlauncher;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;

/** {@link Fragment} which displays relevant information that changes over time. */
public class ContextualFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.contextual_fragment, container, false);
        TextView greetingText = requireNonNull(rootView.findViewById(R.id.greeting));
        ImageView weatherImage = requireNonNull(rootView.findViewById(R.id.weather_image));
        TextView temperatureText = requireNonNull(rootView.findViewById(R.id.temperature));

        ContextualViewModel viewModel = ViewModelProviders.of(this).get(ContextualViewModel.class);

        viewModel.getUserName().observe(this, userName -> {
            if (userName != null) {
                greetingText.setText(getResources().getString(R.string.greeting, userName));
            } else {
                greetingText.setText("");
            }
        });

        viewModel.getWeatherImageResource().observe(this, resourceId -> {
            if (resourceId != null) {
                weatherImage.setImageResource(resourceId);
            }
        });

        viewModel.getWeatherTemperature().observe(this, temperature -> {
            String tempString = temperature != null
                    ? getString(R.string.temperature, temperature)
                    : getString(R.string.temperature_empty);
            temperatureText.setText(tempString);
        });

        return rootView;
    }
}
