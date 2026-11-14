package com.aamaker.app.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.Display
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object AAMakerCentralBridgeExecutor {

    private val executor = Executors.newSingleThreadExecutor()
    private val SAFE_COMP_REGEX = Regex("[^a-zA-Z0-9._/$]")
    private var persistentShizukuProcess: Process? = null
    private var persistentShizukuWriter: OutputStreamWriter? = null
    private var cachedNewProcessMethod: Method? = null

    fun executeLaunchOnDisplay(displayId: Int, componentName: String) {
        if (displayId == Display.DEFAULT_DISPLAY || componentName.isBlank()) return
        val safeComp = componentName.replace(SAFE_COMP_REGEX, "")
        if (safeComp.isBlank()) return
        val pkgName = safeComp.substringBefore('/')
        val cmd = if (pkgName.isNotBlank()) {
            "cmd deviceidle tempwhitelist -d 300000 $pkgName >/dev/null 2>&1; am start --display $displayId --activity-single-top --activity-clear-top -n $safeComp"
        } else {
            "am start --display $displayId --activity-single-top --activity-clear-top -n $safeComp"
        }
        runPrivilegedShellCommand(cmd)
    }

    fun executeTouchCommandFallback(
        displayId: Int,
        type: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ) {
        if (displayId == Display.DEFAULT_DISPLAY) return
        val ix1 = x1.roundToInt()
        val iy1 = y1.roundToInt()
        val cmd = if (type == AAMakerTouchBridgeService.TYPE_SWIPE) {
            val ix2 = x2.roundToInt()
            val iy2 = y2.roundToInt()
            "input -d $displayId swipe $ix1 $iy1 $ix2 $iy2 ${durationMs.coerceIn(60L, 800L)}"
        } else {
            "input -d $displayId tap $ix1 $iy1"
        }
        runPrivilegedShellCommand(cmd)
    }

    fun executeBackKey(displayId: Int) {
        val cmd = if (displayId != Display.DEFAULT_DISPLAY) {
            "input -d $displayId keyevent 4"
        } else {
            "input keyevent 4"
        }
        runPrivilegedShellCommand(cmd)
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
        if (intent == null) return
        when (intent.action) {
            AAMakerTouchBridgeService.ACTION_LAUNCH_ON_DISPLAY -> {
                val displayId = intent.getIntExtra(AAMakerTouchBridgeService.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                val componentName = intent.getStringExtra(AAMakerTouchBridgeService.EXTRA_COMPONENT_NAME).orEmpty()
                AAMakerCentralBridgeExecutor.executeLaunchOnDisplay(displayId, componentName)
            }
            AAMakerTouchBridgeService.ACTION_INJECT_TOUCH -> {
                val displayId = intent.getIntExtra(AAMakerTouchBridgeService.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                val type = intent.getStringExtra(AAMakerTouchBridgeService.EXTRA_TOUCH_TYPE) ?: AAMakerTouchBridgeService.TYPE_TAP
                val x1 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_X1, 0f)
                val y1 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_Y1, 0f)
                val x2 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_X2, x1)
                val y2 = intent.getFloatExtra(AAMakerTouchBridgeService.EXTRA_Y2, y1)
                val durationMs = intent.getLongExtra(AAMakerTouchBridgeService.EXTRA_DURATION_MS, 60L)
                AAMakerCentralBridgeExecutor.executeTouchCommandFallback(displayId, type, x1, y1, x2, y2, durationMs)
            }
            AAMakerTouchBridgeService.ACTION_INJECT_BACK -> {
                val displayId = intent.getIntExtra(AAMakerTouchBridgeService.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                AAMakerCentralBridgeExecutor.executeBackKey(displayId)
            }
        }
    }
}

object AAMakerTouchBridgeService {
    const val ACTION_LAUNCH_ON_DISPLAY = "com.aamaker.action.LAUNCH_ON_DISPLAY"
    const val ACTION_INJECT_TOUCH = "com.aamaker.action.INJECT_TOUCH"
    const val ACTION_INJECT_BACK = "com.aamaker.action.INJECT_BACK"
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
