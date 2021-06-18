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

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerToken;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Organizer for controlling the policies defined in
 * {@link com.android.server.wm.CarDisplayAreaPolicyProvider}
 */
public class CarDisplayAreaOrganizer extends DisplayAreaOrganizer {

    /**
     * The display partition to launch applications by default.
     */
    public static final int FOREGROUND_DISPLAY_AREA_ROOT = FEATURE_VENDOR_FIRST + 1;

    /**
     * Background applications task container.
     */
    public static final int BACKGROUND_TASK_CONTAINER = FEATURE_VENDOR_FIRST + 2;

    private static CarDisplayAreaOrganizer sCarDisplayAreaOrganizer;
    private final ArrayMap<WindowContainerToken, SurfaceControl> mDisplayAreaTokenMap =
            new ArrayMap();
    private final Context mContext;
    private final Intent mMapsIntent;
    private boolean mIsShowingBackgroundDisplay;

    /**
     * Gets the instance of {@link CarDisplayAreaOrganizer}
     */
    public static CarDisplayAreaOrganizer getInstance(Executor executor,
            Context context, Intent mapsIntent) {
        if (sCarDisplayAreaOrganizer == null) {
            sCarDisplayAreaOrganizer = new CarDisplayAreaOrganizer(executor,
                    context, mapsIntent);
        }
        return sCarDisplayAreaOrganizer;
    }

    private CarDisplayAreaOrganizer(Executor executor, Context context, Intent mapsIntent) {
        super(executor);
        mContext = context;
        mMapsIntent = mapsIntent;
    }

    ArrayMap<WindowContainerToken, SurfaceControl> getDisplayAreaTokenMap() {
        return mDisplayAreaTokenMap;
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        if (displayAreaInfo.featureId == BACKGROUND_TASK_CONTAINER
                && !mIsShowingBackgroundDisplay) {
            startMapsInBackGroundDisplayArea(displayAreaInfo.token);
            mIsShowingBackgroundDisplay = true;
        }
        mDisplayAreaTokenMap.put(displayAreaInfo.token, leash);
    }

    void startMapsInBackGroundDisplayArea(WindowContainerToken token) {
        ActivityOptions options = ActivityOptions
                .makeCustomAnimation(mContext,
                        /* enterResId= */ 0, /* exitResId= */ 0);
        options.setLaunchTaskDisplayArea(token);
        mContext.startActivity(mMapsIntent, options.toBundle());
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        if (displayAreaInfo.featureId == BACKGROUND_TASK_CONTAINER) {
            mIsShowingBackgroundDisplay = false;
        }
        mDisplayAreaTokenMap.remove(displayAreaInfo.token);
    }

    @Override
    public List<DisplayAreaAppearedInfo> registerOrganizer(int displayAreaFeature) {
        List<DisplayAreaAppearedInfo> displayAreaInfos =
                super.registerOrganizer(displayAreaFeature);
        for (DisplayAreaAppearedInfo info : displayAreaInfos) {
            onDisplayAreaAppeared(info.getDisplayAreaInfo(), info.getLeash());
        }
        return displayAreaInfos;
    }
}
