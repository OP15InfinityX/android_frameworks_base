/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.doze;

import android.app.AlarmManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.RotationUtils;
import android.view.Surface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.android.systemui.Dumpable;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.doze.util.BurnInHelperKt;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.AlarmTimeout;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import dagger.Lazy;

import com.android.systemui.statusbar.phone.DozeParameters;

/** Optional transport controls for the selected user's existing media session while dozing. */
@SysUISingleton
public final class AodMediaControls implements Dumpable, TunerService.Tunable {
    public static final String SETTING = "aod_media_controls";
    private static final long PAUSED_TIMEOUT_MS = 20 * 60 * 1000L;
    private final Context mContext;
    private final TunerService mTunerService;
    private final UserTracker mUserTracker;
    private final KeyguardStateController mKeyguardStateController;
    private final FalsingManager mFalsingManager;
    private final MediaSessionManager mSessionManager;
    private final Lazy<DozeParameters> mDozeParameters;
    private final Lazy<AuthController> mAuthController;
    private final int[] mRootLocation = new int[2];
    private final AuthController.Callback mAuthCallback = new AuthController.Callback() {
        @Override
        public void onFingerprintLocationChanged() {
            updatePosition();
        }

        @Override
        public void onUdfpsLocationChanged(UdfpsOverlayParams params) {
            updatePosition();
        }
    };
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Rect mHitRect = new Rect();
    private final int mTouchSlop;
    private FrameLayout mRoot;
    private View mView;
    private View mTransportRow;
    private TextView mTitle;
    private TextView mArtist;
    private final View.OnLayoutChangeListener mLayoutChanged =
            (v, l, t, r, b, ol, ot, or, ob) -> updatePosition();
    private final AlarmTimeout mPausedTimeout;
    private long mPausedDeadline;
    private ImageButton mPrevious;
    private ImageButton mPlayPause;
    private ImageButton mNext;
    private MediaController mController;
    private boolean mEnabled;
    private boolean mDozing;
    private boolean mUserChanging;
    private boolean mListening;
    private boolean mPlaying;
    private boolean mOwnsTouch;
    private ImageButton mPressedButton;
    private Object mPressedSession;
    private int mPointerId;
    private float mDownX;
    private float mDownY;
    private long mLastActionTime;
    private int mActionCount;
    private int mTouchCount;
    private int mSensorTapCount;

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsChanged =
            sessions -> refreshSessions();
    private final MediaController.Callback mMediaCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (canResume(state)) updateView();
            else refreshSessions();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateView();
        }
    };
    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override
        public void onBeforeUserSwitching(int newUser) {
            mUserChanging = true;
            stopListening();
            updateView();
        }

        @Override
        public void onUserChanged(int newUser, Context userContext) {
            mUserChanging = false;
            mEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(), SETTING,
                    0, newUser) != 0;
            updateListening();
        }
    };
    private final KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    updateView();
                }
            };

    @Inject
    public AodMediaControls(Context context, TunerService tunerService, UserTracker userTracker,
            KeyguardStateController keyguardStateController, FalsingManager falsingManager,
            DumpManager dumpManager, Lazy<DozeParameters> dozeParameters,
            Lazy<AuthController> authController) {
        mContext = context;
        mDozeParameters = dozeParameters;
        mAuthController = authController;
        mPausedTimeout = new AlarmTimeout(context.getSystemService(AlarmManager.class),
                this::updateView, "AodMediaControlsPaused", mHandler);
        mTunerService = tunerService;
        mUserTracker = userTracker;
        mKeyguardStateController = keyguardStateController;
        mFalsingManager = falsingManager;
        mSessionManager = context.getSystemService(MediaSessionManager.class);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        dumpManager.registerNormalDumpable("AodMediaControls", this);
    }

    public void attach(FrameLayout root) {
        if (root == null || mRoot == root) return;
        if (mRoot != null) {
            mRoot.removeOnLayoutChangeListener(mLayoutChanged);
            mView.removeOnLayoutChangeListener(mLayoutChanged);
            mRoot.removeView(mView);
        } else {
            mAuthController.get().addCallback(mAuthCallback);
            mTunerService.addTunable(this, SETTING);
            mUserTracker.addCallback(mUserCallback, mContext.getMainExecutor());
            mKeyguardStateController.addCallback(mKeyguardCallback);
        }
        mRoot = root;
        mView = LayoutInflater.from(root.getContext()).inflate(R.layout.aod_media_controls,
                root, false);
        mTransportRow = mView.findViewById(R.id.aod_media_transport_row);
        mTitle = mView.findViewById(R.id.aod_media_title);
        mArtist = mView.findViewById(R.id.aod_media_artist);
        mPrevious = mView.findViewById(R.id.aod_media_previous);
        mPlayPause = mView.findViewById(R.id.aod_media_play_pause);
        mNext = mView.findViewById(R.id.aod_media_next);
        mPrevious.setOnClickListener(v -> performAction(mPrevious));
        mPlayPause.setOnClickListener(v -> performAction(mPlayPause));
        mNext.setOnClickListener(v -> performAction(mNext));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = getControlsTop();
        // Keep the buttons below the existing AOD dimming scrim.
        int front = root.indexOfChild(root.findViewById(R.id.scrim_in_front));
        root.addView(mView, front < 0 ? root.getChildCount() : front, lp);
        root.addOnLayoutChangeListener(mLayoutChanged);
        mView.addOnLayoutChangeListener(mLayoutChanged);
        updateListening();
    }

    @Override
    public void onTuningChanged(String key, String value) {
        mEnabled = TunerService.parseIntegerSwitch(value, false);
        updateListening();
    }

    public void setDozing(boolean dozing) {
        mDozing = dozing;
        updateListening();
    }

    private void updateListening() {
        boolean listen = mEnabled && mDozing && !mUserChanging && mView != null;
        if (listen && !mListening) {
            mListening = true;
            mSessionManager.addOnActiveSessionsChangedListener(null,
                    UserHandle.of(mUserTracker.getUserId()), mContext.getMainExecutor(),
                    mSessionsChanged);
            refreshSessions();
        } else if (!listen) {
            stopListening();
        }
        updateView();
    }

    private void stopListening() {
        if (mListening) mSessionManager.removeOnActiveSessionsChangedListener(mSessionsChanged);
        mListening = false;
        setController(null);
        cancelPressedButton();
    }

    private void refreshSessions() {
        if (mListening) {
            selectSession(mSessionManager.getActiveSessionsForUser(null,
                    UserHandle.of(mUserTracker.getUserId())));
        }
    }

    private static boolean isPlaying(PlaybackState state) {
        return state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING
                || state.getState() == PlaybackState.STATE_CONNECTING);
    }

    private static boolean canResume(PlaybackState state) {
        return isPlaying(state) || state != null && state.getState() == PlaybackState.STATE_PAUSED;
    }

    private void selectSession(List<MediaController> sessions) {
        if (!mListening || mUserChanging) return;
        MediaController selected = null;
        for (MediaController candidate : sessions) {
            // Also reject a delayed callback/token from the previous foreground user.
            if (UserHandle.getUserId(candidate.getSessionToken().getUid())
                    != mUserTracker.getUserId()) continue;
            if (!canResume(candidate.getPlaybackState())) continue;
            if (selected == null || !isPlaying(selected.getPlaybackState())
                    && (isPlaying(candidate.getPlaybackState())
                    || mController != null && candidate.controlsSameSession(mController))) {
                selected = candidate;
            }
        }
        setController(selected);
        updateView();
    }

    private void setController(MediaController controller) {
        if (mController != null && controller != null
                && mController.controlsSameSession(controller)) return;
        if (mController != null) mController.unregisterCallback(mMediaCallback);
        mController = controller;
        clearPausedTimeout();
        cancelPressedButton();
        if (mController != null) mController.registerCallback(mMediaCallback, mHandler);
    }

    private boolean canShow() {
        return mEnabled && mDozing && !mUserChanging && mController != null
                && mKeyguardStateController.isShowing() && !mKeyguardStateController.isOccluded()
                && canResume(mController.getPlaybackState())
                && (isPlaybackRunning(mController.getPlaybackState())
                    || mPausedDeadline > SystemClock.elapsedRealtime());
    }

    private void updateView() {
        if (mView == null) return;
        updatePausedTimeout();
        boolean show = canShow();
        mView.setVisibility(show ? View.VISIBLE : View.GONE);
        mDozeParameters.get().setAodMediaControlsActive(show);
        if (!show) {
            cancelPressedButton();
            return;
        }
        updateMetadata();
        PlaybackState state = mController.getPlaybackState();
        if (state == null) return;
        mPlaying = isPlaying(state);
        long actions = state.getActions();
        mPlayPause.setImageResource(mPlaying ? R.drawable.ic_aod_media_pause
                : R.drawable.ic_aod_media_play);
        mPlayPause.setContentDescription(mContext.getString(mPlaying
                ? R.string.aod_media_pause : R.string.aod_media_play));
        setButtonEnabled(mPlayPause, (actions & (PlaybackState.ACTION_PLAY_PAUSE
                | (mPlaying ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) != 0);
        setButtonEnabled(mPrevious, (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        setButtonEnabled(mNext, (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        dozeTimeTick();
    }

    private int getControlsTop() {
        // Move the entire player down by one play-button diameter, respecting display density.
        int diameter = mPlayPause.getHeight();
        if (diameter == 0) diameter = Math.max(0, mPlayPause.getLayoutParams().height);
        return Math.round(mRoot.getHeight() * 0.59f) + diameter;
    }

    private void updatePosition() {
        if (mRoot == null || mView == null || mTransportRow == null) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mView.getLayoutParams();
        if (params == null) return;
        // Keep the metadata above the controls, including large-font layouts.
        int top = Math.max(0, getControlsTop() - mTransportRow.getTop());
        if (mView.getHeight() > 0) {
            int travel = getVerticalBurnInAmplitude();
            int clearance = Math.round(26 * mContext.getResources().getDisplayMetrics().density);
            int maxTop = mRoot.getHeight() - mView.getHeight() - travel;
            Rect sensor = getFingerprintBounds();
            mRoot.getLocationOnScreen(mRootLocation);
            if (sensor != null) {
                int horizontalTravel = getHorizontalBurnInAmplitude();
                int left = mRootLocation[0] + mView.getLeft() - horizontalTravel;
                int right = mRootLocation[0] + mView.getRight() + horizontalTravel;
                if (sensor.left < right && sensor.right > left) {
                    // Reserve the actual fingerprint sensor area, even at maximum downward drift.
                    maxTop = Math.min(maxTop, sensor.top - mRootLocation[1] - clearance
                            - travel - mView.getHeight());
                }
            }
            top = Math.min(top, Math.max(0, maxTop));
        }
        if (params.topMargin != top) {
            params.topMargin = top;
            mView.setLayoutParams(params);
        }
    }

    private Rect getFingerprintBounds() {
        Point center = mAuthController.get().getUdfpsLocation();
        float radius = mAuthController.get().getUdfpsRadius();
        if (center == null || radius <= 0 || mRoot.getDisplay() == null) return null;
        Rect bounds = new Rect((int) Math.floor(center.x - radius),
                (int) Math.floor(center.y - radius), (int) Math.ceil(center.x + radius),
                (int) Math.ceil(center.y + radius));
        Point size = new Point();
        mRoot.getDisplay().getRealSize(size);
        int rotation = mRoot.getDisplay().getRotation();
        boolean swapped = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        RotationUtils.rotateBounds(bounds, swapped ? size.y : size.x,
                swapped ? size.x : size.y, rotation);
        return bounds;
    }

    private int getHorizontalBurnInAmplitude() {
        return Math.max(1, Math.round(mContext.getResources().getDisplayMetrics().density));
    }

    private int getVerticalBurnInAmplitude() {
        return Math.max(1, Math.round(8 * mContext.getResources().getDisplayMetrics().density));
    }

    private void updateMetadata() {
        MediaMetadata metadata = mController.getMetadata();
        setMetadataText(mTitle, firstMetadataText(metadata,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE));
        setMetadataText(mArtist, firstMetadataText(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
    }

    private static CharSequence firstMetadataText(MediaMetadata metadata, String... keys) {
        if (metadata == null) return null;
        for (String key : keys) {
            CharSequence text = metadata.getText(key);
            if (!TextUtils.isEmpty(text)) return text;
        }
        return null;
    }

    private static void setMetadataText(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private static boolean isPlaybackRunning(PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private void clearPausedTimeout() {
        mPausedTimeout.cancel();
        mPausedDeadline = 0;
    }

    private void updatePausedTimeout() {
        PlaybackState state = mController == null ? null : mController.getPlaybackState();
        if (!mEnabled || !mDozing || mUserChanging || !canResume(state)
                || isPlaybackRunning(state)) {
            clearPausedTimeout();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (mPausedDeadline == 0) mPausedDeadline = now + PAUSED_TIMEOUT_MS;
        if (mPausedDeadline > now && !mPausedTimeout.isScheduled()) {
            mPausedTimeout.schedule(mPausedDeadline - now, AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
        }
    }

    private static void setButtonEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.3f);
    }

    public void dozeTimeTick() {
        if (mView == null || mView.getVisibility() != View.VISIBLE) return;
        int horizontal = getHorizontalBurnInAmplitude();
        int vertical = getVerticalBurnInAmplitude();
        // Minimal lateral motion, more vertical motion; updatePosition reserves the full range.
        mView.setTranslationX(BurnInHelperKt.getBurnInOffset(horizontal * 2, true) - horizontal);
        mView.setTranslationY(BurnInHelperKt.getBurnInOffset(vertical * 2, false) - vertical);
    }

    private ImageButton buttonAt(float x, float y) {
        if (mView == null || !mView.isShown() || !canShow()) return null;
        for (ImageButton button : new ImageButton[]{mPrevious, mPlayPause, mNext}) {
            if (button.getGlobalVisibleRect(mHitRect)
                    && mHitRect.contains(Math.round(x), Math.round(y))) return button;
        }
        return null;
    }

    private void cancelPressedButton() {
        if (mPressedButton != null) mPressedButton.setPressed(false);
        mPressedButton = null;
        mPressedSession = null;
    }

    /** Consume the complete button gesture before the shade's wake-up detectors see it. */
    public boolean dispatchTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mOwnsTouch = false;
            cancelPressedButton();
            ImageButton button = buttonAt(event.getRawX(), event.getRawY());
            if (button == null) return false;
            mOwnsTouch = true;
            mTouchCount++;
            mPointerId = event.getPointerId(0);
            mDownX = event.getRawX();
            mDownY = event.getRawY();
            if (button.isEnabled() && !mFalsingManager.isProximityNear()) {
                mPressedButton = button;
                mPressedSession = mController.getSessionToken();
                button.setPressed(true);
            }
        }
        if (!mOwnsTouch) return false;
        if (event.getPointerCount() != 1 || event.getPointerId(0) != mPointerId
                || Math.abs(event.getRawX() - mDownX) > mTouchSlop
                || Math.abs(event.getRawY() - mDownY) > mTouchSlop) {
            cancelPressedButton();
        }
        if (action == MotionEvent.ACTION_UP) {
            ImageButton button = mPressedButton;
            boolean valid = button != null && mController != null
                    && Objects.equals(mPressedSession, mController.getSessionToken())
                    && buttonAt(event.getRawX(), event.getRawY()) == button
                    && !mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY);
            cancelPressedButton();
            mOwnsTouch = false;
            if (valid) button.performClick();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelPressedButton();
            mOwnsTouch = false;
        }
        return true;
    }

    /** A tap already checked for proximity by DozeTriggers, with screen coordinates. */
    public boolean dispatchSensorTap(float x, float y) {
        ImageButton button = buttonAt(x, y);
        if (button == null) return false;
        mSensorTapCount++;
        if (!mFalsingManager.isProximityNear()) button.performClick();
        return true;
    }

    private void performAction(ImageButton button) {
        if (!canShow() || !button.isEnabled() || mFalsingManager.isProximityNear()) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastActionTime < 350) return;
        mLastActionTime = now;
        MediaController.TransportControls controls = mController.getTransportControls();
        if (button == mPrevious) controls.skipToPrevious();
        else if (button == mNext) controls.skipToNext();
        else if (isPlaying(mController.getPlaybackState())) controls.pause();
        else controls.play();
        mActionCount++;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("enabled=" + mEnabled + " dozing=" + mDozing + " listening=" + mListening
                + " visible=" + (mView != null && mView.getVisibility() == View.VISIBLE));
        pw.println("user=" + mUserTracker.getUserId() + " playing=" + mPlaying
                + " actionsDispatched=" + mActionCount);
        pw.println("touchGestures=" + mTouchCount + " sensorTaps=" + mSensorTapCount
                + " proximityNear=" + mFalsingManager.isProximityNear());
        pw.println("session=" + (mController == null ? "none" : mController.getPackageName()));
        if (mView != null && mRoot != null && mPlayPause != null) {
            int[] location = new int[2];
            mPlayPause.getLocationOnScreen(location);
            pw.println("playCenter=" + (location[0] + mPlayPause.getWidth() / 2f) + ","
                    + (location[1] + mPlayPause.getHeight() / 2f)
                    + " rootWidth=" + mRoot.getWidth()
                    + " motion=" + mView.getTranslationX() + "," + mView.getTranslationY());
            pw.println("fingerprintBounds=" + getFingerprintBounds());
        }
        pw.println("pausedTimeoutRemainingMs=" + (mPausedDeadline == 0 ? 0
                : Math.max(0, mPausedDeadline - SystemClock.elapsedRealtime())));
    }
}
