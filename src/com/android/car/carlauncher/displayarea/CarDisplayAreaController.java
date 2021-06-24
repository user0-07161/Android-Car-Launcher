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

import static com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer.BACKGROUND_TASK_CONTAINER;
import static com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer.CONTROL_BAR_DISPLAY_AREA;
import static com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer.FOREGROUND_DISPLAY_AREA_ROOT;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.car.carlauncher.AppGridActivity;
import com.android.car.carlauncher.R;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.util.List;

/**
 * Controls the bounds of the home background and application displays. This is a singleton class as
 * there should be one controller used to register and control the DA's
 */
public class CarDisplayAreaController {
    private static final String TAG = "CarDisplayAreaController";
    // Layer index of how display areas should be placed. Keeping a gap of 100 if we want to
    // add some other display area layers in between in future.
    static final int BACKGROUND_LAYER_INDEX = 0;
    static final int FOREGROUND_LAYER_INDEX = 100;
    static final int CONTROL_BAR_LAYER_INDEX = 200;
    static final CarDisplayAreaController INSTANCE = new CarDisplayAreaController();

    private final Rect mControlBarDisplayBounds = new Rect();
    private final Rect mForegroundApplicationDisplayBounds = new Rect();
    private final Rect mBackgroundApplicationDisplayBounds = new Rect();
    private final Rect mNavBarBounds = new Rect();

    private SyncTransactionQueue mSyncQueue;
    private CarDisplayAreaOrganizer mOrganizer;
    private DisplayAreaAppearedInfo mForegroundApplicationsDisplay;
    private DisplayAreaAppearedInfo mBackgroundApplicationDisplay;
    private DisplayAreaAppearedInfo mControlBarDisplay;
    private int mDpiDensity;
    private int mTotalScreenWidth = -1;
    // height of DA hosting the control bar.
    private int mControlBarDisplayHeight;
    // height of DA hosting default apps and covering the maps fully.
    private int mFullDisplayHeight;
    // height of DA hosting default apps and covering the maps to default height.
    private int mDefaultDisplayHeight;
    private int mScreenHeightWithoutNavBar;
    private boolean mIsHostingDefaultApplicationDisplayAreaVisible;
    private CarDisplayAreaTouchHandler mCarDisplayAreaTouchHandler;
    public static boolean sIsRegistered;

    /**
     * Gets the instance of {@link CarDisplayAreaController}
     */
    public static CarDisplayAreaController getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes the controller
     */
    public void init(Context context, SyncTransactionQueue syncQueue,
            CarDisplayAreaOrganizer organizer) {
        mSyncQueue = syncQueue;
        mOrganizer = organizer;
        int totalScreenHeight = context.getResources().getDimensionPixelSize(
                R.dimen.total_screen_height);
        mTotalScreenWidth = context.getResources().getDimensionPixelSize(
                R.dimen.total_screen_width);
        mControlBarDisplayHeight = context.getResources().getDimensionPixelSize(
                R.dimen.control_bar_height);
        mFullDisplayHeight = context.getResources().getDimensionPixelSize(
                R.dimen.full_app_display_area_height);
        mDefaultDisplayHeight = context.getResources().getDimensionPixelSize(
                R.dimen.default_app_display_area_height);
        mCarDisplayAreaTouchHandler = new CarDisplayAreaTouchHandler(
                new HandlerExecutor(context.getMainThreadHandler()));

        // Get bottom nav bar height.
        Resources resources = context.getResources();
        int navBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        if (navBarHeight > 0) {
            mNavBarBounds.set(0, totalScreenHeight - navBarHeight, mTotalScreenWidth,
                    totalScreenHeight);
        }

        // Get left nav bar width.
        int leftNavBarWidthResId = resources
                .getIdentifier("car_left_system_bar_width", "dimen", "android");
        int leftNavBarWidth = 0;
        if (leftNavBarWidthResId > 0) {
            leftNavBarWidth = resources.getDimensionPixelSize(leftNavBarWidthResId);
            mNavBarBounds.set(0, 0, leftNavBarWidth, totalScreenHeight);
        }

        // Get right nav bar width.
        int rightNavBarWidthResId = resources
                .getIdentifier("car_right_system_bar_width", "dimen", "android");
        int rightNavBarWidth = 0;
        if (rightNavBarWidthResId > 0) {
            rightNavBarWidth = resources.getDimensionPixelSize(rightNavBarWidthResId);
            mNavBarBounds.set(mTotalScreenWidth - rightNavBarWidth, 0, mTotalScreenWidth,
                    totalScreenHeight);
        }

        mScreenHeightWithoutNavBar = totalScreenHeight - mNavBarBounds.height();
    }

