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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED;

import static com.android.car.carlauncher.CarLauncher.TAG;
import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_FULLSCREEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Application.ActivityLifecycleCallbacks;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.car.user.CarUserManager;
import android.car.user.UserLifecycleEventFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.TaskAppearedInfo;

import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.internal.annotations.VisibleForTesting;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.startingsurface.StartingWindowController;
import com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm;
import com.android.wm.shell.sysui.ShellInit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;


/**
 * A manager for creating {@link ControlledCarTaskView}, {@link LaunchRootCarTaskView} &
 * {@link SemiControlledCarTaskView}.
 */
public final class TaskViewManager {
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String SCHEME_PACKAGE = "package";

    private final AtomicReference<CarActivityManager> mCarActivityManagerRef =
            new AtomicReference<>();
    @ShellMainThread
    private final HandlerExecutor mShellExecutor;
    private final SyncTransactionQueue mSyncQueue;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final int mHostTaskId;

    // All TaskView are bound to the Host Activity if it exists.
    @ShellMainThread
    private final List<ControlledCarTaskView> mControlledTaskViews = new ArrayList<>();
    @ShellMainThread
    private final List<SemiControlledCarTaskView> mSemiControlledTaskViews = new ArrayList<>();
    @ShellMainThread
    private LaunchRootCarTaskView mLaunchRootCarTaskView = null;

    private CarUserManager mCarUserManager;
    private Activity mContext;

    private final ShellTaskOrganizer.TaskListener mRootTaskListener =
            new ShellTaskOrganizer.TaskListener() {
                @Override
                public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo,
                        SurfaceControl leash) {
                    // Called for a task appearing the launch root. Route it to the appropriate
                    // semi-controlled taskview;
                    for (SemiControlledCarTaskView taskView : mSemiControlledTaskViews) {
                        if (taskView.getCallbacks().shouldStartInTaskView(taskInfo)) {
                            if (taskView.isInitialized()) {
                                taskView.onTaskAppeared(taskInfo, leash);
                            }
                            return;
                        }
                    }

                    // TODO(b/228077499): Fix for the case when a task is started in the
                    // launch-root-task right after the initialization of launch-root-task, it
                    // remains blank.
                    mSyncQueue.runInSync(t -> t.show(leash));

                    CarActivityManager carAm = mCarActivityManagerRef.get();
                    if (carAm != null) {
                        carAm.onTaskAppeared(taskInfo);
                    } else {
                        Log.w(TAG, "CarActivityManager is null, skip onTaskAppeared: TaskInfo"
                                + " = " + taskInfo);
                    }
                }

                @Override
                public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
                    for (SemiControlledCarTaskView taskView : mSemiControlledTaskViews) {
                        if (taskView.getCallbacks().shouldStartInTaskView(taskInfo)) {
                            if (taskView.isInitialized()) {
                                // onLocationChanged() is crucial. If this is not called, the
                                // further activities opened by the current activity do not open in
                                // the correct size.
                                // TODO(b/234879199): Explore more for a better solution.
                                taskView.onLocationChanged();
                                taskView.onTaskInfoChanged(taskInfo);
                            }
                            // Semi-controlled apps are assumed to be Distraction optimised and
                            // hence not reported to CarActivityManager.
                            return;
                        }
                    }

