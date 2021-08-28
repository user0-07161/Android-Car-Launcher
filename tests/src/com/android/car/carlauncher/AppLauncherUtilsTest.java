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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

import static com.android.car.carlauncher.AppLauncherUtils.APP_TYPE_LAUNCHABLES;
import static com.android.car.carlauncher.AppLauncherUtils.APP_TYPE_MEDIA_SERVICES;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.car.media.CarMediaManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.service.media.MediaBrowserService;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class AppLauncherUtilsTest {
    private static final String TEST_DISABLED_APP_1 = "com.android.car.test.disabled1";
    private static final String TEST_DISABLED_APP_2 = "com.android.car.test.disabled2";
    private static final String TEST_ENABLED_APP = "com.android.car.test.enabled";

    @Mock
    private Context mMockContext;
    @Mock
    private LauncherApps mMockLauncherApps;
    @Mock
    private PackageManager mMockPackageManager;

    private CarMediaManager mCarMediaManager;
    private CarPackageManager mCarPackageManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Car car = Car.createCar(mMockContext);
        mCarPackageManager = (CarPackageManager) car.getCarManager(Car.PACKAGE_SERVICE);
        mCarMediaManager = (CarMediaManager) car.getCarManager(Car.CAR_MEDIA_SERVICE);
    }

    @Test
    public void testGetLauncherAppsWithEnableAndLaunchDisabledApps() {
        mockPackageManagerQueries();
        injectApplicationEnabledSetting(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);

        AppLauncherUtils.LauncherAppsInfo launcherAppsInfo = AppLauncherUtils.getLauncherApps(
                /* appsToHide= */ new ArraySet<>(), /* customMediaComponents= */ new ArraySet<>(),
                /* appTypes= */ APP_TYPE_LAUNCHABLES + APP_TYPE_MEDIA_SERVICES,
                /* openMediaCenter= */ false, mMockLauncherApps, mCarPackageManager,
                mMockPackageManager, mCarMediaManager);

        List<AppMetaData> appMetaData = launcherAppsInfo.getLaunchableComponentsList();

        assertEquals(3, appMetaData.size());

        injectApplicationEnabledSetting(COMPONENT_ENABLED_STATE_ENABLED);
        launchAllApps(appMetaData);

        verify(mMockPackageManager).setApplicationEnabledSetting(
                eq(TEST_DISABLED_APP_1), eq(COMPONENT_ENABLED_STATE_ENABLED), eq(0));
        verify(mMockPackageManager, times(2)).getApplicationEnabledSetting(eq(TEST_DISABLED_APP_1));

        verify(mMockPackageManager).setApplicationEnabledSetting(
                eq(TEST_DISABLED_APP_2), eq(COMPONENT_ENABLED_STATE_ENABLED), eq(0));
        verify(mMockPackageManager, times(2)).getApplicationEnabledSetting(eq(TEST_DISABLED_APP_2));

        verify(mMockContext, times(2)).startActivity(any(), any());
    }

    @Test
    public void testGetLauncherAppsWithNotEnablingEnabledApps() {
        mockPackageManagerQueries();
        injectApplicationEnabledSetting(COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);

        AppLauncherUtils.LauncherAppsInfo launcherAppsInfo = AppLauncherUtils.getLauncherApps(
                /* appsToHide= */ new ArraySet<>(), /* customMediaComponents= */ new ArraySet<>(),
                /* appTypes= */ APP_TYPE_LAUNCHABLES + APP_TYPE_MEDIA_SERVICES,
                /* openMediaCenter= */ false, mMockLauncherApps, mCarPackageManager,
                mMockPackageManager, mCarMediaManager);

        List<AppMetaData> appMetaData = launcherAppsInfo.getLaunchableComponentsList();

        assertEquals(3, appMetaData.size());

        injectApplicationEnabledSetting(COMPONENT_ENABLED_STATE_ENABLED);
        launchAllApps(appMetaData);

        verify(mMockPackageManager, never()).setApplicationEnabledSetting(
                eq(TEST_ENABLED_APP), anyInt(), eq(0));
        verify(mMockPackageManager, never()).getApplicationEnabledSetting(eq(TEST_ENABLED_APP));
    }

    private void mockPackageManagerQueries() {
        when(mMockPackageManager.queryIntentServices(any(), anyInt())).thenAnswer(args -> {
            Intent intent = args.getArgument(0);
            if (intent.getAction().equals(MediaBrowserService.SERVICE_INTERFACE)) {
                return Collections.singletonList(constructServiceResolveInfo(TEST_ENABLED_APP));
            }
            return new ArrayList<>();
        });

        when(mMockPackageManager.queryIntentActivities(any(), anyInt())).thenAnswer(args -> {
            Intent intent = args.getArgument(0);
            int flags = args.getArgument(1);
            List<ResolveInfo> resolveInfoList = new ArrayList<>();
            if (intent.getAction().equals(Intent.ACTION_MAIN)) {
                if ((flags & MATCH_DISABLED_UNTIL_USED_COMPONENTS) != 0) {
                    resolveInfoList.add(constructActivityResolveInfo(TEST_DISABLED_APP_1));
                    resolveInfoList.add(constructActivityResolveInfo(TEST_DISABLED_APP_2));
                }
                resolveInfoList.add(constructActivityResolveInfo(TEST_ENABLED_APP));
            }
            return resolveInfoList;
        });
    }

    private void injectApplicationEnabledSetting(int enabledState) {
        when(mMockPackageManager.getApplicationEnabledSetting(anyString()))
                .thenReturn(enabledState);
    }

    private void launchAllApps(List<AppMetaData> appMetaData) {
        for (AppMetaData meta : appMetaData) {
            Consumer<Context> launchCallback = meta.getLaunchCallback();
            launchCallback.accept(mMockContext);
        }
    }

    private static ResolveInfo constructActivityResolveInfo(String packageName) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = packageName;
        info.activityInfo.name = packageName + ".class";
        info.activityInfo.applicationInfo = new ApplicationInfo();
        return info;
    }

    private static ResolveInfo constructServiceResolveInfo(String packageName) {
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = new ServiceInfo();
        info.serviceInfo.packageName = packageName;
        info.serviceInfo.name = packageName + ".class";
        info.serviceInfo.applicationInfo = new ApplicationInfo();
        return info;
    }
}
