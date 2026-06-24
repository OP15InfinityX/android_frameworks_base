/*
 * Copyright (C) 2026 The Infinity-X Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package android.app;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility wrapper for Settings AppLock UI.
 *
 * @hide
 */
@SystemService(Context.APP_LOCK_SERVICE)
public class AppLockManager {

    /** @hide */
    public static final String EXTRA_PACKAGE_LABEL = "app_label";
    /** @hide */
    public static final String EXTRA_ALLOW_BIOMETRICS = "allow_biometrics";
    /** @hide */
    public static final boolean DEFAULT_BIOMETRICS_ALLOWED = true;
    /** @hide */
    public static final boolean DEFAULT_HIDE_IN_LAUNCHER = false;
    /** @hide */
    public static final boolean DEFAULT_REDACT_NOTIFICATION = false;

    private static final String SETTING_BIOMETRICS_ALLOWED = "app_lock_biometrics_allowed";

    private final Context mContext;
    private final AxSandboxManager mAxSandboxManager;

    /** @hide */
    public AppLockManager(@NonNull Context context, @NonNull AxSandboxManager axSandboxManager) {
        mContext = context;
        mAxSandboxManager = axSandboxManager;
    }

    /** @hide */
    @NonNull
    public List<PackageData> getPackageData() {
        List<PackageData> data = new ArrayList<>();
        List<String> lockedPackages = mAxSandboxManager.getLockedPackages();
        for (String packageName : mAxSandboxManager.getLockablePackages()) {
            data.add(new PackageData(packageName, lockedPackages.contains(packageName), false));
        }
        for (String packageName : lockedPackages) {
            if (!containsPackage(data, packageName)) {
                data.add(new PackageData(packageName, true, false));
            }
        }
        return data;
    }

    /** @hide */
    @NonNull
    public List<String> getHiddenPackages() {
        return mAxSandboxManager.getHiddenPackages();
    }

    /** @hide */
    public void setShouldProtectApp(@NonNull String packageName, boolean protect) {
        if (protect) {
            mAxSandboxManager.addLockedApp(packageName);
        } else {
            mAxSandboxManager.removeLockedApp(packageName);
        }
    }

    /** @hide */
    public void setShouldRedactNotification(@NonNull String packageName, boolean redact) {
        // Notification redaction is handled by the AxSandbox notification path.
    }

    /** @hide */
    public void setPackageHidden(@NonNull String packageName, boolean hidden) {
        mAxSandboxManager.setPackageHidden(packageName, hidden);
    }

    /** @hide */
    public void unlockPackage(@NonNull String packageName) {
        mAxSandboxManager.unlockApp(packageName, UserHandle.myUserId());
    }

    /** @hide */
    public boolean isBiometricsAllowed() {
        return Settings.Secure.getInt(mContext.getContentResolver(), SETTING_BIOMETRICS_ALLOWED,
                DEFAULT_BIOMETRICS_ALLOWED ? 1 : 0) != 0;
    }

    /** @hide */
    public void setBiometricsAllowed(boolean allowed) {
        Settings.Secure.putInt(mContext.getContentResolver(), SETTING_BIOMETRICS_ALLOWED,
                allowed ? 1 : 0);
    }

    /** @hide */
    public long getTimeout() {
        return Settings.Secure.getLong(mContext.getContentResolver(),
                AxSandboxManager.SETTING_LOCK_TIMEOUT, AxSandboxManager.DEFAULT_LOCK_TIMEOUT);
    }

    /** @hide */
    public void setTimeout(long timeout) {
        Settings.Secure.putLong(mContext.getContentResolver(),
                AxSandboxManager.SETTING_LOCK_TIMEOUT, timeout);
    }

    private boolean containsPackage(List<PackageData> data, String packageName) {
        for (PackageData packageData : data) {
            if (packageData.packageName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    /** @hide */
    public static final class PackageData {
        public final String packageName;
        public final boolean shouldProtectApp;
        public final boolean shouldRedactNotification;

        private PackageData(String packageName, boolean shouldProtectApp,
                boolean shouldRedactNotification) {
            this.packageName = packageName;
            this.shouldProtectApp = shouldProtectApp;
            this.shouldRedactNotification = shouldRedactNotification;
        }
    }
}