                    // Uncontrolled apps by default launch in the launch root so nothing needs to
                    // be done here for them.
                    CarActivityManager carAm = mCarActivityManagerRef.get();
                    if (carAm != null) {
                        carAm.onTaskInfoChanged(taskInfo);
                    } else {
                        Log.w(TAG, "CarActivityManager is null, skip onTaskAppeared: TaskInfo"
                                + " = " + taskInfo);
                    }
                }

                @Override
                public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
                    for (SemiControlledCarTaskView taskView : mSemiControlledTaskViews) {
                        if (taskView.getCallbacks().shouldStartInTaskView(taskInfo)) {
                            if (taskView.isInitialized()) {
                                taskView.onTaskVanished(taskInfo);
                            }
                            return;
                        }
                    }

                    CarActivityManager carAm = mCarActivityManagerRef.get();
                    if (carAm != null) {
                        carAm.onTaskVanished(taskInfo);
                    } else {
                        Log.w(TAG, "CarActivityManager is null, skip onTaskAppeared: TaskInfo"
                                + " = " + taskInfo);
                    }
                }
            };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            boolean hostFocused = taskId == mHostTaskId && focused;
            if (DBG) {
                Log.d(TAG, "onTaskFocusChanged: taskId=" + taskId
                        + ", hostFocused=" + hostFocused);
            }
            if (!hostFocused) {
                return;
            }

            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                ControlledCarTaskView taskView = mControlledTaskViews.get(i);
                if (taskView.getTaskId() == INVALID_TASK_ID) {
                    // If the task in TaskView is crashed when host is in background,
                    // We'd like to restart it when host becomes foreground and focused.
                    taskView.startActivity();
                }
            }
        }

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (DBG) {
                Log.d(TAG, "onActivityRestartAttempt: taskId=" + task.taskId
                        + ", homeTaskVisible=" + homeTaskVisible + ", wasVisible=" + wasVisible);
            }
            if (homeTaskVisible && mHostTaskId == task.taskId) {
                for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                    ControlledCarTaskView taskView = mControlledTaskViews.get(i);
                    // In the case of CarLauncher, this code handles the case where Home Intent is
                    // sent when CarLauncher is foreground and the task in a ControlledTaskView is
                    // crashed.
                    if (taskView.getTaskId() == INVALID_TASK_ID) {
                        taskView.startActivity();
                    }
                }
            }
        }
    };

    private final CarUserManager.UserLifecycleListener mUserLifecycleListener = event -> {
        if (DBG) {
            Log.d(TAG, "UserLifecycleListener.onEvent: For User "
                    + mContext.getUserId()
                    + ", received an event " + event);
        }

        // When user-unlocked, if task isn't launched yet, then try to start it.
        if (event.getEventType() == USER_LIFECYCLE_EVENT_TYPE_UNLOCKED
                && mContext.getUserId() == event.getUserId()) {
            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                ControlledCarTaskView taskView = mControlledTaskViews.get(i);
                if (taskView.getTaskId() == INVALID_TASK_ID) {
                    taskView.startActivity();
                }
            }
        }

        // When user-switching, onDestroy in the previous user's Host app isn't called.
        // So try to release the resource explicitly.
        if (event.getEventType() == USER_LIFECYCLE_EVENT_TYPE_SWITCHING
                && mContext.getUserId() == event.getPreviousUserId()) {
            release();
        }
    };

    private final BroadcastReceiver mPackageBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Log.d(TAG, "onReceive: intent=" + intent);

            if (isActivityStopped(mContext)) {
                return;
            }

            String packageName = intent.getData().getSchemeSpecificPart();
            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                ControlledCarTaskView taskView = mControlledTaskViews.get(i);
                if (taskView.getTaskId() == INVALID_TASK_ID
                        && taskView.getDependingPackageNames().contains(packageName)) {
                    taskView.startActivity();
                }
            }
        }
    };

    public TaskViewManager(Activity context, HandlerExecutor handlerExecutor) {
        this(context, handlerExecutor, new ShellTaskOrganizer(handlerExecutor),
                new SyncTransactionQueue(new TransactionPool(), handlerExecutor));
    }

    @VisibleForTesting
    TaskViewManager(Activity context, HandlerExecutor handlerExecutor,
            ShellTaskOrganizer shellTaskOrganizer, SyncTransactionQueue syncQueue) {
        if (DBG) Slog.d(TAG, "TaskViewManager(): " + context);
        mContext = context;
        mShellExecutor = handlerExecutor;
        mTaskOrganizer = shellTaskOrganizer;
        mHostTaskId = mContext.getTaskId();
        mSyncQueue = syncQueue;

        initCar();
        initTaskOrganizer(mCarActivityManagerRef);
        mContext.registerActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
    }

    private void initCar() {
        Car.createCar(/* context= */ mContext, /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) {
                        Log.w(TAG, "CarService looks crashed");
                        mCarActivityManagerRef.set(null);
                        return;
                    }
                    setCarUserManager((CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE));
                    UserLifecycleEventFilter filter = new UserLifecycleEventFilter.Builder()
                            .addEventType(USER_LIFECYCLE_EVENT_TYPE_UNLOCKED)
                            .addEventType(USER_LIFECYCLE_EVENT_TYPE_SWITCHING).build();
                    mCarUserManager.addListener(mContext.getMainExecutor(), filter,
                            mUserLifecycleListener);
                    CarActivityManager carAM = (CarActivityManager) car.getCarManager(
                            Car.CAR_ACTIVITY_SERVICE);
                    mCarActivityManagerRef.set(carAM);

                    carAM.registerTaskMonitor();
                });

        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);

        IntentFilter packageIntentFilter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        packageIntentFilter.addDataScheme(SCHEME_PACKAGE);
        mContext.registerReceiver(mPackageBroadcastReceiver, packageIntentFilter);
    }

    // TODO(b/239958124A): Remove this method when unit tests for TaskViewManager have been added.
    /**
     * This method only exists for the container activity to set mock car user manager in tests.
     */
    void setCarUserManager(CarUserManager carUserManager) {
        mCarUserManager = carUserManager;
    }

    private void initTaskOrganizer(AtomicReference<CarActivityManager> carActivityManagerRef) {
        FullscreenTaskListener fullscreenTaskListener = new CarFullscreenTaskMonitorListener(
                carActivityManagerRef, mSyncQueue);
        mTaskOrganizer.addListenerForType(fullscreenTaskListener, TASK_LISTENER_TYPE_FULLSCREEN);
        ShellInit shellInit = new ShellInit(mShellExecutor);
        // StartingWindowController needs to be initialized so that splash screen is displayed.
        new StartingWindowController(mContext, shellInit, mTaskOrganizer, mShellExecutor,
                new PhoneStartingWindowTypeAlgorithm(), new IconProvider(mContext),
                new TransactionPool());
        shellInit.init();
        List<TaskAppearedInfo> taskAppearedInfos = mTaskOrganizer.registerOrganizer();
        cleanUpExistingTaskViewTasks(taskAppearedInfos);
    }

    /**
     * Creates a {@link ControlledCarTaskView}.
     *
     * @param callbackExecutor the executor which the {@link ControlledCarTaskViewCallbacks} will
     *                         be executed on.
     * @param activityIntent the intent of the activity that is meant to be started in this
     *                       {@link ControlledCarTaskView}.
     * @param taskViewCallbacks the callbacks for the underlying TaskView.
     */
    public void createControlledCarTaskView(
            Executor callbackExecutor,
            Intent activityIntent,
            boolean autoRestartOnCrash,
            ControlledCarTaskViewCallbacks taskViewCallbacks) {
        mShellExecutor.execute(() -> {
            ControlledCarTaskView taskView = new ControlledCarTaskView(mContext, mTaskOrganizer,
                    mSyncQueue, callbackExecutor, activityIntent, autoRestartOnCrash,
                    taskViewCallbacks, mContext.getSystemService(UserManager.class));
            mControlledTaskViews.add(taskView);
        });
    }

    /**
     * Creates a {@link LaunchRootCarTaskView}.
     *
     * @param callbackExecutor the executor which the {@link LaunchRootCarTaskViewCallbacks} will be
     *                         executed on.
     * @param taskViewCallbacks the callbacks for the underlying TaskView.
     */
    public void createLaunchRootTaskView(Executor callbackExecutor,
            LaunchRootCarTaskViewCallbacks taskViewCallbacks) {
        mShellExecutor.execute(() -> {
            if (mLaunchRootCarTaskView != null) {
                throw new IllegalStateException("Cannot create more than one launch root task");
            }
            mLaunchRootCarTaskView = new LaunchRootCarTaskView(mContext, mTaskOrganizer,
                    mSyncQueue, callbackExecutor, taskViewCallbacks, mRootTaskListener);
        });
    }

    /**
     * Creates a {@link SemiControlledCarTaskView}.
     *
     * @param callbackExecutor the executor which the {@link SemiControlledCarTaskViewCallbacks}
     *                         will be executed on.
     * @param taskViewCallbacks the callbacks for the underlying TaskView.
     */
    public void createSemiControlledTaskView(Executor callbackExecutor,
            SemiControlledCarTaskViewCallbacks taskViewCallbacks) {
        mShellExecutor.execute(() -> {
            if (mLaunchRootCarTaskView == null) {
                throw new IllegalStateException("Cannot create a semi controlled taskview without a"
                        + " launch root taskview");
            }
            SemiControlledCarTaskView taskView = new SemiControlledCarTaskView(mContext,
                    mTaskOrganizer, mSyncQueue, callbackExecutor, taskViewCallbacks);
            mSemiControlledTaskViews.add(taskView);
        });
    }

    /**
     * Releases {@link TaskViewManager} and unregisters the underlying {@link ShellTaskOrganizer}.
     * It also removes all TaskViews which are created by this {@link TaskViewManager}.
     */
    private void release() {
        mShellExecutor.execute(() -> {
            if (DBG) Slog.d(TAG, "TaskViewManager.release");

            if (mCarUserManager != null) {
                mCarUserManager.removeListener(mUserLifecycleListener);
            }
            TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
            mContext.unregisterReceiver(mPackageBroadcastReceiver);

            CarActivityManager carAM = mCarActivityManagerRef.get();
            if (carAM != null) {
                carAM.unregisterTaskMonitor();
                mCarActivityManagerRef.set(null);
            }

            for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                mControlledTaskViews.get(i).release();
            }
            mControlledTaskViews.clear();

            for (int i = mSemiControlledTaskViews.size() - 1; i >= 0; --i) {
                mSemiControlledTaskViews.get(i).release();
            }
            mSemiControlledTaskViews.clear();

            if (mLaunchRootCarTaskView != null) {
                mLaunchRootCarTaskView.release();
                mLaunchRootCarTaskView = null;
            }

            mContext.unregisterActivityLifecycleCallbacks(mActivityLifecycleCallbacks);
            mTaskOrganizer.unregisterOrganizer();
        });
    }

    private static boolean isActivityStopped(Activity activity) {
        // This code relies on Activity#isVisibleForAutofill() instead of maintaining a custom
        // activity state.
        return !activity.isVisibleForAutofill();
    }

    private final ActivityLifecycleCallbacks mActivityLifecycleCallbacks =
            new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity,
                        @Nullable Bundle savedInstanceState) {
                    if (DBG) {
                        Log.d(TAG, "Host activity created");
                    }
                }

                @Override
                public void onActivityStarted(@NonNull Activity activity) {
                    if (DBG) {
                        Log.d(TAG, "Host activity started");
                    }
                }

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                    Log.d(TAG, "Host activity resumed");
                    if (activity != mContext) {
                        return;
                    }
                    mShellExecutor.execute(() -> {
                        for (int i = mControlledTaskViews.size() - 1; i >= 0; --i) {
                            mControlledTaskViews.get(i).showEmbeddedTask();
                        }
                        if (mLaunchRootCarTaskView != null) {
                            mLaunchRootCarTaskView.showEmbeddedTask();
                        }
                        for (int i = mSemiControlledTaskViews.size() - 1; i >= 0; --i) {
                            mSemiControlledTaskViews.get(i).showEmbeddedTask();
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

    @VisibleForTesting
    List<ControlledCarTaskView> getControlledTaskViews() {
        return mControlledTaskViews;
    }

    @VisibleForTesting
    LaunchRootCarTaskView getLaunchRootCarTaskView() {
        return mLaunchRootCarTaskView;
    }

    @VisibleForTesting
    List<SemiControlledCarTaskView> getSemiControlledTaskViews() {
        return mSemiControlledTaskViews;
    }

    @VisibleForTesting
    BroadcastReceiver getPackageBroadcastReceiver() {
        return mPackageBroadcastReceiver;
    }
}
