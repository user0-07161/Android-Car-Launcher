/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.car.carlauncher.CarLauncher.TAG;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityTaskManager;
import android.app.Application.ActivityLifecycleCallbacks;
import android.app.TaskInfo;
import android.car.app.CarActivityManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Slog;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalMainThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm;
import com.android.wm.shell.sysui.ShellInit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TaskViewManager {
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    private final Activity mContext;
    @ShellMainThread
    private final HandlerExecutor mExecutor;
    private final SyncTransactionQueue mSyncQueue;
    private final ShellTaskOrganizer mTaskOrganizer;
    // All TaskView are bound to the Host Activity if it exists.
    @ShellMainThread
    private final List<CarTaskView> mTaskViews = new ArrayList<>();

    public TaskViewManager(Activity context, HandlerExecutor handlerExecutor,
            AtomicReference<CarActivityManager> carActivityManagerRef) {
        mContext = context;
        mExecutor = handlerExecutor;
        mTaskOrganizer = new ShellTaskOrganizer(mExecutor);
        TransactionPool transactionPool = new TransactionPool();
        mSyncQueue = new SyncTransactionQueue(transactionPool, mExecutor);
        initTaskOrganizer(carActivityManagerRef, transactionPool);

        mContext.registerActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
        if (DBG) Slog.d(TAG, "TaskViewManager.create: " + mContext);
    }

    private void initTaskOrganizer(AtomicReference<CarActivityManager> carActivityManagerRef,
            TransactionPool transactionPool) {
        FullscreenTaskListener fullscreenTaskListener = new CarFullscreenTaskMonitorListener(
                carActivityManagerRef, mSyncQueue);
        mTaskOrganizer.addListenerForType(fullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        ShellInit shellInit = new ShellInit(mExecutor);
        StartingWindowController startingController =
                new StartingWindowController(mContext, shellInit, mTaskOrganizer, mExecutor,
                        new PhoneStartingWindowTypeAlgorithm(), new IconProvider(mContext),
                        transactionPool);
        shellInit.init();
        List<TaskAppearedInfo> taskAppearedInfos = mTaskOrganizer.registerOrganizer();
        cleanUpExistingTaskViewTasks(taskAppearedInfos);
    }

    /**
     * Releases {@link TaskViewManager} and unregisters the underlying {@link ShellTaskOrganizer}.
     * It also removes all TaskViews which are created by this
     * {@link TaskViewManager}.
     */
    public void release() {
        if (DBG) Slog.d(TAG, "TaskViewManager.release");
        mExecutor.execute(() -> {
            if (DBG) Slog.d(TAG, "TaskViewManager.release");
            for (int i = mTaskViews.size() - 1; i >= 0; --i) {
                mTaskViews.get(i).release();
            }
            mTaskViews.clear();
            mContext.unregisterActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
            mTaskOrganizer.unregisterOrganizer();
        });
    }

    /**
     * Creates a {@code TaskView}.
     * @param callbackExecutor the {@link Executor} where the callback is called.
     * @param onCreate a callback to get the instance of the created TaskView.
     */
    public void createTaskView(Executor callbackExecutor, Consumer<CarTaskView> onCreate) {
        mExecutor.execute(() -> {
            CarTaskView taskView = new CarTaskView(mContext, mTaskOrganizer, mSyncQueue);
            mTaskViews.add(taskView);
            callbackExecutor.execute(() -> onCreate.accept(taskView));
        });
    }

    /**
     * Destroys the given {@code TaskView}.
     */
    public void destroyTaskView(CarTaskView taskView) {
        mExecutor.execute(() -> {
            mTaskViews.remove(taskView);
            taskView.release();
        });
    }

    /**
     * Shows the embedded Task of the given {@code TaskView}.
     */
    public void showEmbeddedTask(@NonNull CarTaskView taskView) {
        mExecutor.execute(() -> {
            taskView.showEmbeddedTask();
        });
    }

    // The callback is called only for HostActivity.
    @ExternalMainThread
    private final ActivityLifecycleCallbacks mActivityLifecycleCallbacks =
            new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity,
                        @Nullable Bundle savedInstanceState) {}

                @Override
                public void onActivityStarted(@NonNull Activity activity) {}

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                    mExecutor.execute(() -> {
                        for (int i = mTaskViews.size() - 1; i >= 0; --i) {
                            mTaskViews.get(i).showEmbeddedTask();
                        }
                    });
                }

                @Override
                public void onActivityPaused(@NonNull Activity activity) {}

                @Override
                public void onActivityStopped(@NonNull Activity activity) {}

                @Override
                public void onActivitySaveInstanceState(@NonNull Activity activity,
                        @NonNull Bundle outState) {}

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    release();
                }
            };

    /**
     * Creates a root task in the specified {code windowingMode}.
     */
    public void createRootTask(int displayId, int windowingMode,
            ShellTaskOrganizer.TaskListener listener) {
        mTaskOrganizer.createRootTask(displayId, windowingMode, listener);
    }

    /**
     * Deletes the root task corresponding to the given {@code token}.
     */
    public void deleteRootTask(WindowContainerToken token) {
        mTaskOrganizer.deleteRootTask(token);
    }

    // TODO(b/235151420): Remove this API as part of TaskViewManager API improvement
    /**
     * Runs the given {@code runnable} in the {@link SyncTransactionQueue} used by {@link TaskView}.
     */
    public void runInSync(SyncTransactionQueue.TransactionRunnable runnable) {
        mSyncQueue.runInSync(runnable);
    }

    // TODO(b/235151420): Remove this API as part of TaskViewManager API improvement
    /**
     * Applies the given {@code windowContainerTransaction} to the underlying
     * {@link ShellTaskOrganizer}.
     */
    public void enqueueTransaction(WindowContainerTransaction windowContainerTransaction) {
        mSyncQueue.queue(windowContainerTransaction);
    }

    private static void cleanUpExistingTaskViewTasks(List<TaskAppearedInfo> taskAppearedInfos) {
        ActivityTaskManager atm = ActivityTaskManager.getInstance();
        for (TaskAppearedInfo taskAppearedInfo : taskAppearedInfos) {
            TaskInfo taskInfo = taskAppearedInfo.getTaskInfo();
            // Only TaskView tasks have WINDOWING_MODE_MULTI_WINDOW.
            if (taskInfo.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW) {
                if (DBG) Slog.d(TAG, "Found the dangling task, removing: " + taskInfo.taskId);
                atm.removeTask(taskInfo.taskId);
            }
        }
    }
}
