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

import android.app.Application;
import android.content.Intent;

import com.android.car.carlauncher.displayarea.CarDisplayAreaController;
import com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer;
import com.android.car.internal.common.UserHelperLite;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;

/**
 * Application layer for launcher.This class is responsible for registering the display areas
 * defined by {@link com.android.server.wm.CarDisplayAreaPolicyProvider}.
 */
public class CarLauncherApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        if (UserHelperLite.isHeadlessSystemUser(getUserId())) {
            return;
        }
        if (CarLauncherUtils.isCustomDisplayPolicyDefined(this)) {
            ShellExecutor executor = new HandlerExecutor(getMainThreadHandler());
            CarDisplayAreaController carDisplayAreaController =
                    CarDisplayAreaController.getInstance();
            SyncTransactionQueue tx = new SyncTransactionQueue(
                    new TransactionPool(), executor);
            Intent controlBarIntent = new Intent(this, ControlBarActivity.class);
            controlBarIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            carDisplayAreaController.init(this, tx,
                    CarDisplayAreaOrganizer.getInstance(executor, this,
                            CarLauncherUtils.getMapsIntent(this),
                            controlBarIntent, tx));
            carDisplayAreaController.register();
        }
    }
}
