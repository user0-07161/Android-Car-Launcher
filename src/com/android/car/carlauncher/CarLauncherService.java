/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * Empty Service to bind with CarService (config_earlyStartupServices) to bump up OOM adj score.
 *
 * <p>We are using a TaskOrganizer to host a mapping application and as such it becomes more
 * critical that the host application (CarLauncher) stays running even when there's memory pressure,
 * because the TaskOrganizer manages all tasks (not only the mapping application) in the system.
 * Thus we create an empty services to get a bump in the OOM adjustment score.
 * (lmkd doesn't have a config for one off usage)
 */
public final class CarLauncherService extends Service {
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