    private CarDisplayAreaController() {
    }

    /**
     * Returns if display area hosting default application is visible to user or not.
     */
    public boolean isHostingDefaultApplicationDisplayAreaVisible() {
        return mIsHostingDefaultApplicationDisplayAreaVisible;
    }

    /** Registers the DA organizer. */
    public void register() {
        mDpiDensity = mOrganizer.getDpiDensity();
        sIsRegistered = true;

        // Register DA organizer.
        registerOrganizer();

        // Pre-calculate the foreground and background display bounds for different configs.
        populateBounds();

        // Set the initial bounds for first and second displays.
        WindowContainerTransaction wct = new WindowContainerTransaction();
        updateBounds(wct);
        mOrganizer.applyTransaction(wct);

        mCarDisplayAreaTouchHandler.registerTouchEventListener((x, y) -> {
            // Check if the click is outside the bounds of default display. If so, close the
            // display area.
            if (mIsHostingDefaultApplicationDisplayAreaVisible
                    && y < (mScreenHeightWithoutNavBar - mDefaultDisplayHeight)) {
                // TODO: closing logic goes here, something like: startAnimation(CONTROL_BAR);
            }
        });
        mCarDisplayAreaTouchHandler.enable(true);
    }

    /** Registers DA organizer. */
    private void registerOrganizer() {
        List<DisplayAreaAppearedInfo> foregroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(FOREGROUND_DISPLAY_AREA_ROOT);
        List<DisplayAreaAppearedInfo> backgroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(BACKGROUND_TASK_CONTAINER);
        List<DisplayAreaAppearedInfo> controlBarDisplayAreaInfos =
                mOrganizer.registerOrganizer(CONTROL_BAR_DISPLAY_AREA);
        if (foregroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch default applications");
        }
        if (backgroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch activity in background");
        }
        if (controlBarDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch audio control");
        }

        // As we have only 1 display defined for each display area feature get the 0th index.
        mForegroundApplicationsDisplay = foregroundDisplayAreaInfos.get(0);
        mBackgroundApplicationDisplay = backgroundDisplayAreaInfos.get(0);
        mControlBarDisplay = controlBarDisplayAreaInfos.get(0);
        SurfaceControl.Transaction tx =
                new SurfaceControl.Transaction();
        // TODO(b/188102153): replace to set mForegroundApplicationsDisplay to top.
        tx.setLayer(mBackgroundApplicationDisplay.getLeash(), BACKGROUND_LAYER_INDEX);
        tx.setLayer(mForegroundApplicationsDisplay.getLeash(), FOREGROUND_LAYER_INDEX);
        tx.setLayer(mControlBarDisplay.getLeash(), CONTROL_BAR_LAYER_INDEX);
        tx.apply();
    }

    /** Un-Registers DA organizer. */
    public void unregister() {
        mOrganizer.resetWindowsOffset();
        mOrganizer.unregisterOrganizer();
        mForegroundApplicationsDisplay = null;
        mBackgroundApplicationDisplay = null;
        mControlBarDisplay = null;
        mCarDisplayAreaTouchHandler.enable(false);
        sIsRegistered = false;
    }

    /**
     * This method should be called after the registration of DA's are done. The method expects a
     * target state as an argument, according to which the animations will take place. For example,
     * if the target state is {@link AppGridActivity.CAR_LAUNCHER_STATE#DEFAULT} then the foreground
     * DA hosting default applications will animate to the default set height.
     */
    public void startAnimation(AppGridActivity.CAR_LAUNCHER_STATE toState) {
        // TODO: currently the animations are only bottom/up. Make it more generic animations here.
        int fromPos = 0;
        int toPos = 0;
        switch (toState) {
            case CONTROL_BAR:
                // Foreground DA closes.
                fromPos = mScreenHeightWithoutNavBar - mDefaultDisplayHeight;
                toPos = mScreenHeightWithoutNavBar;
                mBackgroundApplicationDisplayBounds.bottom =
                        mScreenHeightWithoutNavBar - mControlBarDisplayHeight;
                mOrganizer.scheduleOffset(fromPos, toPos, mBackgroundApplicationDisplayBounds,
                        mBackgroundApplicationDisplay, mForegroundApplicationsDisplay,
                        mControlBarDisplay, toState);
                mIsHostingDefaultApplicationDisplayAreaVisible = false;
                break;
            case FULL:
                // TODO: Implement this.
                break;
            default:
                // Foreground DA opens to default height.
                // update the bounds to expand the foreground display area before starting
                // animations.
                fromPos = mScreenHeightWithoutNavBar;
                toPos = mScreenHeightWithoutNavBar - mDefaultDisplayHeight;
                mBackgroundApplicationDisplayBounds.bottom = toPos;
                mOrganizer.scheduleOffset(fromPos, toPos, mBackgroundApplicationDisplayBounds,
                        mBackgroundApplicationDisplay, mForegroundApplicationsDisplay,
                        mControlBarDisplay, toState);
                mIsHostingDefaultApplicationDisplayAreaVisible = true;
        }
    }

