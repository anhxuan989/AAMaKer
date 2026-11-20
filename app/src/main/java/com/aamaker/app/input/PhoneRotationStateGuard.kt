package com.aamaker.app.input

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * Captures the phone's screen auto-rotate (`ACCELEROMETER_ROTATION`) and locked orientation
 * (`USER_ROTATION`) before an Android Auto session starts, and restores them when the session ends.
 */
object PhoneRotationStateGuard {

    private const val PREFS_NAME = "aamaker_rotation_guard"
    private const val KEY_AUTO_ROTATE = "saved_accelerometer_rotation"
    private const val KEY_USER_ROTATION = "saved_user_rotation"

    private val bgExecutor = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun captureBeforeCarSession(context: Context) {
        val appCtx = context.applicationContext
        try {
            val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (sp.contains(KEY_AUTO_ROTATE)) return
            val resolver = appCtx.contentResolver
            val autoRotate = Settings.System.getInt(
                resolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            ).coerceIn(0, 1)
            val userRotation = Settings.System.getInt(
                resolver,
                Settings.System.USER_ROTATION,
                0
            ).coerceIn(0, 3)
            sp.edit()
                .putInt(KEY_AUTO_ROTATE, autoRotate)
                .putInt(KEY_USER_ROTATION, userRotation)
                .apply()
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun restoreAfterCarSession(context: Context) {
        val appCtx = context.applicationContext
        val (autoRotate, userRotation) = try {
            val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!sp.contains(KEY_AUTO_ROTATE)) return
            val savedAuto = sp.getInt(KEY_AUTO_ROTATE, -1)
            val savedUser = sp.getInt(KEY_USER_ROTATION, -1)
            sp.edit()
                .remove(KEY_AUTO_ROTATE)
                .remove(KEY_USER_ROTATION)
                .apply()
            savedAuto to savedUser
        } catch (_: Throwable) {
            return
        }

        if (autoRotate !in 0..1) return

        // 1. Direct restore if WRITE_SETTINGS permission is granted
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(appCtx)) {
                val resolver = appCtx.contentResolver
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, autoRotate)
                if (autoRotate == 0 && userRotation in 0..3) {
                    Settings.System.putInt(resolver, Settings.System.USER_ROTATION, userRotation)
                }
            }
        } catch (_: Throwable) {
        }

        // 2. Privileged restore via Shizuku / Root (works even without WRITE_SETTINGS)
        bgExecutor.execute {
            try {
                AAMakerCentralBridgeExecutor.executeRestorePhoneRotation(autoRotate, userRotation)
            } catch (_: Throwable) {
            }
        }
    }
}
