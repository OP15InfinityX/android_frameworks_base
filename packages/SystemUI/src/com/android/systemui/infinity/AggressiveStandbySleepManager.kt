/*
 * Copyright (C) 2026 Project Infinity X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.systemui.infinity

import android.app.ActivityManager
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import com.android.systemui.CoreStartable
import com.android.systemui.keyguard.ScreenLifecycle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AggressiveStandbySleepManager @Inject constructor(
    private val context: Context,
    private val screenLifecycle: ScreenLifecycle,
) : CoreStartable, ScreenLifecycle.Observer {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val telecomManager = context.getSystemService(TelecomManager::class.java)
    private val generation = AtomicLong()
    private val enforcementRunning = AtomicBoolean(false)
    @Volatile private var screenOff = false
    @Volatile private var enabled = false

    private val enforcement = Runnable { runEnforcement() }
    private val settingObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateEnabled()
        }
    }

    override fun start() {
        updateEnabled()
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.AGGRESSIVE_STANDBY_SLEEP),
            false,
            settingObserver,
        )
        screenLifecycle.addObserver(this)
        screenOff = screenLifecycle.screenState == ScreenLifecycle.SCREEN_OFF
        if (screenOff && enabled) schedule(TimeUnit.MINUTES.toMillis(10))
    }

    override fun onScreenTurnedOff() {
        screenOff = true
        schedule(TimeUnit.MINUTES.toMillis(10))
    }

    override fun onScreenTurnedOn() {
        screenOff = false
        generation.incrementAndGet()
        handler.removeCallbacks(enforcement)
    }

    private fun schedule(delayMillis: Long) {
        generation.incrementAndGet()
        handler.removeCallbacks(enforcement)
        if (enabled) handler.postDelayed(enforcement, delayMillis)
    }

    private fun runEnforcement() {
        if (!enabled || !screenOff || !enforcementRunning.compareAndSet(false, true)) return
        val runGeneration = generation.get()
        try {
            executor.execute {
                try {
                    enforce(runGeneration)
                } finally {
                    enforcementRunning.set(false)
                    handler.post {
                        if (enabled && screenOff && generation.get() == runGeneration) {
                            schedule(TimeUnit.HOURS.toMillis(1))
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            enforcementRunning.set(false)
        }
    }

    private fun enforce(runGeneration: Long) {
        val manager = activityManager ?: return
        if (!isCurrent(runGeneration)) return
        if (isCallActive()) return

        val processes = manager.runningAppProcesses.orEmpty()
        val protected = protectedPackages()
        val downloadingPackages = activeDownloadPackages()
        val packages: Set<String> = buildSet {
            processes.forEach { process ->
                process.pkgList?.forEach { packageName ->
                    add(packageName)
                }
            }
        }
        val userVisibleServicePackages = userVisibleForegroundServicePackages()
        val audioActive = isAudioActive()
        val mediaProtectedPackages = if (audioActive) {
            setOf(
                "com.google.android.gms",
                "app.revanced.android.gms",
            )
        } else {
            emptySet()
        }

        packages.toSet().forEach { packageName ->
            if (!isCurrent(runGeneration)) return
            if (packageName in protected ||
                packageName in mediaProtectedPackages ||
                packageName.lowercase(Locale.ROOT).contains("clock") ||
                packageName in userVisibleServicePackages ||
                packageName in downloadingPackages ||
                (audioActive && isActiveMediaApp(packageName, processes))
            ) {
                return@forEach
            }
            forceStop(manager, packageName)
        }
    }

    private fun isCurrent(runGeneration: Long): Boolean =
        screenOff && generation.get() == runGeneration && enabled

    private fun isCallActive(): Boolean =
        telecomManager?.isInCall == true ||
            audioManager?.mode == AudioManager.MODE_IN_CALL ||
            audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audioManager?.mode == AudioManager.MODE_RINGTONE

    private fun isAudioActive(): Boolean =
        try { audioManager?.isMusicActive == true } catch (_: Exception) { false }

    private fun isActiveMediaApp(
        pkg: String,
        processes: List<ActivityManager.RunningAppProcessInfo>,
    ): Boolean {
        for (process in processes) {
            val pkgList = process.pkgList ?: continue
            for (name in pkgList) {
                if (name == pkg) {
                    return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                }
            }
        }
        return false
    }

    private fun forceStop(manager: ActivityManager, packageName: String) {
        try {
            ActivityManager.getService().forceStopPackage(packageName, UserHandle.myUserId())
        } catch (_: Exception) {
            try {
                manager.killBackgroundProcesses(packageName)
            } catch (_: Exception) {
                // The package may have exited between enumeration and enforcement.
            }
        }
    }

    private fun userVisibleForegroundServicePackages(): Set<String> =
        try {
            notificationManager?.activeNotifications
                ?.asSequence()
                ?.filter { it.notification.isForegroundService && it.isOngoing }
                ?.mapTo(mutableSetOf()) { it.packageName }
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }

    private fun activeDownloadPackages(): Set<String> {
        val manager = downloadManager ?: return emptySet()
        return try {
            manager.query(
                DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_RUNNING)
            )?.use { cursor ->
                val packageColumn = cursor.getColumnIndex("notificationpackage")
                if (packageColumn < 0) return@use emptySet()
                buildSet {
                    while (cursor.moveToNext()) {
                        cursor.getString(packageColumn)?.let(::add)
                    }
                }
            }.orEmpty()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun protectedPackages(): Set<String> {
        val packages = mutableSetOf(
            "android",
            "com.android.systemui",
            "com.android.providers.telephony",
            "com.android.server.telecom",
            "com.android.providers.media",
            "com.android.providers.media.module",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.whatsapp",
            "com.tencent.mm",
            "com.facebook.orca",
            "org.lunaris.dolby",
            "com.dolby.atmos",
        )
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        home?.activityInfo?.packageName?.let(packages::add)
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }
            ?.let(packages::add)
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            ?.let(packages::add)
        return packages
    }

    private fun updateEnabled() {
        val wasEnabled = enabled
        enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.AGGRESSIVE_STANDBY_SLEEP,
            0,
        ) != 0
        if (wasEnabled && !enabled) {
            generation.incrementAndGet()
            handler.removeCallbacks(enforcement)
        } else if (!wasEnabled && enabled && screenOff) {
            schedule(TimeUnit.MINUTES.toMillis(10))
        }
    }
}