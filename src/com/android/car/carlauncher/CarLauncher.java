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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityView;
import android.app.ActivityTaskManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;

import com.android.car.media.common.PlaybackFragment;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Basic Launcher for Android Automotive which demonstrates the use of {@link ActivityView} to host
 * maps content.
 *
 * <p>Note: On some devices, the ActivityView may render with a width, height, and/or aspect
 * ratio that does not meet Android compatibility definitions. Developers should work with content
 * owners to ensure content renders correctly when extending or emulating this class.
 *
 * <p>Note: Since the hosted maps Activity in ActivityView is currently in a virtual display, the
 * system considers the Activity to always be in front. Launching the maps Activity with a direct
 * Intent will not work. To start the maps Activity on the real display, send the Intent to the
 * Launcher with the {@link Intent#CATEGORY_APP_MAPS} category, and the launcher will start the
 * Activity on the real display.
 *
 * <p>Note: The state of the virtual display in the ActivityView is nondeterministic when
 * switching away from and back to the current user. To avoid a crash, this Activity will finish
 * when switching users.
 */
public class CarLauncher extends FragmentActivity {
    private static final String TAG = "CarLauncher";
    private static final boolean DEBUG = false;

    private ActivityView mActivityView;
    private boolean mActivityViewReady;
    private boolean mIsStarted;

    /** Set to {@code true} once we've logged that the Activity is fully drawn. */
    private boolean mIsReadyLogged;

    private final ActivityView.StateCallback mActivityViewCallback =
            new ActivityView.StateCallback() {

                // EmptyActivity last task ID. Used to prevent this task to be moved to front.
                // This value can't be cached since it will be concurrently accessed.
                private final AtomicInteger mEmptyActivityTaskId = new AtomicInteger(
                        ActivityTaskManager.INVALID_TASK_ID);

                @Override
                public void onActivityViewReady(ActivityView view) {
                    if (DEBUG) Log.d(TAG, "onActivityViewReady(" + getUserId() + ")");
                    mActivityViewReady = true;
                    startMapsInActivityView();
                    maybeLogReady();
                }

                @Override
                public void onActivityViewDestroyed(ActivityView view) {
                    if (DEBUG) Log.d(TAG, "onActivityViewDestroyed(" + getUserId() + ")");
                    mActivityViewReady = false;
                }

                @Override
                public void onTaskMovedToFront(int taskId) {
                    if (DEBUG) {
                        Log.d(TAG, "onTaskMovedToFront(" + getUserId() + ") with taskId= " +
                                + taskId + "invoked on " + "CarLauncher(mIsStarted=" + mIsStarted
                                + ", mActivityViewReady=" + mActivityViewReady
                                + ", mIsStarted=" + mIsStarted
                                + "mEmptyActivityTaskId=" + mEmptyActivityTaskId + ")");
                    }

                    // Skip EmptyActivity, since we don't want to move CarLauncher forward for it.
                    if (mEmptyActivityTaskId.get() == taskId) {
                        return;
                    }
                    try {
                        if (mActivityViewReady && !mIsStarted) {
                            ActivityManager am =
                                    (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                            am.moveTaskToFront(CarLauncher.this.getTaskId(), /* flags= */ 0);
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Failed to move CarLauncher to front.");
                    }
                }

                @Override
                public void onTaskCreated(int taskId, ComponentName componentName) {
                    if (DEBUG) {
                        Log.d(TAG, "onTaskCreated(" + taskId + ", " + componentName + ")");
                    }
                    if (componentName == null) {
                        return;
                    }
                    if (EmptyActivity.class.getName().equals(componentName.getClassName())) {
                        mEmptyActivityTaskId.set(taskId);
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Don't show the maps panel in multi window mode.
        // NOTE: CTS tests for split screen are not compatible with activity views on the default
        // activity of the launcher
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            setContentView(R.layout.car_launcher_multiwindow);
        } else {
            setContentView(R.layout.car_launcher);
        }
        initializeFragments();
        mActivityView = findViewById(R.id.maps);
        if (mActivityView != null) {
            mActivityView.setCallback(mActivityViewCallback);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Set<String> categories = intent.getCategories();
        if (categories != null && categories.size() == 1 && categories.contains(
                Intent.CATEGORY_APP_MAPS)) {
            launchMapsActivity();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        startMapsInActivityView();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsStarted = true;
        maybeLogReady();

        // Request EmptyActivity to finish
        sendIntentToEmptyActivity(/* stopActivity= */ true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsStarted = false;

        // Request EmptyActivity to start
        sendIntentToEmptyActivity(/* stopActivity= */ false);
    }

    private void sendIntentToEmptyActivity(boolean stopActivity) {
        if (DEBUG) Log.d(TAG, "Sending intent to EmptyActivity with stopActivity:" + stopActivity);

        if (mActivityView != null && mActivityViewReady) {
            if (DEBUG) Log.d(TAG, "Maps exists in CarLauncher");

            Intent intent = new Intent(mActivityView.getContext(), EmptyActivity.class);
            intent.putExtra(EmptyActivity.EXTRA_STOP_ACTIVITY, stopActivity);

            try {
                mActivityView.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "EmptyActivity not found", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mActivityView != null && mActivityViewReady) {
            mActivityView.release();
        }
    }

    /**
     * Empty activity used to ensure that onTaskMovedToFront is invoked when the Activity inside
     * the ActivityView gets the intent while CarLauncher is in the background. See b/154739682
     * for more info.
     */
    public static final class EmptyActivity extends Activity {

        public static final String EXTRA_STOP_ACTIVITY = "EXTRA_STOP_EMPTY_ACTIVITY";

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Setting background color to transparent in order to avoid unnecessary flash when
            // starting this activity.
            getWindow().getDecorView().setBackgroundColor(Color.TRANSPARENT);
            handleEmptyActivityIntent(getIntent());
        }

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            handleEmptyActivityIntent(intent);
        }

        private void handleEmptyActivityIntent(Intent intent) {
            if (DEBUG) Log.d(TAG, "Received intent: " + intent);

            // Finish this activity if required.
            boolean stopActivity = intent.getBooleanExtra(EXTRA_STOP_ACTIVITY,
                    /* defaultValue= */ true);
            if (DEBUG) Log.d(TAG, "Requested to stop EmptyActivity: " + stopActivity);
            if (stopActivity) {
                finish();
            }
        }
    }

    private void startMapsInActivityView() {
        // If we happen to be be resurfaced into a multi display mode we skip launching content
        // in the activity view as we will get recreated anyway.
        if (!mActivityViewReady || isInMultiWindowMode() || isInPictureInPictureMode()) {
            return;
        }
        if (mActivityView != null) {
            mActivityView.startActivity(getMapsIntent());
        }
    }

    private void launchMapsActivity() {
        // Make sure the Activity launches on the current display instead of in the ActivityView
        // virtual display.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(getDisplay().getDisplayId());
        startActivity(getMapsIntent(), options.toBundle());
    }

    private Intent getMapsIntent() {
        return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initializeFragments();
    }

    private void initializeFragments() {
        PlaybackFragment playbackFragment = new PlaybackFragment();
        ContextualFragment contextualFragment = null;
        FrameLayout contextual = findViewById(R.id.contextual);
        if(contextual != null) {
            contextualFragment = new ContextualFragment();
        }

        FragmentTransaction fragmentTransaction =
                getSupportFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.playback, playbackFragment);
        if(contextual != null) {
            fragmentTransaction.replace(R.id.contextual, contextualFragment);
        }
        fragmentTransaction.commitNow();
    }

    /** Logs that the Activity is ready. Used for startup time diagnostics. */
    private void maybeLogReady() {
        if (DEBUG) {
            Log.d(TAG, "maybeLogReady(" + getUserId() + "): activityReady=" + mActivityViewReady
                    + ", started=" + mIsStarted + ", alreadyLogged: " + mIsReadyLogged);
        }
        if (mActivityViewReady && mIsStarted) {
            // We should report every time - the Android framework will take care of logging just
            // when it's effectively drawn for the first time, but....
            reportFullyDrawn();
            if (!mIsReadyLogged) {
                // ... we want to manually check that the Log.i below (which is useful to show
                // the user id) is only logged once (otherwise it would be logged every time the
                // user taps Home)
                Log.i(TAG, "Launcher for user " + getUserId() + " is ready");
                mIsReadyLogged = true;
            }
        }
    }
}
