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

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * CarLauncher version of {@link TaskView} which solves some CarLauncher specific issues:
 * <ul>
 * <li>b/228092608: Clears the hidden flag to make it TopFocusedRootTask.</li>
 * <li>b/225388469: Moves the embedded task to the top to make it resumed.</li>
 * </ul>
 */
public class CarTaskView extends TaskView {
    private static final String TAG = CarTaskView.class.getSimpleName();
    @Nullable
    private WindowContainerToken mTaskToken;
    private final SyncTransactionQueue mSyncQueue;
    private final SparseArray<Rect> mInsets = new SparseArray<>();

    public CarTaskView(Context context, ShellTaskOrganizer organizer,
            SyncTransactionQueue syncQueue) {
        super(context, organizer, /* taskViewTransitions= */ null, syncQueue);
        mSyncQueue = syncQueue;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        mTaskToken = taskInfo.token;
        super.onTaskAppeared(taskInfo, leash);

        applyInsets();
    }

    /**
     * Moves the embedded task over the embedding task to make it shown.
     */
    public void showEmbeddedTask() {
        if (mTaskToken == null) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        // Clears the hidden flag to make it TopFocusedRootTask: b/228092608
        wct.setHidden(mTaskToken, /* hidden= */ false);
        // Moves the embedded task to the top to make it resumed: b/225388469
        wct.reorder(mTaskToken, /* onTop= */ true);
        mSyncQueue.queue(wct);
    }

    // TODO(b/238473897): Consider taking insets one by one instead of taking all insets.
    /**
     * Set & apply the given {@code insets} on the Task.
     */
    public void setInsets(SparseArray<Rect> insets) {
        mInsets.clear();
        for (int i = insets.size() - 1; i >= 0; i--) {
            mInsets.append(insets.keyAt(i), insets.valueAt(i));
        }
        applyInsets();
    }

    private void applyInsets() {
        if (mInsets == null || mInsets.size() == 0) {
            Log.w(TAG, "Cannot apply null or empty insets");
            return;
        }
        if (mTaskToken == null) {
            Log.w(TAG, "Cannot apply insets as the task token is not present.");
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        for (int i = 0; i < mInsets.size(); i++) {
            wct.addRectInsetsProvider(mTaskToken, mInsets.valueAt(i), new int[]{mInsets.keyAt(i)});
        }
        mSyncQueue.queue(wct);
    }
}
