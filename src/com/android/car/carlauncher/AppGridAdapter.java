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
import android.content.Intent;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

/**
 * The adapter that populates the grid view with apps.
 */

final class AppGridAdapter extends RecyclerView.Adapter<AppGridAdapter.ViewHolder> {
    private final Activity mContext;
    private final List<AppMetaData> mApps;
    private final int mColumnNumber;

    private List<AppMetaData> mMostRecentApps;

    AppGridAdapter(
        Activity context,
        List<AppMetaData> apps,
        int columnNumber) {
        mContext = context;
        mApps = apps;
        mColumnNumber = columnNumber;
    }

    void updateMostRecentApps(List<AppMetaData> mostRecentApps) {
        mMostRecentApps = mostRecentApps;
        notifyDataSetChanged();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public View mAppItem;
        public ImageView mAppIconView;
        public TextView mAppNameView;

        public ViewHolder(View view) {
            super(view);
            mAppItem = view.findViewById(R.id.app_item);
            mAppIconView = mAppItem.findViewById(R.id.app_icon);
            mAppNameView = mAppItem.findViewById(R.id.app_name);
        }

        public void bind(AppMetaData app, View.OnClickListener listener) {
            if (app == null) {
                // Empty out the view
                mAppItem.setClickable(false);
                mAppItem.setOnClickListener(null);
                mAppIconView.setBackground(null);
                mAppNameView.setText(null);
            } else {
                mAppItem.setClickable(true);
                mAppItem.setOnClickListener(listener);
                mAppIconView.setBackground(app.getIcon());
                mAppNameView.setText(app.getDisplayName());
            }
        }
    }

    @Override
    public AppGridAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mContext.getLayoutInflater().inflate(R.layout.grid_item, null);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        AppMetaData app;
        if (mMostRecentApps == null || mMostRecentApps.size() == 0) {
            // there are no most recent apps
            app = mApps.get(position);
        } else {
            // there are some recent apps
            if (position < mMostRecentApps.size()) {
                // bind the most recent apps
                app = mMostRecentApps.get(position);
            } else if (position < mColumnNumber) {
                // bind the empty space of the first row
                app = null;
            } else {
                // bind the grid of apps
                app = mApps.get(position - mColumnNumber);
            }
        }
        View.OnClickListener onClickListener = getOnClickListener(app);
        holder.bind(app, onClickListener);
    }

    /**
     * Helper method to get the OnClickListener that launches the app given the app's AppMetaData.
     */
    private View.OnClickListener getOnClickListener(@Nullable AppMetaData app) {
        if (app == null) {
            return null;
        }

        Intent intent =
            mContext.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        return v -> mContext.startActivity(intent);
    }

    @Override
    public int getItemCount() {
        // If there are any most recently launched apps, add a first row (can be half filled)
        boolean hasRecentApps = mMostRecentApps != null && mMostRecentApps.size() > 0;
        return mApps.size() + (hasRecentApps ? mColumnNumber : 0);
    }
}