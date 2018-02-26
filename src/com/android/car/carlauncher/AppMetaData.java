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
import android.graphics.drawable.Drawable;

import java.util.Comparator;

/**
 * Meta data of an app including the display name, the full package name, and the icon drawable.
 */

final class AppMetaData implements Comparable<AppMetaData> {
    // The display name of the app
    @Nullable
    private String mDisplayName;
    // The package name of the app
    private String mPackageName;
    private Drawable mIcon;

    private static Comparator<String> nullSafeStringComparator = Comparator
        .nullsFirst(String::compareToIgnoreCase);

    /**
     * Comparator for {@link AppMetaData} that sorts the list by the "displayName" property
     * in ascending order.
     */
    private static Comparator<AppMetaData> metadataComparator = Comparator
        .comparing(AppMetaData::getDisplayName, nullSafeStringComparator);

    public AppMetaData(CharSequence displayName, String packageName, Drawable icon) {
        mDisplayName = displayName == null ? "" : displayName.toString();
        mPackageName = packageName == null ? "" : packageName;
        mIcon = icon;
    }

    public String getDisplayName() {
        return mDisplayName;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public Drawable getIcon() {
        return mIcon;
    }

    @Override
    public int compareTo(AppMetaData that) {
        return metadataComparator.compare(this, that);
    }

    /**
     * The equality of two AppMetaData is determined by whether the package names are the same.
     *
     * @param o Object that this AppMetaData object is compared against
     * @return {@code true} when two AppMetaData have the same package name
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AppMetaData)) {
            return false;
        } else {
            return ((AppMetaData) o).getPackageName().equals(mPackageName);
        }
    }

    @Override
    public int hashCode() {
        return mPackageName.hashCode();
    }
}
