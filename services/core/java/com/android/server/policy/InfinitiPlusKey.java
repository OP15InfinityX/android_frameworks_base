/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0
 *
 * OnePlus Plus Key intercept helper for infiniti.
 */

package com.android.server.policy;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

public final class InfinitiPlusKey {
    private static final String TAG = "InfinitiPlusKey";

    public static final String ACTION_SHORT_PRESS = "com.oplus.pluskey.SHORT_PRESS";
    public static final String ACTION_LONG_PRESS = "com.oplus.pluskey.LONG_PRESS";
    public static final String ACTION_CAMERA_TRIGGER_DOWN =
            "com.oplus.pluskey.CAMERA_TRIGGER_DOWN";
    public static final String ACTION_CAMERA_TRIGGER_UP =
            "com.oplus.pluskey.CAMERA_TRIGGER_UP";

    private static final String[] SUPPORTED_DEVICES = {
            "infiniti",
            "OP60FFL1",
            "OP611FL1",
    };

    private static volatile Boolean sIsInfiniti;

    private InfinitiPlusKey() {
    }

    public static boolean isInfiniti() {
        Boolean cached = sIsInfiniti;
        if (cached != null) {
            return cached;
        }

        boolean supported =
                isSupportedDevice(SystemProperties.get("ro.lineage.device", ""))
                        || isSupportedDevice(SystemProperties.get("ro.evolution.device", ""))
                        || isSupportedDevice(SystemProperties.get("ro.product.device", ""))
                        || isSupportedDevice(SystemProperties.get("ro.product.vendor.device", ""))
                        || isSupportedDevice(SystemProperties.get("ro.vendor.product.device", ""));

        sIsInfiniti = supported;
        return supported;
    }

    private static boolean isSupportedDevice(String device) {
        for (String supportedDevice : SUPPORTED_DEVICES) {
            if (supportedDevice.equalsIgnoreCase(device)) {
                return true;
            }
        }
        return false;
    }

    public static void fireShortPress(Context context) {
        sendPlusKeyBroadcast(context, ACTION_SHORT_PRESS, "fireShortPress");
    }

    public static void fireLongPress(Context context) {
        sendPlusKeyBroadcast(context, ACTION_LONG_PRESS, "fireLongPress");
    }

    public static void fireCameraTriggerDown(Context context) {
        sendPlusKeyBroadcast(context, ACTION_CAMERA_TRIGGER_DOWN, "fireCameraTriggerDown");
    }

    public static void fireCameraTriggerUp(Context context) {
        sendPlusKeyBroadcast(context, ACTION_CAMERA_TRIGGER_UP, "fireCameraTriggerUp");
    }

    private static void sendPlusKeyBroadcast(Context context, String action, String logName) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage("com.oplus.pluskey");
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            Log.d(TAG, logName);
        } catch (Throwable t) {
            Log.w(TAG, logName + " failed", t);
        }
    }
}
