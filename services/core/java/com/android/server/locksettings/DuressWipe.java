package com.android.server.locksettings;

import android.content.Context;
import android.os.IRecoverySystem;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;

import com.android.server.power.PowerManagerService;
import com.android.server.recoverysystem.RecoverySystemService;

public class DuressWipe {
    static final String TAG = DuressWipe.class.getSimpleName();

    // used only for testing, guarded by owner credential
    public static boolean sleep5sBeforePoweroff;

    static void run(Context context) {
        Slog.d(TAG, "start");

        if (sleep5sBeforePoweroff) {
            SystemClock.sleep(5000);
        }

        Slog.d(TAG, "requesting recovery data wipe");
        try {
            IRecoverySystem recoverySystem = IRecoverySystem.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.RECOVERY_SERVICE));
            // RecoverySystemService writes this command to the BCB before deleting secrets and
            // rebooting. This ordering ensures that devices which don't automatically format an
            // undecryptable /data partition still complete the wipe in recovery.
            recoverySystem.rebootRecoveryWithCommand("--wipe_data\n--reason=duress\n");
        } catch (Throwable e) {
            Slog.e(TAG, "failed to request recovery data wipe", e);
        }

        // rebootRecoveryWithCommand() only returns if writing the BCB or rebooting failed. Keep
        // the cryptographic wipe as a fail-safe even in that case.
        Slog.e(TAG, "recovery data wipe request returned; falling back to shutdown");
        RecoverySystemService.deleteSecrets();
        PowerManagerService.lowLevelShutdown(null);
    }
}
