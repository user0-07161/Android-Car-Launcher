/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import static com.android.car.carlauncher.TaskViewManager.DBG;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A controlled {@link CarTaskView} is fully managed by the {@link TaskViewManager}.
 * The underlying task will be restarted if it is crashed.
 *
 * It should be used when:
 * <ul>
 *     <li>The underlying task is meant to be started by the host and be there forever.</li>
 * </ul>
 */
public final class ControlledCarTaskView extends CarTaskView {
    private static final String TAG = ControlledCarTaskView.class.getSimpleName();

    private final Executor mCallbackExecutor;
    private final Intent mActivityIntent;
    private final Set<String> mPackagesThatCanRestart;
    private final ControlledCarTaskViewCallbacks mCallbacks;
    private final UserManager mUserManager;

    public ControlledCarTaskView(Activity context,
            ShellTaskOrganizer organizer,
            SyncTransactionQueue syncQueue,
            Executor callbackExecutor,
            Intent activityIntent,
            Set<String> packagesThatCanRestart,
            ControlledCarTaskViewCallbacks callbacks,
            UserManager userManager) {
        super(context, organizer, syncQueue);
        mCallbackExecutor = callbackExecutor;
        mActivityIntent = activityIntent;
        mPackagesThatCanRestart = packagesThatCanRestart;
        mCallbacks = callbacks;
        mUserManager = userManager;

        mCallbackExecutor.execute(() -> mCallbacks.onTaskViewCreated(this));
    }

    @Override
    protected void notifyInitialized() {
        super.notifyInitialized();
        startActivity();
        mCallbackExecutor.execute(() -> mCallbacks.onTaskViewReady());
    }

    /**
     * Starts the underlying activity.
     */
    public void startActivity() {
        if (!mUserManager.isUserUnlocked()) {
            if (DBG) Log.d(TAG, "Can't start activity due to user is isn't unlocked");
            return;
        }

        // Don't start activity when the display is off for ActivityVisibilityTests.
        if (getDisplay().getState() != Display.STATE_ON) {
            if (DBG) Log.d(TAG, "Can't start activity due to the display is off");
            return;
        }

        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext,
                /* enterResId= */ 0, /* exitResId= */ 0);
        Rect launchBounds = new Rect();
        getBoundsOnScreen(launchBounds);
        if (DBG) {
            Log.d(TAG, "Starting (" + mActivityIntent.getComponent() + ") on " + launchBounds);
        }
        startActivity(
                PendingIntent.getActivity(mContext, /* requestCode= */ 0,
                        mActivityIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT),
                /* fillInIntent= */ null, options, launchBounds);
    }

    /**
     * @return a set of packages which should lead to restart of the current task, when changed.
     */
    Set<String> getPackagesThatCanRestart() {
        return mPackagesThatCanRestart;
    }

    /**
     * Returns the taskId of the currently running task.
     */
    public int getTaskId() {
        if (mTaskInfo == null) {
            return INVALID_TASK_ID;
        }
        return mTaskInfo.taskId;
    }
}
