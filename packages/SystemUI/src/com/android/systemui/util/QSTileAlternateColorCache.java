/*
 * Copyright (C) 2026 Project Infinity X
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

package com.android.systemui.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

public class QSTileAlternateColorCache {
    private static volatile boolean sInitialized = false;
    private static volatile boolean sAlternateColorEnabled = true;
    private static ContentObserver sObserver;

    public static boolean isAlternateColorEnabled(Context context) {
        if (!sInitialized) {
            synchronized (QSTileAlternateColorCache.class) {
                if (!sInitialized) {
                    Context appContext = context.getApplicationContext();
                    final ContentResolver resolver = appContext.getContentResolver();
                    
                    // Read initial value
                    sAlternateColorEnabled = Settings.System.getInt(
                            resolver,
                            Settings.System.QS_TILE_ALTERNATE_COLOR,
                            1
                    ) == 1;
                    
                    // Register observer to keep it up to date
                    sObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sAlternateColorEnabled = Settings.System.getInt(
                                    resolver,
                                    Settings.System.QS_TILE_ALTERNATE_COLOR,
                                    1
                            ) == 1;
                        }
                    };
                    
                    try {
                        resolver.registerContentObserver(
                                Settings.System.getUriFor(Settings.System.QS_TILE_ALTERNATE_COLOR),
                                false,
                                sObserver
                        );
                    } catch (Exception e) {
                        // Fail-safe fallback in case of exceptions
                    }
                    
                    sInitialized = true;
                }
            }
        }
        return sAlternateColorEnabled;
    }
}
