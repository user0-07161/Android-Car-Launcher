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

package com.android.car.carlauncher.displayarea;

import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.view.SurfaceControl;

import com.android.car.carlauncher.AppGridActivity;
import com.android.wm.shell.FullscreenTaskListener;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Organizes tasks presented in display area using {@link CarDisplayAreaOrganizer}.
 */
public class CarFullscreenTaskListener extends FullscreenTaskListener {
    // TODO: update the regex to cover all such packages.
    static final String MAPS = "maps";

    private final CarDisplayAreaController mCarDisplayAreaController;
    private final Context mContext;

    public CarFullscreenTaskListener(Context context, SyncTransactionQueue syncQueue,
            CarDisplayAreaController carDisplayAreaController) {
        super(syncQueue);
        mContext = context;
        mCarDisplayAreaController = carDisplayAreaController;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        super.onTaskAppeared(taskInfo, leash);
        if (taskInfo.displayAreaFeatureId == FEATURE_DEFAULT_TASK_CONTAINER) {
            if (!mCarDisplayAreaController.isHostingDefaultApplicationDisplayAreaVisible()) {
                mCarDisplayAreaController.startAnimation(
                        AppGridActivity.CAR_LAUNCHER_STATE.DEFAULT);
            }
        }

        if (taskInfo.baseActivity != null && taskInfo.baseActivity.getPackageName().contains(
                MAPS)) {
            if (mCarDisplayAreaController.getOrganizer() != null) {
                ActivityOptions options = ActivityOptions
                        .makeCustomAnimation(mContext, /* enterResId= */ 0, /* exitResId= */ 0);
                options.setLaunchTaskDisplayArea(
                        mCarDisplayAreaController.getOrganizer().getBackgroundDisplayToken());
                mContext.startActivity(taskInfo.baseIntent, options.toBundle());
            }
        }
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        super.onTaskInfoChanged(taskInfo);
        if (taskInfo.displayAreaFeatureId == FEATURE_DEFAULT_TASK_CONTAINER) {
            if (!mCarDisplayAreaController.isHostingDefaultApplicationDisplayAreaVisible()) {
                mCarDisplayAreaController.startAnimation(
                        AppGridActivity.CAR_LAUNCHER_STATE.DEFAULT);
            }
        }
    }
}
