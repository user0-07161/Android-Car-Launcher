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

import android.app.Application;
import android.os.UserManager;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * Default implementation {@link ViewModel} for {@link ContextualFragment} which only returns
 * placeholder data.
 */
public class ContextualViewModel extends AndroidViewModel {

    private final LiveData<String> mUserName = new UserNameLiveData();
    private final LiveData<Integer> mWeatherCondition = new WeatherImageResourceLiveData();
    private final LiveData<Integer> mWeatherTemperature = new WeatherTemperatureLiveData();

    public ContextualViewModel(Application application) {
        super(application);
    }

    /**
     * Returns a {@link LiveData} containing the name of the current system user.
     */
    public LiveData<String> getUserName() {
        return mUserName;
    }

    /**
     * Returns a {@link LiveData} containing the drawable resource ID of the current weather
     * condition.
     */
    public LiveData<Integer> getWeatherImageResource() {
        return mWeatherCondition;
    }

    /**
     * Returns a {@link LiveData} containing the current outdoor temperature.
     */
    public LiveData<Integer> getWeatherTemperature() {
        return mWeatherTemperature;
    }

    private class UserNameLiveData extends MutableLiveData<String> {

        @Override
        protected void onActive() {
            UserManager userManager = UserManager.get(getApplication());
            String userName = userManager.getUserName();
            setValue(userName);
        }
    }

    private class WeatherImageResourceLiveData extends MutableLiveData<Integer> {

        WeatherImageResourceLiveData() {
            setValue(R.drawable.ic_partly_cloudy);
        }
    }

    private class WeatherTemperatureLiveData extends MutableLiveData<Integer> {

        WeatherTemperatureLiveData() {
            setValue(82);
        }
    }
}
