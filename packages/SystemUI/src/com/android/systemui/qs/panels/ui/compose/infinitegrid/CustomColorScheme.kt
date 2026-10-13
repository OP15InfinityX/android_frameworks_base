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
package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class CustomColorScheme(val qsTileColor: Color) {

    companion object {
        val current: CustomColorScheme
            @Composable
            @ReadOnlyComposable
            get() = LocalCustomColorScheme.current
    }
}

val LocalCustomColorScheme = staticCompositionLocalOf<CustomColorScheme> {
    CustomColorScheme(Color.Transparent)
}

object CustomColorSchemeCache {
    private var initialized = false
    private val _useAlternateColor = mutableStateOf(true)
    val useAlternateColor: State<Boolean> = _useAlternateColor

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver

            fun readVal() = try {
                Settings.System.getIntForUser(
                    resolver,
                    Settings.System.QS_TILE_ALTERNATE_COLOR,
                    1,
                    UserHandle.USER_CURRENT
                ) == 1
            } catch (_: Throwable) {
                true
            }

            _useAlternateColor.value = readVal()

            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    _useAlternateColor.value = readVal()
                }
            }

            try {
                resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QS_TILE_ALTERNATE_COLOR),
                    false,
                    observer,
                    UserHandle.USER_ALL
                )
            } catch (_: Throwable) {}
            initialized = true
        }
    }
}

@Composable
fun rememberCustomColorScheme(): CustomColorScheme {
    val context = LocalContext.current
    CustomColorSchemeCache.initialize(context)

    val useAlternateColor by CustomColorSchemeCache.useAlternateColor

    val tileColor = remember(useAlternateColor) {
        val colorRes = if (useAlternateColor) 
            com.android.internal.R.color.customColorSurfaceEffect2
        else 
            com.android.internal.R.color.customColorSurfaceEffect1
        
        val colorInt = context.resources.getColor(colorRes, context.theme)
        CustomColorScheme(Color(colorInt))
    }
    return tileColor
}
