package com.aamaker.app.input

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.aamaker.core.patcher.security.BridgeAuthManager
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object AAMakerCentralBridgeExecutor {

    private val executor = Executors.newSingleThreadExecutor()
    private val SAFE_PKG_REGEX = Regex("[^a-zA-Z0-9._]")
    private val SAFE_CLS_REGEX = Regex("[^a-zA-Z0-9._$]")
    private var persistentShizukuProcess: Process? = null
    private var persistentShizukuWriter: OutputStreamWriter? = null
    private var cachedNewProcessMethod: Method? = null

    fun executeLaunchOnDisplay(
        context: Context,
        displayId: Int,
        componentName: String,
        verifiedSenderPackage: String
    ) {
        if (!isValidVirtualDisplayForSender(context, displayId, verifiedSenderPackage) || componentName.isBlank()) return
        val parsedComp = ComponentName.unflattenFromString(componentName) ?: return
        if (parsedComp.packageName != verifiedSenderPackage) return
        val safePkg = parsedComp.packageName.replace(SAFE_PKG_REGEX, "")
        val safeCls = parsedComp.className.replace(SAFE_CLS_REGEX, "")
        if (safePkg.isBlank() || safeCls.isBlank() || safePkg != verifiedSenderPackage) return
        val safeComp = "$safePkg/$safeCls"
        val cmd = "cmd deviceidle tempwhitelist -d 300000 $safePkg >/dev/null 2>&1; am start --display $displayId --activity-single-top --activity-clear-top -n $safeComp"
        runPrivilegedShellCommand(cmd)
    }

    fun executeTouchCommandFallback(
        context: Context,
        displayId: Int,
        verifiedSenderPackage: String,
        type: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ) {
        if (!isValidVirtualDisplayForSender(context, displayId, verifiedSenderPackage)) return
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return
        val ix1 = x1.roundToInt().coerceIn(0, 10000)
        val iy1 = y1.roundToInt().coerceIn(0, 10000)
        val cmd = if (type == AAMakerTouchBridgeService.TYPE_SWIPE) {
            val ix2 = x2.roundToInt().coerceIn(0, 10000)
            val iy2 = y2.roundToInt().coerceIn(0, 10000)
            "input -d $displayId swipe $ix1 $iy1 $ix2 $iy2 ${durationMs.coerceIn(60L, 800L)}"
        } else {
            "input -d $displayId tap $ix1 $iy1"
        }
        runPrivilegedShellCommand(cmd)
    }

    fun executeBackKey(context: Context, displayId: Int, verifiedSenderPackage: String) {
        if (!isValidVirtualDisplayForSender(context, displayId, verifiedSenderPackage)) return
        val cmd = "input -d $displayId keyevent 4"
        runPrivilegedShellCommand(cmd)
    }

    private fun isValidVirtualDisplayForSender(
        context: Context,
        displayId: Int,
        verifiedSenderPackage: String
    ): Boolean {
        if (displayId <= Display.DEFAULT_DISPLAY) return false
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
            val display = dm.getDisplay(displayId) ?: return false
            if (display.displayId <= Display.DEFAULT_DISPLAY) return false
            val expectedDisplayName = "AAMakerVirtualDisplay_$verifiedSenderPackage"
            display.name == expectedDisplayName || verifiedSenderPackage == context.packageName
        } catch (_: Throwable) {
            false
        }
    }

    private fun runPrivilegedShellCommand(cmd: String) {
        executor.execute {
            if (runViaShizuku(cmd)) return@execute

            try {
                if (Shell.isAppGrantedRoot() == true || Shell.getShell().isRoot) {
                    Shell.cmd(cmd).submit()
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Suppress("PrivateApi")
    private fun runViaShizuku(cmd: String): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false

            var writer = persistentShizukuWriter
            val proc = persistentShizukuProcess
            if (writer == null || proc == null || !proc.isAlive) {
                var method = cachedNewProcessMethod
                if (method == null) {
                    method = Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    ).apply { isAccessible = true }
                    cachedNewProcessMethod = method
                }
                val newProc = method.invoke(null, arrayOf("sh"), null, null) as Process
                writer = OutputStreamWriter(newProc.outputStream)
                persistentShizukuProcess = newProc
                persistentShizukuWriter = writer
            }
            writer.write("$cmd\n")
            writer.flush()
            true
        } catch (_: Throwable) {
            persistentShizukuWriter = null
            persistentShizukuProcess = null
            false
        }
    }
}

class AAMakerCommandBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val verifiedSenderPkg = verifyAuthorizedCaller(context, intent) ?: return

        when (intent.action) {
            AAMakerTouchBridgeService.ACTION_LAUNCH_ON_DISPLAY -> {
                val displayId = intent.getIntExtra(AAMakerTouchBridgeService.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                val componentName = intent.getStringExtra(AAMakerTouchBridgeService.EXTRA_COMPONENT_NAME).orEmpty()
                AAMakerCentralBridgeExecutor.executeLaunchOnDisplay(
                    context = context,
                    displayId = displayId,
                    componentName = componentName,
                    verifiedSenderPackage = verifiedSenderPkg
                )
            }
            AAMakerTouchBridgeService.ACTION_INJECT_TOUCH -> {
                val displayId = intent.getIntExtra(AAMakerTouchBridgeService.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                val type = intent.getStringExtra(AAMakerTouchBridgeService.EXTRA_TOUCH_TYPE) ?: AAMakerTouchBridgeService.TYPE_TAP
                val x1 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_X1, 0f)
                val y1 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_Y1, 0f)
                val x2 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_X2, x1)
                val y2 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_Y2, y1)
                val durationMs = intent.getLongExtra(AAMakerTouchBridgeService.EXTRA_DURATION_MS, 60L)
                AAMakerCentralBridgeExecutor.executeTouchCommandFallback(
                    context = context,
                    displayId = displayId,
                    verifiedSenderPackage = verifiedSenderPkg,
                    type = type,
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2,
                    durationMs = durationMs
                )
            }
            AAMakerTouchBridgeService.ACTION_INJECT_BACK -> {
                val displayId = intent.getIntExtra(AAMakerTouchBridgeService.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                AAMakerCentralBridgeExecutor.executeBackKey(context, displayId, verifiedSenderPkg)
            }
        }
    }

    private fun verifyAuthorizedCaller(context: Context, intent: Intent): String? {
        val callerPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AAMakerTouchBridgeService.EXTRA_CALLER_IDENTITY, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AAMakerTouchBridgeService.EXTRA_CALLER_IDENTITY)
        }

        val senderPackage = callerPendingIntent?.creatorPackage
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromPackage else null

        if (senderPackage.isNullOrBlank()) return null

        if (senderPackage == context.packageName) {
            return senderPackage
        }

        // Verify cryptographic signing certificate matches this device's local AAMaKer patching key
        if (!BridgeAuthManager.hasLocalPatchingSigningKeyCertMatch(context, senderPackage)) {
            return null
        }

        // Verify per-installation random secret token injected at patch time (if token initialized)
        val token = intent.getStringExtra(BridgeAuthManager.EXTRA_BRIDGE_TOKEN)
        if (!BridgeAuthManager.isValidToken(context, token)) {
            return null
        }

        return senderPackage
    }
}

object AAMakerTouchBridgeService {
    const val ACTION_LAUNCH_ON_DISPLAY = "com.aamaker.action.LAUNCH_ON_DISPLAY"
    const val ACTION_INJECT_TOUCH = "com.aamaker.action.INJECT_TOUCH"
    const val ACTION_INJECT_BACK = "com.aamaker.action.INJECT_BACK"
    const val EXTRA_CALLER_IDENTITY = "caller_identity"
    const val EXTRA_DISPLAY_ID = "display_id"
    const val EXTRA_COMPONENT_NAME = "component_name"
    const val EXTRA_TOUCH_TYPE = "touch_type"
    const val EXTRA_X1 = "x1"
    const val EXTRA_Y1 = "y1"
    const val EXTRA_X2 = "x2"
    const val EXTRA_Y2 = "y2"
    const val EXTRA_DURATION_MS = "duration_ms"
    const val TYPE_TAP = "tap"
    const val TYPE_SWIPE = "swipe"
}
