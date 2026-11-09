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

package com.android.systemui.volume

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.VolumeDialog
import com.android.systemui.volume.dialog.VolumeDialogPlugin
import com.android.systemui.axion.volume.AxionVolumeDialogPlugin
import lineageos.providers.LineageSettings
import javax.inject.Inject
import dagger.Lazy

@SysUISingleton
class VolumeDialogDelegator @Inject constructor(
    private val context: Context,
    private val stockVolumeDialog: Lazy<VolumeDialogPlugin>,
    private val axionVolumeDialog: Lazy<AxionVolumeDialogPlugin>
) : VolumeDialog {

    private var activeDialog: VolumeDialog? = null
    private var windowType: Int = 0
    private var callback: VolumeDialog.Callback? = null
    private var isUsingAxion: Boolean = true

    private val handler = Handler(Looper.getMainLooper())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val useAxion = getSettingValue()
            if (useAxion != isUsingAxion) {
                switchDialog(useAxion)
            }
        }
    }

    private fun getSettingValue(): Boolean {
        return Settings.Secure.getIntForUser(
            context.contentResolver,
            "use_axion_volume_dialog",
            1,
            UserHandle.USER_CURRENT
        ) == 1
    }

    override fun init(windowType: Int, callback: VolumeDialog.Callback?) {
        this.windowType = windowType
        this.callback = callback

        // Start observing the setting
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("use_axion_volume_dialog"),
            false,
            observer,
            UserHandle.USER_ALL
        )

        isUsingAxion = getSettingValue()
        val dialog = if (isUsingAxion) axionVolumeDialog.get() else stockVolumeDialog.get()
        activeDialog = dialog
        dialog.init(windowType, callback)
    }

    override fun destroy() {
        context.contentResolver.unregisterContentObserver(observer)
        activeDialog?.destroy()
        activeDialog = null
    }

    private fun switchDialog(useAxion: Boolean) {
        activeDialog?.destroy()
        isUsingAxion = useAxion
        val nextDialog = if (isUsingAxion) axionVolumeDialog.get() else stockVolumeDialog.get()
        activeDialog = nextDialog
        nextDialog.init(windowType, callback)
    }
}
