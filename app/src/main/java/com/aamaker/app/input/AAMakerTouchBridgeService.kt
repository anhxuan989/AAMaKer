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
import com.aamaker.core.patcher.security.PackageNameValidator
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object AAMakerCentralBridgeExecutor {

    internal val executor = Executors.newSingleThreadExecutor()
    private val authorizedDisplayOwners = ConcurrentHashMap<Int, String>()
    private var persistentShizukuProcess: Process? = null
    private var persistentShizukuWriter: OutputStreamWriter? = null
    private var cachedNewProcessMethod: Method? = null

    fun registerAuthorizedDisplayOwner(displayId: Int, ownerPackage: String) {
        if (displayId > Display.DEFAULT_DISPLAY && PackageNameValidator.isValidPackageName(ownerPackage)) {
            authorizedDisplayOwners[displayId] = ownerPackage
        }
    }

    fun unregisterAuthorizedDisplayOwner(displayId: Int) {
        if (displayId > Display.DEFAULT_DISPLAY) {
            authorizedDisplayOwners.remove(displayId)
        }
    }

    fun executeLaunchOnDisplay(
        context: Context,
        displayId: Int,
        componentName: String,
        verifiedSenderPackage: String
    ) {
        if (!PackageNameValidator.isValidPackageName(verifiedSenderPackage)) return
        if (!isValidVirtualDisplayForSender(context, displayId, verifiedSenderPackage) || componentName.isBlank()) return
        val parsedComp = ComponentName.unflattenFromString(componentName) ?: return
        if (parsedComp.packageName != verifiedSenderPackage) return
        if (!PackageNameValidator.isValidPackageName(parsedComp.packageName) ||
            !PackageNameValidator.isValidClassName(parsedComp.className)
        ) {
            return
        }
        val safePkg = parsedComp.packageName
        val safeCls = parsedComp.className
        val safeComp = "$safePkg/$safeCls"
        val binder = PrivilegedServerCoordinator.getOrStartServer(context)
        if (binder != null) {
            PrivilegedServerCoordinator.transactLaunchAppOnDisplay(binder, displayId, safePkg, safeCls)
        }
        val cmd = "cmd deviceidle whitelist +'$safePkg' >/dev/null 2>&1; " +
            "cmd deviceidle tempwhitelist -d 3600000 '$safePkg' >/dev/null 2>&1; " +
            "appops set '$safePkg' RUN_IN_BACKGROUND allow >/dev/null 2>&1; " +
            "appops set '$safePkg' RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
            "am set-standby-bucket '$safePkg' active >/dev/null 2>&1; " +
            "am start --display $displayId --activity-single-top --activity-clear-top -n '$safeComp' >/dev/null 2>&1"
        runPrivilegedShellCommand(cmd)
    }

    fun executeLaunchSecondaryAppOnDisplay(
        context: Context,
        displayId: Int,
        targetPackageName: String,
        mixAudioFocus: Boolean,
        authorizedHostPackage: String
    ): Boolean {
        if (!PackageNameValidator.isValidPackageName(targetPackageName) ||
            !PackageNameValidator.isValidPackageName(authorizedHostPackage)
        ) {
            return false
        }
        if (!isValidVirtualDisplayForSender(context, displayId, authorizedHostPackage)) {
            return false
        }
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPackageName)
        var resolvedComponent = launchIntent?.component
        if (resolvedComponent == null) {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                setPackage(targetPackageName)
            }
            val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
            val actInfo = resolveList.firstOrNull {
                !it.activityInfo.name.contains("CarAppPermissionActivity")
            }?.activityInfo
            if (actInfo != null) {
                resolvedComponent = ComponentName(targetPackageName, actInfo.name)
            }
        }
        val comp = resolvedComponent ?: return false
        if (!PackageNameValidator.isValidClassName(comp.className)) return false
        val safePkg = targetPackageName
        val safeComp = "$safePkg/${comp.className}"

        // 1. Primary path: Direct Binder IPC to ActivityTaskManager inside PrivilegedServer (UID 2000 / 0)
        // which moves existing tasks or starts the Activity directly onto displayId!
        val binder = PrivilegedServerCoordinator.getOrStartServer(context)
        if (binder != null) {
            PrivilegedServerCoordinator.transactLaunchAppOnDisplay(binder, displayId, safePkg, comp.className)
        }

        val audioFocusCmd = if (mixAudioFocus) {
            "appops set '$safePkg' TAKE_AUDIO_FOCUS ignore >/dev/null 2>&1; "
        } else {
            "appops set '$safePkg' TAKE_AUDIO_FOCUS allow >/dev/null 2>&1; "
        }
        // CRITICAL: If targetPackageName is already running on Display 0 (or another display), Android's
        // ActivityTaskSupervisor ignores --display <displayId> and resumes the existing task on Display 0
        // unless we use --activity-multiple-task --activity-new-task or move the task to displayId!
        val keepAliveCmd = "cmd deviceidle whitelist +'$safePkg' >/dev/null 2>&1; " +
            "cmd deviceidle tempwhitelist -d 3600000 '$safePkg' >/dev/null 2>&1; " +
            "appops set '$safePkg' RUN_IN_BACKGROUND allow >/dev/null 2>&1; " +
            "appops set '$safePkg' RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
            "am set-standby-bucket '$safePkg' active >/dev/null 2>&1; " +
            "wm dismiss-keyguard >/dev/null 2>&1; "
        val cmd = "${audioFocusCmd}${keepAliveCmd}" +
            "am start --display $displayId --windowingMode 1 --activity-single-top --activity-reorder-to-front --activity-new-task -n '$safeComp' >/dev/null 2>&1 || " +
            "am start --display $displayId --windowingMode 1 --activity-new-task --activity-multiple-task -n '$safeComp' >/dev/null 2>&1 || " +
            "am start --display $displayId --activity-new-task --activity-multiple-task -n '$safeComp' >/dev/null 2>&1 || " +
            "(am force-stop '$safePkg' >/dev/null 2>&1; am start --display $displayId -n '$safeComp' >/dev/null 2>&1)"
        runPrivilegedShellCommand(cmd)

        // Direct ActivityOptions fallback (succeeds immediately when LSPosed system_server hook or PUBLIC display is active)
        try {
            val directIntent = Intent(Intent.ACTION_MAIN).apply {
                component = comp
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            }
            val opts = android.app.ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(displayId)
            }
            context.startActivity(directIntent, opts.toBundle())
        } catch (_: Throwable) {
        }
        return true
    }

    fun enableMultiWindowSystemSettings() {
        val cmd = "settings put global force_resizable_activities 1 >/dev/null 2>&1; " +
            "settings put global enable_non_resizable_multi_window 1 >/dev/null 2>&1; " +
            "settings put global enable_freeform_support 1 >/dev/null 2>&1"
        runPrivilegedShellCommand(cmd)
    }

    fun submitPrivilegedCommandImmediate(cmd: String): Boolean {
        if (runViaShizuku(cmd)) return true
        return try {
            val granted = Shell.isAppGrantedRoot()
            val hasRoot = if (granted != null) {
                granted
            } else {
                runCatching { Shell.getShell().isRoot }.getOrDefault(false)
            }
            if (hasRoot) {
                Shell.cmd(cmd).submit()
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
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
        if (!PackageNameValidator.isValidPackageName(verifiedSenderPackage)) return
        if (!isValidVirtualDisplayForSender(context, displayId, verifiedSenderPackage)) return
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return
        val ix1 = x1.roundToInt().coerceIn(0, 10000)
        val iy1 = y1.roundToInt().coerceIn(0, 10000)
        val cmd = if (type == AAMakerTouchBridgeService.TYPE_SWIPE) {
            val ix2 = x2.roundToInt().coerceIn(0, 10000)
            val iy2 = y2.roundToInt().coerceIn(0, 10000)
            "input -d $displayId swipe $ix1 $iy1 $ix2 $iy2 ${durationMs.coerceIn(60L, 800L)} >/dev/null 2>&1"
        } else {
            "input -d $displayId tap $ix1 $iy1 >/dev/null 2>&1"
        }
        runPrivilegedShellCommand(cmd)
    }

    fun executeBackKey(context: Context, displayId: Int, verifiedSenderPackage: String) {
        if (!PackageNameValidator.isValidPackageName(verifiedSenderPackage)) return
        if (!isValidVirtualDisplayForSender(context, displayId, verifiedSenderPackage)) return
        val cmd = "input -d $displayId keyevent 4 >/dev/null 2>&1"
        runPrivilegedShellCommand(cmd)
    }

    fun executeSetPhoneBrightness(
        dim: Boolean,
        restoreBrightness: Int,
        restoreMode: Int,
        panelOffApplied: Boolean = false
    ) {
        if (dim) {
            val cmd = "wm dismiss-keyguard >/dev/null 2>&1; " +
                "settings put global stay_on_while_plugged_in 7 >/dev/null 2>&1; " +
                "settings put system screen_brightness_mode 0 >/dev/null 2>&1; " +
                "settings put system screen_brightness 0 >/dev/null 2>&1"
            runPrivilegedShellCommand(cmd)
        } else {
            val safeBrightness = if (restoreBrightness in 40..255) restoreBrightness else 128
            val safeMode = if (restoreMode in 0..1) restoreMode else 1
            val cmd = "settings put system screen_brightness $safeBrightness >/dev/null 2>&1; settings put system screen_brightness_mode $safeMode >/dev/null 2>&1"
            runPrivilegedShellCommand(cmd)
        }
    }

    private fun isValidVirtualDisplayForSender(
        context: Context,
        displayId: Int,
        verifiedSenderPackage: String
    ): Boolean {
        if (displayId <= Display.DEFAULT_DISPLAY) return false
        val registeredOwner = authorizedDisplayOwners[displayId]
        if (registeredOwner != null &&
            (registeredOwner == verifiedSenderPackage || verifiedSenderPackage == context.packageName)
        ) {
            return true
        }
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
            val display = dm.getDisplay(displayId) ?: return false
            if (display.displayId <= Display.DEFAULT_DISPLAY) return false
            val expectedDisplayName = "AAMakerVirtualDisplay_$verifiedSenderPackage"
            val dispName = display.name.orEmpty()
            dispName == expectedDisplayName ||
                dispName.startsWith("${expectedDisplayName}_") ||
                verifiedSenderPackage == context.packageName
        } catch (_: Throwable) {
            false
        }
    }

    private fun runPrivilegedShellCommand(cmd: String) {
        executor.execute {
            if (runViaShizuku(cmd)) return@execute

            try {
                val granted = Shell.isAppGrantedRoot()
                val hasRoot = if (granted != null) {
                    granted
                } else {
                    runCatching { Shell.getShell().isRoot }.getOrDefault(false)
                }
                if (hasRoot) {
                    Shell.cmd(cmd).submit()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun startDaemonDrainThread(stream: InputStream, name: String) {
        Thread({
            val buf = ByteArray(2048)
            try {
                while (stream.read(buf) >= 0) {
                    // Drain continuously to prevent 64KB pipe buffer deadlock
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { stream.close() }
            }
        }, name).apply {
            isDaemon = true
            start()
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
                runCatching { persistentShizukuWriter?.close() }
                runCatching { persistentShizukuProcess?.destroy() }
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
                startDaemonDrainThread(newProc.inputStream, "AAMaker-ShizukuStdoutDrain")
                startDaemonDrainThread(newProc.errorStream, "AAMaker-ShizukuStderrDrain")
                writer = OutputStreamWriter(newProc.outputStream)
                persistentShizukuProcess = newProc
                persistentShizukuWriter = writer
            }
            writer.write("$cmd\n")
            writer.flush()
            true
        } catch (_: Throwable) {
            runCatching { persistentShizukuWriter?.close() }
            runCatching { persistentShizukuProcess?.destroy() }
            persistentShizukuWriter = null
            persistentShizukuProcess = null
            false
        }
    }
}

class AAMakerCommandBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appContext = context.applicationContext ?: context
        val sentFromPkgFallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { sentFromPackage }.getOrNull()
        } else {
            null
        }
        val pendingResult = goAsync()

        AAMakerCentralBridgeExecutor.executor.execute {
            try {
                val verifiedSenderPkg = verifyAuthorizedCaller(appContext, intent, sentFromPkgFallback) ?: return@execute

                when (intent.action) {
                    AAMakerTouchBridgeService.ACTION_LAUNCH_ON_DISPLAY -> {
                        val displayId = intent.getIntExtra(AAMakerTouchBridgeService.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
                        val componentName = intent.getStringExtra(AAMakerTouchBridgeService.EXTRA_COMPONENT_NAME).orEmpty()
                        AAMakerCentralBridgeExecutor.executeLaunchOnDisplay(
                            context = appContext,
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
                            context = appContext,
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
                        AAMakerCentralBridgeExecutor.executeBackKey(appContext, displayId, verifiedSenderPkg)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private fun verifyAuthorizedCaller(
        context: Context,
        intent: Intent,
        sentFromPkgFallback: String?
    ): String? {
        val callerPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AAMakerTouchBridgeService.EXTRA_CALLER_IDENTITY, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AAMakerTouchBridgeService.EXTRA_CALLER_IDENTITY)
        }

        val senderPackage = callerPendingIntent?.creatorPackage ?: sentFromPkgFallback
        if (senderPackage.isNullOrBlank() || !PackageNameValidator.isValidPackageName(senderPackage)) return null

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
