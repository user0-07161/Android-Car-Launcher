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
import com.android.wm.shell.common.SyncTransactionQueue;

import java.util.List;

/**
 * Controls the bounds of the home background and application displays. This is a singleton class as
 * there should be one controller used to register and control the DA's
 */
public class CarDisplayAreaController {
    private static final String TAG = "CarDisplayAreaController";
    // Layer index of how display areas f=should be placed. Keeping a gap of 100 if we want to
    // add some other display area layers in between in future.
    private static final int BACKGROUND_LAYER_INDEX = 0;
    private static final int FOREGROUND_LAYER_INDEX = 100;
    private static final CarDisplayAreaController INSTANCE = new CarDisplayAreaController();

    private final Rect mForegroundApplicationDisplayBounds = new Rect();
    private final Rect mBackgroundApplicationDisplayBounds = new Rect();
    private final Rect mNavBarBounds = new Rect();

    private Context mContext;
    private SyncTransactionQueue mSyncQueue;
    private CarDisplayAreaOrganizer mOrganizer;
    private DisplayAreaAppearedInfo mForegroundApplicationsDisplay;
    private DisplayAreaAppearedInfo mBackgroundApplicationDisplay;
    private int mDpiDensity;
    private int mTotalHeight = -1;
    private int mTotalWidth = -1;
    // height of DA hosting the control bar.
    private int mControlBarDisplayHeight;
    // height of DA hosting default apps and covering the maps fully.
    private int mFullDisplayHeight;
    // height of DA hosting default apps and covering the maps to default height.
    private int mDefaultDisplayHeight;

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
        mContext = context;
        mSyncQueue = syncQueue;
        mOrganizer = organizer;
        mTotalHeight = mContext.getResources().getDimensionPixelSize(R.dimen.total_screen_height);
        mTotalWidth = mContext.getResources().getDimensionPixelSize(R.dimen.total_screen_width);
        mControlBarDisplayHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.control_bar_height);
        mFullDisplayHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.full_app_display_area_height);
        mDefaultDisplayHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.default_app_display_area_height);

        // Get bottom nav bar height.
        Resources resources = context.getResources();
        int navBarHeight = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        if (navBarHeight > 0) {
            mNavBarBounds.set(0, mTotalHeight - navBarHeight, mTotalWidth, mTotalHeight);
        }

        // Get left nav bar width.
        int leftNavBarWidthResId = resources
                .getIdentifier("car_left_system_bar_width", "dimen", "android");
        int leftNavBarWidth = 0;
        if (leftNavBarWidthResId > 0) {
            leftNavBarWidth = resources.getDimensionPixelSize(leftNavBarWidthResId);
            mNavBarBounds.set(0, 0, leftNavBarWidth, mTotalHeight);
        }

        // Get right nav bar width.
        int rightNavBarWidthResId = resources
                .getIdentifier("car_right_system_bar_width", "dimen", "android");
        int rightNavBarWidth = 0;
        if (rightNavBarWidthResId > 0) {
            rightNavBarWidth = resources.getDimensionPixelSize(rightNavBarWidthResId);
            mNavBarBounds.set(mTotalWidth - rightNavBarWidth, 0, mTotalWidth, mTotalHeight);
        }
    }

    private CarDisplayAreaController() {
    }

    /** Registers the DA organizer. */
    public void register(boolean changeForegroundApplicationBoundBeforeAnimation) {
        mDpiDensity = mOrganizer.getDpiDensity();

        // Register DA organizer.
        registerOrganizer();

        // Pre-calculate the foreground and background display bounds for different configs.
        populateBounds(changeForegroundApplicationBoundBeforeAnimation);

        // Set the initial bounds for first and second displays.
        WindowContainerTransaction wct = new WindowContainerTransaction();
        updateBounds(wct);
        mOrganizer.applyTransaction(wct);
    }

    /** Registers DA organizer. */
    private void registerOrganizer() {
        List<DisplayAreaAppearedInfo> foregroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(FOREGROUND_DISPLAY_AREA_ROOT);
        List<DisplayAreaAppearedInfo> backgroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(BACKGROUND_TASK_CONTAINER);
        if (foregroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch default applications");
        }
        if (backgroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch activity in background");
        }

        // As we have only 1 display defined for each display area feature get the 0th index.
        mForegroundApplicationsDisplay = foregroundDisplayAreaInfos.get(0);
        mBackgroundApplicationDisplay = backgroundDisplayAreaInfos.get(0);
        SurfaceControl.Transaction tx =
                new SurfaceControl.Transaction();
        // TODO(b/188102153): replace to set mForegroundApplicationsDisplay to top.
        tx.setLayer(mBackgroundApplicationDisplay.getLeash(), BACKGROUND_LAYER_INDEX);
        tx.setLayer(mForegroundApplicationsDisplay.getLeash(), FOREGROUND_LAYER_INDEX);
        tx.apply();
    }

    /** Un-Registers DA organizer. */
    public void unregister() {
        mOrganizer.resetWindowsOffset();
        mOrganizer.unregisterOrganizer();
        mForegroundApplicationsDisplay = null;
        mBackgroundApplicationDisplay = null;
    }

    /**
     * This method should be called after the registration of DA's are done. The method expects a
     * target state as an argument, according to which the animations will take place. For example,
     * if the target state is {@link AppGridActivity.CAR_LAUNCHER_STATE#DEFAULT} then the foreground
     * DA hosting default applications will animate to the default set height.
     */
    public void startAnimation(AppGridActivity.CAR_LAUNCHER_STATE state) {
        // TODO: currently the animations are only bottom/up. Make it more generic animations here.
        int fromPos = 0;
        int toPos = 0;
        switch (state) {
            case CONTROL_BAR:
                // Foreground DA closes.
                fromPos = mTotalHeight - mNavBarBounds.height()
                        - mDefaultDisplayHeight;
                toPos = mTotalHeight - mNavBarBounds.height()
                        - mControlBarDisplayHeight;
                mBackgroundApplicationDisplayBounds.bottom = toPos;
                mForegroundApplicationDisplayBounds.bottom =
                        mTotalHeight - mNavBarBounds.height();
                mOrganizer.scheduleOffset(fromPos, toPos, mBackgroundApplicationDisplayBounds,
                        mBackgroundApplicationDisplay, state);
                break;
            case FULL:
                // not implemented yet.
                break;
            default:
                // Foreground DA opens to default height.
                fromPos = mTotalHeight - mNavBarBounds.height()
                        - mControlBarDisplayHeight;
                toPos = mTotalHeight - mNavBarBounds.height()
                        - mDefaultDisplayHeight;
                mBackgroundApplicationDisplayBounds.bottom = toPos;
                mOrganizer.scheduleOffset(fromPos, toPos, mBackgroundApplicationDisplayBounds,
                        mBackgroundApplicationDisplay, state);
        }
    }

    /** Pre-calculates the default and background display bounds for different configs. */
    private void populateBounds(boolean changeForegroundApplicationBoundBeforeAnimation) {
        int dh = mTotalHeight - mNavBarBounds.height();
        int defaultTop = dh - mControlBarDisplayHeight;

        // Populate the bounds depending on where the nav bar is.
        if (mNavBarBounds.left == 0 && mNavBarBounds.top == 0) {
            // Left nav bar.
            mForegroundApplicationDisplayBounds.set(new Rect(mNavBarBounds.right, defaultTop,
                    mTotalWidth, dh));
            mBackgroundApplicationDisplayBounds.set(new Rect(mNavBarBounds.right, 0, mTotalWidth,
                    defaultTop));
        } else if (mNavBarBounds.top == 0) {
            // Right nav bar.
            mForegroundApplicationDisplayBounds.set(new Rect(0, defaultTop, mNavBarBounds.left,
                    dh));
            mBackgroundApplicationDisplayBounds.set(new Rect(0, 0, mNavBarBounds.left, defaultTop));
        } else {
            // Bottom nav bar.
            mForegroundApplicationDisplayBounds.set(new Rect(0, defaultTop, mTotalWidth, dh));
            mBackgroundApplicationDisplayBounds.set(new Rect(0, 0, mTotalWidth, defaultTop));
        }

        if (changeForegroundApplicationBoundBeforeAnimation) {
            mForegroundApplicationDisplayBounds.set(new Rect(0, defaultTop, mTotalWidth,
                    dh - mControlBarDisplayHeight + mDefaultDisplayHeight));

        }
    }

    /** Updates the default and background display bounds for the given config. */
    private void updateBounds(WindowContainerTransaction wct) {
        Rect foregroundApplicationDisplayBound = mForegroundApplicationDisplayBounds;
        Rect backgroundApplicationDisplayBound = mBackgroundApplicationDisplayBounds;
        WindowContainerToken foregroundDisplayToken =
                mForegroundApplicationsDisplay.getDisplayAreaInfo().token;
        WindowContainerToken backgroundDisplayToken =
                mBackgroundApplicationDisplay.getDisplayAreaInfo().token;

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

        mSyncQueue.runInSync(t -> {
            t.setPosition(mForegroundApplicationsDisplay.getLeash(),
                    foregroundApplicationDisplayBound.left,
                    foregroundApplicationDisplayBound.top);
            t.setPosition(mBackgroundApplicationDisplay.getLeash(),
                    backgroundApplicationDisplayBound.left,
                    backgroundApplicationDisplayBound.top);
        });
    }
}
