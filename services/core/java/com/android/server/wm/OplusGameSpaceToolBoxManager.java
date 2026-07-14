/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.server.wm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerExemptionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.view.Display;

import android.app.ActivityManagerInternal;

import com.android.server.LocalServices;

/**
 * Minimal stock-compatible bridge for OnePlus Game Space.
 *
 * Stock Oplus services notify com.oplus.games when game mode starts and call into
 * the toolbox manager for edge gestures. We do the same with the exported services
 * available in the OP15 Game Space APK.
 */
final class OplusGameSpaceToolBoxManager {
    private static final String TAG = "OplusGameSpaceToolBoxManager";

    private static final String GAMES_PACKAGE = "com.oplus.games";
    private static final String GAME_EVENT_SERVICE = "com.oplus.games.service.GameEventService";
    private static final String GAME_DOCK_SERVICE = "com.oplus.games.gamedock.GameDockService";
    private static final String FLOAT_WINDOW_SERVICE =
            "com.coloros.gamespaceui.module.floatwindow.FloatWindowManagerService";

    private static final String ACTION_GAME_START = "oplus.intent.game.GAME_START";
    private static final String ACTION_GAME_STOP = "oplus.intent.game.GAME_STOP";
    private static final String ACTION_DOCK_INIT = "NEW_ARCH_SERVICE_INIT";
    private static final String ACTION_FLOAT_MANAGER = "oppo.intent.action.GAME_FLOAT_MANAGER";

    private static final String KEY_GAME_ASSISTANT_SWITCH = "oplus_games_game_assistant_switch_key";
    private static final String KEY_GAMECENTER_DOCK_SWITCH = "is_gamecenter_control_gamedock_swith";
    private static final String KEY_EDGE_PANEL_SWITCH = "show_gamespace_edge_panel";
    private static final String KEY_GAME_OVERLAY = "game_overlay";
    private static final String KEY_GAME_MODE_STATUS = "game_mode_status";
    private static final String KEY_GAME_LIST = "gamespace_game_list";
    private static final String KEY_DENIED_LIST = "gamespace_denied_list";

    private static final int TOOLBOX_EDGE_WIDTH_DP = 48;
    private static final long FLOAT_SERVICE_ALLOWLIST_DURATION_MS = 10_000;

    private static final OplusGameSpaceToolBoxManager sInstance =
            new OplusGameSpaceToolBoxManager();

    private String mCurrentGamePackage;
    private long mOrder = 10;

    static OplusGameSpaceToolBoxManager getInstance() {
        return sInstance;
    }

    private OplusGameSpaceToolBoxManager() {
    }

    void onActivityLaunched(Context context, Handler handler, String callingPackage,
            String targetPackage) {
        if (context == null || TextUtils.isEmpty(targetPackage)) {
            return;
        }
        if (GAMES_PACKAGE.equals(targetPackage)) {
            return;
        }
        final boolean launchedFromGameSpace = GAMES_PACKAGE.equals(callingPackage);
        if (launchedFromGameSpace || isKnownGamePackage(context, targetPackage)) {
            enterGame(context, handler, targetPackage);
        } else if (!TextUtils.isEmpty(mCurrentGamePackage)
                && !mCurrentGamePackage.equals(targetPackage)) {
            exitGame(context, handler, mCurrentGamePackage);
        }
    }

    boolean gameModeShowToolBox(Context context, Handler handler, Display display, int x, int y,
            String topPackage) {
        if (context == null || display == null || !isAssistantEnabled(context)) {
            return false;
        }
        final String gamePackage = !TextUtils.isEmpty(mCurrentGamePackage)
                ? mCurrentGamePackage : topPackage;
        if (TextUtils.isEmpty(gamePackage) || GAMES_PACKAGE.equals(gamePackage)) {
            return false;
        }
        if (!isWithinGameModeToolBoxRegion(context, display, x)) {
            return false;
        }

        enterGame(context, handler, gamePackage);
        startFloatWindowManager(context, handler, gamePackage);
        return true;
    }

    private boolean isWithinGameModeToolBoxRegion(Context context, Display display, int x) {
        final int edgeWidth = (int) (context.getResources().getDisplayMetrics().density
                * TOOLBOX_EDGE_WIDTH_DP);
        return x <= edgeWidth || x >= display.getWidth() - edgeWidth;
    }

    private boolean isAssistantEnabled(Context context) {
        return getSecureInt(context, KEY_GAME_ASSISTANT_SWITCH, 1) == 1
                && getSecureInt(context, KEY_GAMECENTER_DOCK_SWITCH, 1) == 1
                && getSecureInt(context, KEY_EDGE_PANEL_SWITCH, 1) == 1;
    }

    private boolean isKnownGamePackage(Context context, String packageName) {
        if (isDelimitedSettingContains(getSecureString(context, KEY_GAME_OVERLAY), packageName)) {
            return true;
        }
        if (isDelimitedSettingContains(getSystemString(context, KEY_DENIED_LIST), packageName)) {
            return false;
        }
        if ("1".equals(getSystemString(context, KEY_GAME_MODE_STATUS))) {
            return true;
        }
        return isDelimitedSettingContains(getSystemString(context, KEY_GAME_LIST), packageName);
    }

