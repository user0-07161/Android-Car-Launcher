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

import android.annotation.Nullable;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.content.pm.CarPackageManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Util class that contains helper method used by app launcher classes.
 */
class AppLauncherUtils {

    private static final String TAG = "AppLauncherUtils";

    private AppLauncherUtils() {
    }

    /**
     * Comparator for {@link AppMetaData} that sorts the list
     * by the "displayName" property in ascending order.
     */
    static final Comparator<AppMetaData> ALPHABETICAL_COMPARATOR = Comparator
            .comparing(AppMetaData::getDisplayName, String::compareToIgnoreCase);

    /**
     * Helper method that launches the app given the app's AppMetaData.
     *
     * @param app the requesting app's AppMetaData
     */
    static void launchApp(Context context, AppMetaData app) {
        context.startActivity(app.getMainLaunchIntent());
    }

    /**
     * Gets all the apps that we want to see in the launcher in unsorted order. Includes media
     * services without launcher activities.
     *
     * @param launcherApps      The {@link LauncherApps} system service
     * @param carPackageManager The {@link CarPackageManager} system service
     * @param packageManager    The {@link PackageManager} system service
     * @return a new map of all apps' metadata keyed by package name
     */
    @Nullable
    static Map<String, AppMetaData> getAllLauncherApps(
            LauncherApps launcherApps,
            CarPackageManager carPackageManager,
            PackageManager packageManager) {

        if (launcherApps == null || carPackageManager == null || packageManager == null) {
            return null;
        }

        List<ResolveInfo> mediaServices = packageManager.queryIntentServices(
                new Intent(MediaBrowserService.SERVICE_INTERFACE),
                PackageManager.GET_RESOLVED_FILTER);
        List<LauncherActivityInfo> availableActivities =
                launcherApps.getActivityList(null, Process.myUserHandle());

        Map<String, AppMetaData> apps = new HashMap<>(
                mediaServices.size() + availableActivities.size());

        // Process media services
        for (ResolveInfo info : mediaServices) {
            String packageName = info.serviceInfo.packageName;
            if (!apps.containsKey(packageName)) {
                final boolean isDistractionOptimized = true;

                Intent intent = new Intent(Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE);
                intent.putExtra(Car.CAR_EXTRA_MEDIA_PACKAGE, packageName);

                AppMetaData appMetaData = new AppMetaData(
                        info.serviceInfo.loadLabel(packageManager),
                        packageName,
                        info.serviceInfo.applicationInfo.loadIcon(packageManager),
                        isDistractionOptimized,
                        intent,
                        packageManager.getLaunchIntentForPackage(packageName));
                apps.put(packageName, appMetaData);
            }
        }

        // Process activities
        for (LauncherActivityInfo info : availableActivities) {
            String packageName = info.getComponentName().getPackageName();
            if (!apps.containsKey(packageName)) {
                boolean isDistractionOptimized =
                        isActivityDistractionOptimized(carPackageManager, packageName,
                                info.getName());

                AppMetaData appMetaData = new AppMetaData(
                        info.getLabel(),
                        packageName,
                        info.getApplicationInfo().loadIcon(packageManager),
                        isDistractionOptimized,
                        packageManager.getLaunchIntentForPackage(packageName),
                        null);
                apps.put(packageName, appMetaData);
            }
        }

        return apps;
    }

    /**
     * Gets if an activity is distraction optimized.
     *
     * @param carPackageManager The {@link CarPackageManager} system service
     * @param packageName       The package name of the app
     * @param activityName      The requested activity name
     * @return true if the supplied activity is distraction optimized
     */
    static boolean isActivityDistractionOptimized(
            CarPackageManager carPackageManager, String packageName, String activityName) {
        boolean isDistractionOptimized = false;
        // try getting distraction optimization info
        try {
            if (carPackageManager != null) {
                isDistractionOptimized =
                        carPackageManager.isActivityDistractionOptimized(packageName, activityName);
            }
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car not connected when getting DO info", e);
        }
        return isDistractionOptimized;
    }
}
