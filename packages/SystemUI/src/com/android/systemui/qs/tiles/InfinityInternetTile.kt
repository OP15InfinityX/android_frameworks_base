/*
 * SPDX-FileCopyrightText: Project Infinity X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Button
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.AccessPointController
import javax.inject.Inject

class InfinityInternetTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val internetDialogManager: InternetDialogManager,
    private val accessPointController: AccessPointController,
    private val broadcastDispatcher: BroadcastDispatcher,
) : QSTileImpl<QSTile.BooleanState>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {

    companion object {
        const val TILE_SPEC = "infinity_internet"
    }

    private var isListening = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshState()
        }
    }

    private val mobileDataObserver: ContentObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshState()
        }
    }

    override fun newTileState(): QSTile.BooleanState {
        return QSTile.BooleanState().also {
            it.forceExpandIcon = true
        }
    }

    override fun isAvailable(): Boolean = true

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_internet_label)

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (isListening == listening) return
        isListening = listening
        if (listening) {
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
                addAction(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            broadcastDispatcher.registerReceiver(receiver, filter)
            try {
                mContext.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.MOBILE_DATA),
                    false,
                    mobileDataObserver,
                )
            } catch (_: Exception) {}
        } else {
            broadcastDispatcher.unregisterReceiver(receiver)
            try {
                mContext.contentResolver.unregisterContentObserver(mobileDataObserver)
            } catch (_: Exception) {}
        }
    }

    override fun handleClick(expandable: Expandable?) {
        mainHandler.post {
            internetDialogManager.create(
                aboveStatusBar = true,
                canConfigMobileData = accessPointController.canConfigMobileData(),
                canConfigWifi = accessPointController.canConfigWifi(),
                expandable = expandable,
            )
        }
    }

    override fun handleSecondaryClick(expandable: Expandable?) {
        toggleInternetState()
    }

    private fun toggleInternetState() {
        val wifiManager = mContext.getSystemService(WifiManager::class.java)
        val wifiEnabled = wifiManager?.isWifiEnabled == true

        if (wifiEnabled) {
            try {
                @Suppress("DEPRECATION")
                wifiManager?.setWifiEnabled(false)
            } catch (_: Exception) {}
        } else {
            val baseTelephony = mContext.getSystemService(TelephonyManager::class.java)
            val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            val telephony = if (SubscriptionManager.isValidSubscriptionId(defaultDataSubId)) {
                try { baseTelephony?.createForSubscriptionId(defaultDataSubId) } catch (_: Exception) { null }
            } else {
                baseTelephony
            }
            val mobileDataEnabled = try { telephony?.isDataEnabled == true } catch (_: Exception) { false }
            val target = !mobileDataEnabled
            try {
                telephony?.setDataEnabledForReason(TelephonyManager.DATA_ENABLED_REASON_USER, target)
            } catch (_: Exception) {}
        }
        refreshState()
    }

    override fun getLongClickIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS)
    }

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        val wifiManager = mContext.getSystemService(WifiManager::class.java)
        val baseTelephony = mContext.getSystemService(TelephonyManager::class.java)
        val subscriptionManager = SubscriptionManager.from(mContext)

        val wifiEnabled = wifiManager?.isWifiEnabled == true
        val wifiInfo = try { wifiManager?.connectionInfo } catch (_: Exception) { null }
        val wifiSsid = sanitizeSsid(wifiInfo?.ssid)
        val wifiConnected = wifiEnabled && !wifiSsid.isNullOrBlank()

        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val hasValidSub = SubscriptionManager.isValidSubscriptionId(defaultDataSubId)
        val telephony = if (hasValidSub) {
            try { baseTelephony?.createForSubscriptionId(defaultDataSubId) } catch (_: Exception) { null }
        } else {
            baseTelephony
        }
        val mobileDataEnabled = try { telephony?.isDataEnabled == true } catch (_: Exception) { false }

        val carrierName = if (hasValidSub) {
            try {
                subscriptionManager.getActiveSubscriptionInfo(defaultDataSubId)?.carrierName?.toString()
            } catch (_: Exception) { null }
        } else null ?: try {
            telephony?.simOperatorName?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null } ?: mContext.getString(R.string.mobile_data_settings_title)

        state.label = mContext.getString(R.string.quick_settings_internet_label)
        state.expandedAccessibilityClassName = Button::class.java.name
        state.handlesSecondaryClick = true
        state.handlesLongClick = true

        if (wifiConnected) {
            state.value = true
            state.state = Tile.STATE_ACTIVE
            state.icon = ResourceIcon.get(R.drawable.ic_wifi_24)
            state.secondaryLabel = wifiSsid
        } else if (wifiEnabled) {
            state.value = true
            state.state = Tile.STATE_ACTIVE
            state.icon = ResourceIcon.get(R.drawable.ic_wifi_24)
            state.secondaryLabel = mContext.getString(R.string.switch_bar_on)
        } else if (mobileDataEnabled) {
            state.value = true
            state.state = Tile.STATE_ACTIVE
            state.icon = ResourceIcon.get(R.drawable.ic_signal_cellular_alt_24)
            state.secondaryLabel = carrierName
        } else {
            state.value = false
            state.state = Tile.STATE_INACTIVE
            state.icon = ResourceIcon.get(R.drawable.ic_mobiledata_off_24)
            state.secondaryLabel = mContext.getString(R.string.switch_bar_off)
        }
    }

    private fun sanitizeSsid(ssid: String?): String? {
        if (ssid.isNullOrBlank()) return null
        if (ssid == WifiManager.UNKNOWN_SSID) return null
        val trimmed = ssid.trim().trim('"')
        return if (trimmed.isNotBlank() && trimmed != WifiManager.UNKNOWN_SSID) trimmed else null
    }
}
