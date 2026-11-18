package com.mag.anyauto;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;

import java.io.File;

/**
 * Bridge for Root + LSPosed direct VirtualDisplay mode.
 */
public final class LsposedBridge {
    private static final String TAG = "AAMaKer.LsposedBridge";
    private static Boolean sDirectModeSupported = null;
    private static Context sContext = null;

    private LsposedBridge() {}

    public static void setContext(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
    }

    /**
     * Check if device is rooted.
     */
    public static boolean isRooted() {
        String[] paths = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap"
        };
        for (String p : paths) {
            try {
                if (new File(p).exists()) return true;
            } catch (Throwable ignored) {}
        }
        try {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String dir : pathEnv.split(":")) {
                    if (new File(dir, "su").exists()) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Check if LSPosed is installed on device.
     */
    public static boolean isLsposedInstalled(Context context) {
        if (context == null) context = sContext;
        if (context != null) {
            try {
                context.getPackageManager().getPackageInfo("org.lsposed.manager", 0);
                return true;
            } catch (Throwable ignored) {}
        }
        String[] lspDirs = {
            "/data/adb/lspd",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed"
        };
        for (String d : lspDirs) {
            try {
                if (new File(d).exists()) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Tests if system_server allows direct creation of a Trusted VirtualDisplay.
     * When AAMaKer's GearheadXposedHook is active in system_server,
     * this probe will succeed without SecurityException.
     */
    public static synchronized boolean isDirectModeAvailable(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
        if (sDirectModeSupported != null) {
            return sDirectModeSupported;
        }

        Context ctx = context != null ? context : sContext;
        if (ctx == null) {
            return false;
        }

        // 1. Fast IPC permission check
        try {
            int perm = ctx.checkCallingOrSelfPermission("android.permission.ADD_TRUSTED_DISPLAY");
            if (perm == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "ADD_TRUSTED_DISPLAY is granted via system_server hook");
                sDirectModeSupported = true;
                return true;
            }
        } catch (Throwable ignored) {}

        // 2. Direct VirtualDisplay probe
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                // VIRTUAL_DISPLAY_FLAG_PUBLIC (1) | VIRTUAL_DISPLAY_FLAG_TRUSTED (1024)
                int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | (1 << 10);
                VirtualDisplay vd = dm.createVirtualDisplay("aamaker_probe", 16, 16, 160, null, flags);
                if (vd != null) {
                    vd.release();
                    Log.i(TAG, "Direct VirtualDisplay probe succeeded! Direct mode active.");
                    sDirectModeSupported = true;
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "Direct VirtualDisplay probe refused: " + t.getMessage());
        }

        sDirectModeSupported = false;
        return false;
    }

    public static boolean isDirectModeAvailable() {
        return isDirectModeAvailable(sContext);
    }
}
