/*
 * Copyright (C) 2025-2026 AxionOS Project
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
package com.android.server.wm;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import lineageos.health.HealthInterface;

import com.android.internal.app.IGameSpaceCallback;

import java.io.File;
import java.util.List;

class GameStateDispatcher {

    private static final String TAG = "GameStateDispatcher";
    private static final String KEY_GAMING_MODE_ACTIVE = "ax_gaming_mode_active";
    private static final String KEY_BYPASS_CHARGE_ENABLED = "bypass_charge_enabled";
    public static final String KEY_BYPASS_CHARGE_ACTIVE = "bypass_charge_active";
    private static final String KEY_BYPASS_SAVED_ENABLED = "bypass_charge_saved_enabled";
    private static final String KEY_BYPASS_SAVED_MODE = "bypass_charge_saved_mode";
    private static final String KEY_BYPASS_SAVED_LIMIT = "bypass_charge_saved_limit";
    private static final String KEY_POWER_MODE_PERF = "persist.sys.power_mode_perf";
    private static final String KEY_POWER_MODE_PERF_BY_USER = "persist.sys.power_mode_perf_by_user";
    private static final String USB_CURRENT_NOW = "/sys/class/power_supply/usb/current_now";
    private static final String USB_VOLTAGE_NOW = "/sys/class/power_supply/usb/voltage_now";

    private final Context mContext;
    private final List<IGameSpaceCallback> mCallbacks;

    private boolean mGameBypassRequested;
    private boolean mManualBypassRequested;
    private boolean mBypassActive;

    GameStateDispatcher(Context context, List<IGameSpaceCallback> callbacks) {
        mContext = context;
        mCallbacks = callbacks;

        mBypassActive = Settings.Global.getInt(mContext.getContentResolver(),
                KEY_BYPASS_CHARGE_ACTIVE, 0) == 1;
        if (mBypassActive) {
            restoreBypassState();
        }

        IntentFilter powerFilter = new IntentFilter();
        powerFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        powerFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                    mManualBypassRequested = false;
                }
                updateBypassState();
            }
        }, powerFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    void dispatchGameState(boolean active, String packageName) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                KEY_GAMING_MODE_ACTIVE, active ? 1 : 0, UserHandle.USER_CURRENT);

        for (IGameSpaceCallback callback : mCallbacks) {
            try {
                if (active && packageName != null) {
                    callback.onGameStart(packageName);
                } else {
                    callback.onGameLeave();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Removing dead callback", e);
                mCallbacks.remove(callback);
            }
        }

        mGameBypassRequested = active && bypassChargeEnabled();
        updateBypassState();
    }

    void boostGame(boolean enable) {
        int perfByUser = Settings.System.getIntForUser(
                mContext.getContentResolver(), KEY_POWER_MODE_PERF_BY_USER, 0,
                UserHandle.USER_CURRENT);
        if (perfByUser == 1) return;

        Settings.System.putIntForUser(mContext.getContentResolver(),
                KEY_POWER_MODE_PERF, enable ? 1 : 0,
                UserHandle.USER_CURRENT);
        SystemProperties.set(KEY_POWER_MODE_PERF, enable ? "1" : "0");
    }

    synchronized void setBypassCharge(boolean enable) {
        Slog.i(TAG, "Manual charging bypass " + (enable ? "requested" : "disabled"));
        mManualBypassRequested = enable;
        if (!enable) {
            mGameBypassRequested = false;
        }
        updateBypassState();
    }

    synchronized boolean isBypassChargeActive() {
        return mBypassActive;
    }

    synchronized long getBypassChargePowerMicrowatts() {
        if (!mBypassActive) return 0;

        long currentMicroamps = readLong(USB_CURRENT_NOW);
        long voltageMicrovolts = readLong(USB_VOLTAGE_NOW);
        if (currentMicroamps <= 0 || voltageMicrovolts <= 0) return 0;

        return currentMicroamps * voltageMicrovolts / 1_000_000L;
    }

    private long readLong(String path) {
        try {
            return Long.parseLong(FileUtils.readTextFile(new File(path), 64, null).trim());
        } catch (Exception e) {
            Slog.w(TAG, "Failed to read " + path, e);
            return 0;
        }
    }

    private boolean bypassChargeEnabled() {
        return SystemProperties.getBoolean("persist.sys.battery_bypass_supported", false)
                && Settings.System.getIntForUser(mContext.getContentResolver(),
                KEY_BYPASS_CHARGE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
    }

    private synchronized void updateBypassState() {
        boolean powerConnected = isPowerConnected();
        boolean shouldEnable = powerConnected
                && (mGameBypassRequested || mManualBypassRequested);
        if (shouldEnable == mBypassActive) return;

        if (shouldEnable) {
            enableBypass();
        } else {
            restoreBypassState();
        }
    }

    private boolean isPowerConnected() {
        Intent battery = mContext.registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private void enableBypass() {
        int level = battLevel();
        if (level < 0) return;

        HealthInterface health = null;
        boolean savedEnabled = false;
        int savedMode = HealthInterface.MODE_LIMIT;
        int savedLimit = 100;
        boolean savedStateRead = false;
        try {
            health = HealthInterface.getInstance(mContext);
            savedEnabled = health.getEnabled();
            savedMode = health.getMode();
            savedLimit = health.getLimit();
            savedStateRead = true;

            Settings.Global.putInt(mContext.getContentResolver(),
                    KEY_BYPASS_SAVED_ENABLED, savedEnabled ? 1 : 0);
            Settings.Global.putInt(mContext.getContentResolver(),
                    KEY_BYPASS_SAVED_MODE, savedMode);
            Settings.Global.putInt(mContext.getContentResolver(),
                    KEY_BYPASS_SAVED_LIMIT, savedLimit);

            boolean success = health.setEnabled(false);
            success &= health.setMode(HealthInterface.MODE_LIMIT);
            success &= health.setLimit(level);
            Settings.Global.putInt(mContext.getContentResolver(),
                    KEY_BYPASS_CHARGE_ACTIVE, 1);
            success &= health.setEnabled(true);
            if (!success) {
                Slog.w(TAG, "Failed to enable charging bypass");
                Settings.Global.putInt(mContext.getContentResolver(),
                        KEY_BYPASS_CHARGE_ACTIVE, 0);
                restoreChargingControl(health, savedEnabled, savedMode, savedLimit);
                return;
            }

            mBypassActive = true;
            Slog.i(TAG, "Charging bypass enabled at " + level + "%");
        } catch (Exception e) {
            Slog.w(TAG, "Failed to enable charging bypass", e);
            Settings.Global.putInt(mContext.getContentResolver(),
                    KEY_BYPASS_CHARGE_ACTIVE, 0);
            if (health != null && savedStateRead) {
                try {
                    restoreChargingControl(health, savedEnabled, savedMode, savedLimit);
                } catch (Exception restoreError) {
                    Slog.w(TAG, "Failed to roll back charging control", restoreError);
                }
            }
        }
    }

    private int battLevel() {
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
    }

    private void restoreBypassState() {
        if (!mBypassActive) return;

        try {
            HealthInterface health = HealthInterface.getInstance(mContext);
            boolean savedEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                    KEY_BYPASS_SAVED_ENABLED, 0) == 1;
            int savedMode = Settings.Global.getInt(mContext.getContentResolver(),
                    KEY_BYPASS_SAVED_MODE, HealthInterface.MODE_LIMIT);
            int savedLimit = Settings.Global.getInt(mContext.getContentResolver(),
                    KEY_BYPASS_SAVED_LIMIT, 100);
            Settings.Global.putInt(mContext.getContentResolver(),
                    KEY_BYPASS_CHARGE_ACTIVE, 0);
            restoreChargingControl(health, savedEnabled, savedMode, savedLimit);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to restore charging control", e);
            Settings.Global.putInt(mContext.getContentResolver(),
                    KEY_BYPASS_CHARGE_ACTIVE, 1);
            return;
        }

        mBypassActive = false;
        Slog.i(TAG, "Charging bypass disabled and charging control restored");
    }

    private void restoreChargingControl(HealthInterface health, boolean enabled,
            int mode, int limit) {
        health.setEnabled(false);
        health.setMode(mode);
        health.setLimit(limit);
        health.setEnabled(enabled);
    }
}
