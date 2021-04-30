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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.car.carlauncher.CarDisplayAreaOrganizer.BACKGROUND_TASK_CONTAINER;
import static com.android.car.carlauncher.CarDisplayAreaOrganizer.FOREGROUND_DISPLAY_AREA_ROOT;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.window.DisplayAreaAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.common.SyncTransactionQueue;

import java.util.List;

/** Controls the bounds of the home background and application displays. */
public class CarDisplayAreaController {
    private static final String TAG = "CarDisplayAreaController";
    private static final CarDisplayAreaController INSTANCE = new CarDisplayAreaController();

    private final Rect[] mForegroundApplicationDisplayBounds = new Rect[1];
    private final Rect[] mBackgroundApplicationDisplayBounds = new Rect[1];
    private final Rect mNavBarBounds = new Rect();

    private Context mContext;
    private SyncTransactionQueue mSyncQueue;
    private CarDisplayAreaOrganizer mOrganizer;
    private DisplayAreaAppearedInfo mForegroundApplicationsDisplay;
    private DisplayAreaAppearedInfo mBackgroundApplicationDisplay;
    private int mDpiDensity;
    private int mTotalHeight = -1;
    private int mTotalWidth = -1;

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
    public void register() {
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        final Display display = displayManager.getDisplay(DEFAULT_DISPLAY);
        final Resources displayResources = mContext.createDisplayContext(display).getResources();

        mDpiDensity = displayResources.getConfiguration().densityDpi;

        // Register DA organizer.
        registerOrganizer();

        // Pre-calculate the foreground and background display bounds for different configs.
        populateBounds();

        // Set the initial bounds for first and second displays.
        final WindowContainerTransaction wct = new WindowContainerTransaction();
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

        mForegroundApplicationsDisplay = foregroundDisplayAreaInfos.get(0);
        mBackgroundApplicationDisplay = backgroundDisplayAreaInfos.get(0);
    }

    /** Un-Registers DA organizer. */
    public void unregister() {
        mOrganizer.unregisterOrganizer();
        mForegroundApplicationsDisplay = null;
        mBackgroundApplicationDisplay = null;
    }

    /** Pre-calculates the default and background display bounds for different configs. */
    private void populateBounds() {
        int dh = mTotalHeight - mNavBarBounds.height();
        int defaultTop = (dh) / 2;

        // Populate the bounds depending on where the nav bar is.
        if (mNavBarBounds.left == 0 && mNavBarBounds.top == 0) {
            // Left nav bar.
            mForegroundApplicationDisplayBounds[0] = new Rect(mNavBarBounds.right, defaultTop,
                    mTotalWidth, dh);
            mBackgroundApplicationDisplayBounds[0] = new Rect(mNavBarBounds.right, 0, mTotalWidth,
                    defaultTop);
        } else if (mNavBarBounds.top == 0) {
            // Right nav bar.
            mForegroundApplicationDisplayBounds[0] = new Rect(0, defaultTop, mNavBarBounds.left,
                    dh);
            mBackgroundApplicationDisplayBounds[0] = new Rect(0, 0, mNavBarBounds.left, defaultTop);
        } else {
            // Bottom nav bar.
            mForegroundApplicationDisplayBounds[0] = new Rect(0, defaultTop, mTotalWidth, dh);
            mBackgroundApplicationDisplayBounds[0] = new Rect(0, 0, mTotalWidth, defaultTop);
        }
    }

    /** Updates the default and background display bounds for the given config. */
    private void updateBounds(WindowContainerTransaction wct) {
        Rect foregroundApplicationDisplayBound = mForegroundApplicationDisplayBounds[0];
        Rect backgroundApplicationDisplayBound = mBackgroundApplicationDisplayBounds[0];
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