    private boolean isDelimitedSettingContains(String value, String packageName) {
        if (TextUtils.isEmpty(value) || TextUtils.isEmpty(packageName)) {
            return false;
        }
        final String[] parts = value.split("[,;]");
        for (String part : parts) {
            if (packageName.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private void enterGame(Context context, Handler handler, String packageName) {
        if (packageName.equals(mCurrentGamePackage)
                && "1".equals(getSystemString(context, KEY_GAME_MODE_STATUS))) {
            return;
        }
        if (!TextUtils.isEmpty(mCurrentGamePackage)) {
            exitGame(context, handler, mCurrentGamePackage);
        }
        mCurrentGamePackage = packageName;
        putSystemString(context, KEY_GAME_MODE_STATUS, "1");
        startGameEventService(context, handler, ACTION_GAME_START, packageName);
        startGameDockService(context, handler, packageName);
    }

    private void exitGame(Context context, Handler handler, String packageName) {
        startGameEventService(context, handler, ACTION_GAME_STOP, packageName);
        putSystemString(context, KEY_GAME_MODE_STATUS, "0");
        if (packageName.equals(mCurrentGamePackage)) {
            mCurrentGamePackage = null;
        }
    }

    private void startGameEventService(Context context, Handler handler, String action,
            String packageName) {
        final Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(GAMES_PACKAGE, GAME_EVENT_SERVICE));
        intent.putExtra("game_pkg_name", packageName);
        intent.putExtra("is_cold_start", ACTION_GAME_START.equals(action));
        startService(context, handler, intent);
    }

    private void startGameDockService(Context context, Handler handler, String packageName) {
        final Intent intent = new Intent(ACTION_DOCK_INIT);
        intent.setComponent(new ComponentName(GAMES_PACKAGE, GAME_DOCK_SERVICE));
        intent.putExtra("game_pkg_name", packageName);
        intent.putExtra("state", 1);
        intent.putExtra("assistant_games_switch_order", ++mOrder);
        startForegroundService(context, handler, intent);
    }

    private void startFloatWindowManager(Context context, Handler handler, String packageName) {
        final Intent intent = new Intent(ACTION_FLOAT_MANAGER);
        intent.setComponent(new ComponentName(GAMES_PACKAGE, FLOAT_WINDOW_SERVICE));
        intent.putExtra("action_name", ACTION_FLOAT_MANAGER);
        intent.putExtra("game_pkg_name", packageName);
        intent.putExtra("game_package_name", packageName);
        intent.putExtra("package_name", packageName);
        intent.putExtra("fast_start_pkg", packageName);
        intent.putExtra("float_type", "float");
        tempAllowlistGamesUid(context);
        startService(context, handler, intent);
    }

    private void tempAllowlistGamesUid(Context context) {
        try {
            final int uid = context.getPackageManager().getPackageUidAsUser(GAMES_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0), UserHandle.USER_SYSTEM);
            final ActivityManagerInternal amInternal =
                    LocalServices.getService(ActivityManagerInternal.class);
            if (amInternal != null) {
                amInternal.updateDeviceIdleTempAllowlist(null, uid, true,
                        FLOAT_SERVICE_ALLOWLIST_DURATION_MS,
                        PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                        PowerExemptionManager.REASON_SERVICE_LAUNCH,
                        "oplus-game-space-float", Process.SYSTEM_UID);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to temp allowlist Game Space", e);
        }
    }

    private void startService(Context context, Handler handler, Intent intent) {
        handler.post(() -> {
            try {
                context.startServiceAsUser(intent, UserHandle.CURRENT);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to start service " + intent, e);
            }
        });
    }

    private void startForegroundService(Context context, Handler handler, Intent intent) {
        handler.post(() -> {
            try {
                context.startForegroundServiceAsUser(intent, UserHandle.CURRENT);
            } catch (IllegalStateException e) {
                try {
                    context.startServiceAsUser(intent, UserHandle.CURRENT);
                } catch (Exception startException) {
                    Slog.w(TAG, "Failed to start service " + intent, startException);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to start service " + intent, e);
            }
        });
    }

    private int getSecureInt(Context context, String name, int def) {
        return Settings.Secure.getIntForUser(context.getContentResolver(), name, def,
                UserHandle.USER_CURRENT);
    }

    private String getSecureString(Context context, String name) {
        try {
            return Settings.Secure.getStringForUser(context.getContentResolver(), name,
                    UserHandle.USER_CURRENT);
        } catch (Exception e) {
            return null;
        }
    }

    private String getSystemString(Context context, String name) {
        try {
            return Settings.System.getStringForUser(context.getContentResolver(), name,
                    UserHandle.USER_CURRENT);
        } catch (Exception e) {
            return null;
        }
    }

    private void putSystemString(Context context, String name, String value) {
        final long token = Binder.clearCallingIdentity();
        try {
            Settings.System.putStringForUser(context.getContentResolver(), name, value,
                    UserHandle.USER_CURRENT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
