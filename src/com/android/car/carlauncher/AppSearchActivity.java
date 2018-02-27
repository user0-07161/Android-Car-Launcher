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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.car.widget.PagedListView;

/**
 * Activity that allows user to search in apps.
 */
public final class AppSearchActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_search_activity);
        findViewById(R.id.container).setOnTouchListener(
                (view, event) -> {
                    hideKeyboard();
                    return false;
                });
        findViewById(R.id.exit_button_container).setOnClickListener(view -> finish());

        ArrayList<AppMetaData> apps = getAppsList();

        PagedListView searchResultView = findViewById(R.id.search_result);
        searchResultView.setClipToOutline(true);
        SearchResultAdapter searchResultAdapter = new SearchResultAdapter(this, apps);
        searchResultView.setAdapter(searchResultAdapter);

        EditText searchBar = findViewById(R.id.app_search_bar);
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (TextUtils.isEmpty(s)) {
                    searchResultView.setVisibility(View.GONE);
                } else {
                    searchResultView.setVisibility(View.VISIBLE);
                    searchResultAdapter.getFilter().filter(s.toString());
                }
            }
        });
    }

    private ArrayList<AppMetaData> getAppsList() {
        ArrayList<AppMetaData> apps = new ArrayList<>();
        PackageManager manager = getPackageManager();

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> availableActivities = manager.queryIntentActivities(intent, 0);
        for (ResolveInfo info : availableActivities) {
            AppMetaData app =
                    new AppMetaData(
                            info.loadLabel(manager),
                            info.activityInfo.packageName,
                            info.activityInfo.loadIcon(manager));
            apps.add(app);
        }
        Collections.sort(apps);
        return apps;
    }

    public void hideKeyboard() {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(
                Activity.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }
}