    /** Pre-calculates the default and background display bounds for different configs. */
    private void populateBounds() {
        int controlBarTop = mScreenHeightWithoutNavBar - mControlBarDisplayHeight;

        // Bottom nav bar. Bottom nav bar height will be 0 if the nav bar is present on the sides.
        Rect backgroundBounds = new Rect(0, 0, mTotalScreenWidth, controlBarTop);
        Rect controlBarBounds = new Rect(0, controlBarTop, mTotalScreenWidth,
                mScreenHeightWithoutNavBar);
        Rect foregroundBounds = new Rect(0, mScreenHeightWithoutNavBar - mDefaultDisplayHeight,
                mTotalScreenWidth, mScreenHeightWithoutNavBar);

        // Adjust the bounds based on the nav bar.
        // TODO: account for the case where nav bar is at the top.

        // Populate the bounds depending on where the nav bar is.
        if (mNavBarBounds.left == 0 && mNavBarBounds.top == 0) {
            // Left nav bar.
            backgroundBounds.left = mNavBarBounds.right;
            controlBarBounds.left = mNavBarBounds.right;
            foregroundBounds.left = mNavBarBounds.right;
        } else if (mNavBarBounds.top == 0) {
            // Right nav bar.
            backgroundBounds.right = mNavBarBounds.left;
            controlBarBounds.right = mNavBarBounds.left;
            foregroundBounds.right = mNavBarBounds.left;
        }

        mBackgroundApplicationDisplayBounds.set(backgroundBounds);
        mControlBarDisplayBounds.set(controlBarBounds);
        mForegroundApplicationDisplayBounds.set(foregroundBounds);
    }

    /** Updates the default and background display bounds for the given config. */
    private void updateBounds(WindowContainerTransaction wct) {
        Rect foregroundApplicationDisplayBound = mForegroundApplicationDisplayBounds;
        Rect backgroundApplicationDisplayBound = mBackgroundApplicationDisplayBounds;
        Rect controlBarDisplayBound = mControlBarDisplayBounds;
        WindowContainerToken foregroundDisplayToken =
                mForegroundApplicationsDisplay.getDisplayAreaInfo().token;
        WindowContainerToken backgroundDisplayToken =
                mBackgroundApplicationDisplay.getDisplayAreaInfo().token;
        WindowContainerToken controlBarDisplayToken =
                mControlBarDisplay.getDisplayAreaInfo().token;

        int foregroundDisplayWidthDp =
                foregroundApplicationDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int foregroundDisplayHeightDp =
                foregroundApplicationDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(foregroundDisplayToken, foregroundApplicationDisplayBound);
        wct.setScreenSizeDp(foregroundDisplayToken, foregroundDisplayWidthDp,
                foregroundDisplayHeightDp);
        wct.setSmallestScreenWidthDp(foregroundDisplayToken, foregroundDisplayWidthDp);

        int backgroundDisplayWidthDp =
                backgroundApplicationDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int backgroundDisplayHeightDp =
                backgroundApplicationDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(backgroundDisplayToken, backgroundApplicationDisplayBound);
        wct.setScreenSizeDp(backgroundDisplayToken, backgroundDisplayWidthDp,
                backgroundDisplayHeightDp);
        wct.setSmallestScreenWidthDp(backgroundDisplayToken, backgroundDisplayWidthDp);

        int controlBarDisplayWidthDp =
                controlBarDisplayBound.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int controlBarDisplayHeightDp =
                controlBarDisplayBound.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(controlBarDisplayToken, controlBarDisplayBound);
        wct.setScreenSizeDp(controlBarDisplayToken, controlBarDisplayWidthDp,
                controlBarDisplayHeightDp);
        wct.setSmallestScreenWidthDp(controlBarDisplayToken, controlBarDisplayWidthDp);

        mSyncQueue.runInSync(t -> {
            t.setPosition(mForegroundApplicationsDisplay.getLeash(),
                    foregroundApplicationDisplayBound.left,
                    foregroundApplicationDisplayBound.top);
            t.setPosition(mBackgroundApplicationDisplay.getLeash(),
                    backgroundApplicationDisplayBound.left,
                    backgroundApplicationDisplayBound.top);
            t.setPosition(mControlBarDisplay.getLeash(),
                    controlBarDisplayBound.left,
                    controlBarDisplayBound.top);
        });
    }
}
