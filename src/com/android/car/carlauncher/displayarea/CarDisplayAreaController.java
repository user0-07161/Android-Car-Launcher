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

import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;
import static android.window.DisplayAreaOrganizer.KEY_ROOT_DISPLAY_AREA_ID;

import static com.android.car.carlauncher.AppGridActivity.CAR_LAUNCHER_STATE.CONTROL_BAR;
import static com.android.car.carlauncher.AppGridActivity.CAR_LAUNCHER_STATE.DEFAULT;
import static com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer.BACKGROUND_TASK_CONTAINER;
import static com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer.CONTROL_BAR_DISPLAY_AREA;
import static com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer.FEATURE_TITLE_BAR;
import static com.android.car.carlauncher.displayarea.CarDisplayAreaOrganizer.FOREGROUND_DISPLAY_AREA_ROOT;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.ImageView;
import android.window.DisplayAreaAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

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
    static final int BACKGROUND_LAYER_INDEX = 200;
    static final int FOREGROUND_LAYER_INDEX = 100;
    static final int CONTROL_BAR_LAYER_INDEX = 0;
    static final CarDisplayAreaController INSTANCE = new CarDisplayAreaController();
    private static final int TITLE_BAR_WINDOW_TYPE =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

    private final Rect mControlBarDisplayBounds = new Rect();
    private final Rect mForegroundApplicationDisplayBounds = new Rect();
    private final Rect mTitleBarDisplayBounds = new Rect();
    private final Rect mBackgroundApplicationDisplayBounds = new Rect();
    private final Rect mNavBarBounds = new Rect();
    private final IBinder mWindowToken = new Binder();

    private SyncTransactionQueue mSyncQueue;
    private CarDisplayAreaOrganizer mOrganizer;
    private DisplayAreaAppearedInfo mForegroundApplicationsDisplay;
    private DisplayAreaAppearedInfo mTitleBarDisplay;
    private DisplayAreaAppearedInfo mBackgroundApplicationDisplay;
    private DisplayAreaAppearedInfo mControlBarDisplay;
    private int mTitleBarDragThreshold;
    private int mEnterExitAnimationDurationMs;
    private int mDpiDensity;
    private int mTotalScreenWidth = -1;
    // height of DA hosting the control bar.
    private int mControlBarDisplayHeight;
    // height of DA hosting default apps and covering the maps fully.
    private int mFullDisplayHeight;
    // height of DA hosting default apps and covering the maps to default height.
    private int mDefaultDisplayHeight;
    private int mTitleBarHeight;
    private int mScreenHeightWithoutNavBar;
    private boolean mIsHostingDefaultApplicationDisplayAreaVisible;
    private CarDisplayAreaTouchHandler mCarDisplayAreaTouchHandler;

    private WindowManager mTitleBarWindowManager;
    private View mTitleBarView;
    private Context mApplicationContext;
    private int mForegroundDisplayTop;

    /**
     * The WindowContext that is registered with {@link #mTitleBarWindowManager} with options to
     * specify the {@link RootDisplayArea} to attach the confirmation window.
     */
    @Nullable
    private Context mTitleBarWindowContext;

    /**
     * Gets the instance of {@link CarDisplayAreaController}
     */
    public static CarDisplayAreaController getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes the controller
     */
    public void init(Context applicationContext, SyncTransactionQueue syncQueue,
            CarDisplayAreaOrganizer organizer) {
        mApplicationContext = applicationContext;
        mSyncQueue = syncQueue;
        mOrganizer = organizer;
        int totalScreenHeight = applicationContext.getResources().getDimensionPixelSize(
                R.dimen.total_screen_height);
        mTotalScreenWidth = applicationContext.getResources().getDimensionPixelSize(
                R.dimen.total_screen_width);
        mControlBarDisplayHeight = applicationContext.getResources().getDimensionPixelSize(
                R.dimen.control_bar_height);
        mFullDisplayHeight = applicationContext.getResources().getDimensionPixelSize(
                R.dimen.full_app_display_area_height);
        mDefaultDisplayHeight = applicationContext.getResources().getDimensionPixelSize(
                R.dimen.default_app_display_area_height);
        mCarDisplayAreaTouchHandler = new CarDisplayAreaTouchHandler(
                new HandlerExecutor(applicationContext.getMainThreadHandler()));

        // Get bottom nav bar height.
        Resources resources = applicationContext.getResources();
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
        mTitleBarHeight = resources.getDimensionPixelSize(R.dimen.title_bar_display_area_height);
        mEnterExitAnimationDurationMs = applicationContext.getResources().getInteger(
                R.integer.enter_exit_animation_foreground_display_area_duration_ms);
        mTitleBarDragThreshold = applicationContext.getResources().getDimensionPixelSize(
                R.dimen.title_bar_display_area_touch_drag_threshold);
        mForegroundDisplayTop = mScreenHeightWithoutNavBar - mDefaultDisplayHeight;
    }

    private CarDisplayAreaController() {
    }

    /**
     * Show the title bar within a targeted display area using the rootDisplayAreaId.
     */
    public void showTitleBar(int rootDisplayAreaId, Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        mTitleBarView = inflater
                .inflate(R.layout.title_bar_display_area_view, null, true);

        // Show the confirmation.
        WindowManager.LayoutParams lp = getTitleBarWindowLayoutParams();
        getWindowManager(rootDisplayAreaId, context).addView(mTitleBarView, lp);

        ImageView closeForegroundDisplay = mTitleBarView.findViewById(
                R.id.close_foreground_display);
        closeForegroundDisplay.setOnClickListener(v -> {
            // Close the foreground display area.
            startAnimation(CONTROL_BAR);
        });
    }

    private WindowManager getWindowManager(int rootDisplayAreaId, Context context) {
        Bundle options = getOptionWithRootDisplayArea(rootDisplayAreaId);
        if (mTitleBarWindowManager == null || mTitleBarWindowContext == null) {
            // Create window context to specify the RootDisplayArea
            mTitleBarWindowContext = context.createWindowContext(
                    TITLE_BAR_WINDOW_TYPE, options);
            mTitleBarWindowManager = mTitleBarWindowContext.getSystemService(WindowManager.class);
            return mTitleBarWindowManager;
        }

        // Update the window context and window manager to specify the RootDisplayArea
        IWindowManager wms = WindowManagerGlobal.getWindowManagerService();
        try {
            wms.attachWindowContextToDisplayArea(mTitleBarWindowContext.getWindowContextToken(),
                    TITLE_BAR_WINDOW_TYPE, context.getDisplayId(), options);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        return mTitleBarWindowManager;
    }

    /**
     * Returns options that specify the {@link RootDisplayArea} to attach the confirmation window.
     * {@code null} if the {@code rootDisplayAreaId} is {@link FEATURE_UNDEFINED}.
     */
    @Nullable
    private static Bundle getOptionWithRootDisplayArea(int rootDisplayAreaId) {
        // In case we don't care which root display area the window manager is specifying.
        if (rootDisplayAreaId == FEATURE_UNDEFINED) {
            return null;
        }

        Bundle options = new Bundle();
        options.putInt(KEY_ROOT_DISPLAY_AREA_ID, rootDisplayAreaId);
        return options;
    }

    private WindowManager.LayoutParams getTitleBarWindowLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mTitleBarHeight,
                TITLE_BAR_WINDOW_TYPE,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(lp.getFitInsetsTypes() & ~WindowInsets.Type.statusBars());
        // Trusted overlay so touches outside the touchable area are allowed to pass through
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        lp.setTitle("TitleBar");
        lp.gravity = Gravity.TOP;
        lp.token = mWindowToken;
        return lp;
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

        // Register DA organizer.
        registerOrganizer();

        // Pre-calculate the foreground and background display bounds for different configs.
        populateBounds();

        // Set the initial bounds for first and second displays.
        WindowContainerTransaction wct = new WindowContainerTransaction();
        updateBounds(wct);
        mOrganizer.applyTransaction(wct);

        mCarDisplayAreaTouchHandler.registerOnClickListener((x, y) -> {
            // Check if the click is outside the bounds of default display. If so, close the
            // display area.
            if (mIsHostingDefaultApplicationDisplayAreaVisible
                    && y < (mForegroundDisplayTop)) {
                // TODO: closing logic goes here, something like: startAnimation(CONTROL_BAR);
            }
        });

        mCarDisplayAreaTouchHandler.registerTouchEventListener(
                new CarDisplayAreaTouchHandler.OnDragDisplayAreaListener() {

                    float mCurrentPos = -1;
                    @Override
                    public void onStart(float x, float y) {
                        mCurrentPos = mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                                - mControlBarDisplayHeight;
                    }

                    @Override
                    public void onMove(float x, float y) {
                        if (y <= mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                                - mControlBarDisplayHeight) {
                            return;
                        }
                        animateToControlBarState((int) mCurrentPos, (int) y, 0);
                        mCurrentPos = y;
                    }

                    @Override
                    public void onFinish(float x, float y) {
                        if (y >= mTitleBarDragThreshold) {
                            animateToControlBarState((int) y,
                                    mScreenHeightWithoutNavBar + mTitleBarHeight, 0);
                            mCarDisplayAreaTouchHandler.updateTitleBarVisibility(false);
                        } else {
                            animateToDefaultState((int) y,
                                    mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                                            - mControlBarDisplayHeight, 0);
                        }
                    }
                });
        mCarDisplayAreaTouchHandler.enable(true);
    }

    /** Registers DA organizer. */
    private void registerOrganizer() {
        List<DisplayAreaAppearedInfo> foregroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(FOREGROUND_DISPLAY_AREA_ROOT);
        if (foregroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch default applications");
        }

        List<DisplayAreaAppearedInfo> titleBarDisplayAreaInfo =
                mOrganizer.registerOrganizer(FEATURE_TITLE_BAR);
        if (titleBarDisplayAreaInfo.size() != 1) {
            throw new IllegalStateException("Can't find display to launch title bar");
        }

        List<DisplayAreaAppearedInfo> backgroundDisplayAreaInfos =
                mOrganizer.registerOrganizer(BACKGROUND_TASK_CONTAINER);
        if (backgroundDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch activity in background");
        }

        List<DisplayAreaAppearedInfo> controlBarDisplayAreaInfos =
                mOrganizer.registerOrganizer(CONTROL_BAR_DISPLAY_AREA);
        if (controlBarDisplayAreaInfos.size() != 1) {
            throw new IllegalStateException("Can't find display to launch audio control");
        }

        // As we have only 1 display defined for each display area feature get the 0th index.
        mForegroundApplicationsDisplay = foregroundDisplayAreaInfos.get(0);
        mTitleBarDisplay = titleBarDisplayAreaInfo.get(0);
        mBackgroundApplicationDisplay = backgroundDisplayAreaInfos.get(0);
        mControlBarDisplay = controlBarDisplayAreaInfos.get(0);
    }

    /** Un-Registers DA organizer. */
    public void unregister() {
        mOrganizer.resetWindowsOffset();
        mOrganizer.unregisterOrganizer();
        mForegroundApplicationsDisplay = null;
        mTitleBarDisplay = null;
        mBackgroundApplicationDisplay = null;
        mControlBarDisplay = null;
        mCarDisplayAreaTouchHandler.enable(false);
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
                fromPos = mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                        - mControlBarDisplayHeight;
                toPos = mScreenHeightWithoutNavBar + mTitleBarHeight;
                animateToControlBarState(fromPos, toPos, mEnterExitAnimationDurationMs);
                break;
            case FULL:
                // TODO: Implement this.
                break;
            default:
                // Foreground DA opens to default height.
                // update the bounds to expand the foreground display area before starting
                // animations.
                fromPos = mScreenHeightWithoutNavBar + mTitleBarHeight;
                toPos = mScreenHeightWithoutNavBar - mDefaultDisplayHeight
                        - mControlBarDisplayHeight;
                animateToDefaultState(fromPos, toPos, mEnterExitAnimationDurationMs);
        }
    }

    private void animateToControlBarState(int fromPos, int toPos, int durationMs) {
        mBackgroundApplicationDisplayBounds.bottom =
                mScreenHeightWithoutNavBar - mControlBarDisplayHeight;
        animate(fromPos, toPos, CONTROL_BAR, durationMs);
        mIsHostingDefaultApplicationDisplayAreaVisible = false;
    }

    private void animateToDefaultState(int fromPos, int toPos, int durationMs) {
        mBackgroundApplicationDisplayBounds.bottom = toPos - mTitleBarHeight;
        animate(fromPos, toPos, DEFAULT, durationMs);
        mIsHostingDefaultApplicationDisplayAreaVisible = true;
        mCarDisplayAreaTouchHandler.updateTitleBarVisibility(true);
    }

    private void animate(int fromPos, int toPos, AppGridActivity.CAR_LAUNCHER_STATE toState,
            int durationMs) {
        mOrganizer.scheduleOffset(fromPos, toPos, mBackgroundApplicationDisplayBounds,
                mBackgroundApplicationDisplay, mForegroundApplicationsDisplay,
                mTitleBarDisplay,
                mControlBarDisplay, toState, durationMs);
    }

    /** Pre-calculates the default and background display bounds for different configs. */
    private void populateBounds() {
        int controlBarTop = mScreenHeightWithoutNavBar - mControlBarDisplayHeight;
        int foregroundTop =
                mScreenHeightWithoutNavBar - mDefaultDisplayHeight - mControlBarDisplayHeight;

        // Bottom nav bar. Bottom nav bar height will be 0 if the nav bar is present on the sides.
        Rect backgroundBounds = new Rect(0, 0, mTotalScreenWidth, controlBarTop);
        Rect controlBarBounds = new Rect(0, controlBarTop, mTotalScreenWidth,
                mScreenHeightWithoutNavBar);
        Rect foregroundBounds = new Rect(0,
                foregroundTop, mTotalScreenWidth,
                mScreenHeightWithoutNavBar - mControlBarDisplayHeight);
        Rect titleBarBounds = new Rect(0,
                foregroundTop - mTitleBarHeight, mTotalScreenWidth, foregroundTop);

        // Adjust the bounds based on the nav bar.
        // TODO: account for the case where nav bar is at the top.

        // Populate the bounds depending on where the nav bar is.
        if (mNavBarBounds.left == 0 && mNavBarBounds.top == 0) {
            // Left nav bar.
            backgroundBounds.left = mNavBarBounds.right;
            controlBarBounds.left = mNavBarBounds.right;
            foregroundBounds.left = mNavBarBounds.right;
            titleBarBounds.left = mNavBarBounds.right;
        } else if (mNavBarBounds.top == 0) {
            // Right nav bar.
            backgroundBounds.right = mNavBarBounds.left;
            controlBarBounds.right = mNavBarBounds.left;
            foregroundBounds.right = mNavBarBounds.left;
            titleBarBounds.right = mNavBarBounds.left;
        }

        mBackgroundApplicationDisplayBounds.set(backgroundBounds);
        mControlBarDisplayBounds.set(controlBarBounds);
        mForegroundApplicationDisplayBounds.set(foregroundBounds);
        mTitleBarDisplayBounds.set(titleBarBounds);
        mCarDisplayAreaTouchHandler.setTitleBarBounds(titleBarBounds);
    }

    /** Updates the default and background display bounds for the given config. */
    private void updateBounds(WindowContainerTransaction wct) {
        Rect foregroundApplicationDisplayBound = mForegroundApplicationDisplayBounds;
        Rect titleBarDisplayBounds = mTitleBarDisplayBounds;
        Rect backgroundApplicationDisplayBound = mBackgroundApplicationDisplayBounds;
        Rect controlBarDisplayBound = mControlBarDisplayBounds;

        WindowContainerToken foregroundDisplayToken =
                mForegroundApplicationsDisplay.getDisplayAreaInfo().token;
        WindowContainerToken titleBarDisplayToken =
                mTitleBarDisplay.getDisplayAreaInfo().token;
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

        int titleBarDisplayWidthDp =
                titleBarDisplayBounds.width() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        int titleBarDisplayHeightDp =
                titleBarDisplayBounds.height() * DisplayMetrics.DENSITY_DEFAULT
                        / mDpiDensity;
        wct.setBounds(titleBarDisplayToken, titleBarDisplayBounds);
        wct.setScreenSizeDp(titleBarDisplayToken, titleBarDisplayWidthDp,
                titleBarDisplayHeightDp);
        wct.setSmallestScreenWidthDp(titleBarDisplayToken, titleBarDisplayWidthDp);

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
            t.setPosition(mTitleBarDisplay.getLeash(),
                    titleBarDisplayBounds.left, -mTitleBarHeight);
            t.setPosition(mBackgroundApplicationDisplay.getLeash(),
                    backgroundApplicationDisplayBound.left,
                    backgroundApplicationDisplayBound.top);
            t.setPosition(mControlBarDisplay.getLeash(),
                    controlBarDisplayBound.left,
                    controlBarDisplayBound.top);
        });
    }
}
