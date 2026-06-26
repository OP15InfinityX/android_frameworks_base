/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server;

import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;

import java.util.concurrent.Executor;

/**
 * Shared background thread expected by Oplus system services.
 */
public final class OplusBackgroundThread extends ServiceThread {
    private static final long SLOW_DISPATCH_THRESHOLD_MS = 10_000;
    private static final long SLOW_DELIVERY_THRESHOLD_MS = 30_000;

    private static final class NoPreloadHolder {
        private static final OplusBackgroundThread sInstance = new OplusBackgroundThread();
    }

    private final Handler mHandler;
    private final HandlerExecutor mHandlerExecutor;

    private OplusBackgroundThread() {
        super("oplus.bg", Process.THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
        start();
        final Looper looper = getLooper();
        looper.setTraceTag(Trace.TRACE_TAG_SYSTEM_SERVER);
        looper.setSlowLogThresholdMs(SLOW_DISPATCH_THRESHOLD_MS, SLOW_DELIVERY_THRESHOLD_MS);
        mHandler = new Handler(looper, null /* callback */, false /* async */, true /* shared */);
        mHandlerExecutor = new HandlerExecutor(mHandler);
    }

    public static OplusBackgroundThread get() {
        return NoPreloadHolder.sInstance;
    }

    public static Handler getHandler() {
        return NoPreloadHolder.sInstance.mHandler;
    }

    public Handler getThreadHandler() {
        return mHandler;
    }

    public static Executor getExecutor() {
        return NoPreloadHolder.sInstance.mHandlerExecutor;
    }
}
