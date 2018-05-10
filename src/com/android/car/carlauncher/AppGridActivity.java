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
import android.app.Activity;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.GridLayoutManager.SpanSizeLookup;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import androidx.car.widget.PagedListView;

/**
 * Launcher activity that shows a grid of apps.
 */

public final class AppGridActivity extends Activity {

    private static final String TAG = "AppGridActivity";

    private static AppInstallUninstallReceiver mReceiver;

    private int mColumnNumber;
    private AppGridAdapter mGridAdapter;
    private PackageManager mPackageManager;
    private UsageStatsManager mUsageStatsManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mColumnNumber = getResources().getInteger(R.integer.car_app_selector_column_number);
        mPackageManager = getPackageManager();
        mUsageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

        setContentView(R.layout.app_grid_activity);

        findViewById(R.id.exit_button_container).setOnClickListener(v -> finish());

        findViewById(R.id.search_button_container).setOnClickListener((View view) -> {
            Intent intent = new Intent(this, AppSearchActivity.class);
            startActivity(intent);
        });

        mGridAdapter = new AppGridAdapter(this);

        PagedListView gridView = findViewById(R.id.apps_grid);

        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, mColumnNumber);
        gridLayoutManager.setSpanSizeLookup(new SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mGridAdapter.getSpanSizeLookup(position);
            }
        });
        gridView.getRecyclerView().setLayoutManager(gridLayoutManager);

        gridView.setAdapter(mGridAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mGridAdapter.updateAllApps(getAllApps());
        // using onResume() to refresh most recently used apps because we want to refresh even if
        // the app being launched crashes/doesn't cover the entire screen.
        mGridAdapter.updateMostRecentApps(getMostRecentApps());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // register broadcast receiver for package installation and uninstallation
        mReceiver = new AppInstallUninstallReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        getApplicationContext().registerReceiver(mReceiver, filter);
    }

    @Override
    protected void onStop() {
        super.onPause();
        if (mReceiver != null) {
            getApplicationContext().unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    private List<AppMetaData> getMostRecentApps() {
        ArrayList<AppMetaData> apps = new ArrayList<>();

        // get the usage stats starting from 1 year ago with a INTERVAL_YEARLY granularity
        // returning entries like:
        // "During 2017 App A is last used at 2017/12/15 18:03"
        // "During 2017 App B is last used at 2017/6/15 10:00"
        // "During 2018 App A is last used at 2018/1/1 15:12"
        List<UsageStats> stats =
            mUsageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_YEARLY,
                System.currentTimeMillis() - DateUtils.YEAR_IN_MILLIS,
                System.currentTimeMillis());

        if (stats == null || stats.size() == 0) {
            return apps; // empty list
        }

        Collections.sort(stats, new LastTimeUsedComparator());

        int currentIndex = 0;
        int itemsAdded = 0;
        int statsSize = stats.size();
        int itemCount = Math.min(mColumnNumber, statsSize);
        while (itemsAdded < itemCount && currentIndex < statsSize) {
            String packageName = stats.get(currentIndex).getPackageName();
            currentIndex++;

            // do not include self
            if (packageName.equals(getPackageName())) {
                continue;
            }

            // do not include apps that don't support starting from launcher
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                continue;
            }

            try {
                // try getting application info from package name
                ApplicationInfo info = mPackageManager.getApplicationInfo(packageName, 0);
                Drawable icon = mPackageManager.getApplicationIcon(info);
                String displayName = mPackageManager.getApplicationLabel(info).toString();
                if (icon == null || TextUtils.isEmpty(displayName)) {
                    continue;
                }
                AppMetaData app = new AppMetaData(displayName, packageName, icon);

                // edge case: do not include duplicated entries
                // e.g. app is used at 2017/12/31 23:59, and 2018/01/01 00:00
                if (apps.contains(app)) {
                    continue;
                }

                apps.add(app);
                itemsAdded++;
            } catch (PackageManager.NameNotFoundException e) {
                // this should never happen
                Log.e(TAG, "NameNotFoundException when getting app icon in AppGridActivity");
            }
        }
        return apps;
    }

    /**
     * Comparator for {@link UsageStats} that sorts the list by the "last time used" property
     * in descending order.
     */
    private static class LastTimeUsedComparator implements Comparator<UsageStats> {
        @Override
        public int compare(UsageStats stat1, UsageStats stat2) {
            Long time1 = stat1.getLastTimeUsed();
            Long time2 = stat2.getLastTimeUsed();
            return time2.compareTo(time1);
        }
    }

    private List<AppMetaData> getAllApps() {
        List<AppMetaData> apps = new ArrayList<>();

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> availableActivities = mPackageManager.queryIntentActivities(intent, 0);
        for (ResolveInfo info : availableActivities) {
            AppMetaData app =
                new AppMetaData(
                    info.loadLabel(mPackageManager),
                    info.activityInfo.packageName,
                    info.activityInfo.loadIcon(mPackageManager));
            apps.add(app);
        }
        Collections.sort(apps);
        return apps;
    }

    public class AppInstallUninstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String packageName = intent.getData().getSchemeSpecificPart();

            if (packageName == null || packageName.length() == 0) {
                // they sent us a bad intent
                return;
            }

            mGridAdapter.updateAllApps(getAllApps());
        }
    }
}
