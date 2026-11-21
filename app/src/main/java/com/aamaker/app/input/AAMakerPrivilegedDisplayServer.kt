package com.aamaker.app.input

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Privileged Display & Input Server running inside Shizuku (UID 2000) or Root (UID 0) via `app_process`.
 *
 * Because it runs as UID 2000 / 0, Android grants it CAPTURE_VIDEO_OUTPUT, INJECT_EVENTS,
 * DEVICE_POWER, and MANAGE_DISPLAYS, allowing:
 * 1. Creation of PUBLIC | TRUSTED | ALWAYS_UNLOCKED | OWN_FOCUS VirtualDisplays for both Primary & Secondary apps.
 * 2. True hardware panel power-off (`IDisplayManager.requestDisplayPower` / `SurfaceControl.setDisplayPowerMode`)
 *    while keeping the DisplayGroup interactive so 60 FPS GPU rendering continues smoothly when the phone screen is turned off.
 * 3. Instant wake-and-blank interception when the user presses the physical Power button during an active Android Auto session.
 * 4. Sub-2ms MotionEvent & KeyEvent injection.
 */
object AAMakerPrivilegedDisplayServer {

    const val DESCRIPTOR = "com.aamaker.privileged.IDisplayBridge"
    const val SERVICE_PING_NAME = "AAMaKerPrivilegedDisplayServer_v2"

    const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_CREATE_OR_UPDATE_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_RELEASE_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_INJECT_MOTION = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_INJECT_KEY = IBinder.FIRST_CALL_TRANSACTION + 5
    const val TRANSACTION_INJECT_TAP = IBinder.FIRST_CALL_TRANSACTION + 6
    const val TRANSACTION_INJECT_SWIPE = IBinder.FIRST_CALL_TRANSACTION + 7
    const val TRANSACTION_SET_PHONE_PANEL_POWER = IBinder.FIRST_CALL_TRANSACTION + 8
    const val TRANSACTION_LAUNCH_APP_ON_DISPLAY = IBinder.FIRST_CALL_TRANSACTION + 9

    private const val SHELL_PKG = "com.android.shell"

    // Hidden flags on DisplayManager for shell/system VirtualDisplays
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14

    private data class HostedDisplayEntry(
        val sessionKey: String,
        var virtualDisplay: VirtualDisplay,
        var surface: Surface,
        var width: Int,
        var height: Int,
        var dpi: Int,
        var displayWakeLock: PowerManager.WakeLock? = null
    )

    private val hostedDisplays = ConcurrentHashMap<String, HostedDisplayEntry>()
    private var panelPowerController: PhonePanelPowerController? = null

    @JvmStatic
    fun main(args: Array<String>) {
        unlockHiddenApiExemptions()
        com.aamaker.app.stage.CarStageDisplayServer.initApplicationSharedMemoryIfNeeded()
        val readyTokenPath = args.firstOrNull()
        try {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        } catch (_: Throwable) {
            if (Looper.myLooper() == null) {
                Looper.prepare()
            }
        }

        val systemContext = resolveSystemContext()
        val mainHandler = Handler(Looper.getMainLooper())
        panelPowerController = PhonePanelPowerController(systemContext)
        val serverBinder = ServerBinder(systemContext, mainHandler)

        // Publish binder asynchronously so Looper.loop() is immediately active on the main thread
        Thread({
            publishBinderToAppProvider(systemContext, serverBinder, readyTokenPath)
        }, "PrivDisplay-BinderPublish").apply {
            isDaemon = true
            start()
        }

        // Keep periodic heartbeat check: if AAMaKer is uninstalled or explicitly signals shutdown, clean up
        Looper.loop()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun unlockHiddenApiExemptions() {
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime").apply { isAccessible = true }
            val runtime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            ).apply { isAccessible = true }
            setExemptions.invoke(runtime, arrayOf("L"))
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun resolveSystemContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        val systemMain = atClass.getMethod("currentActivityThread").invoke(null)
            ?: atClass.getMethod("systemMain").invoke(null)
        val rawCtx = atClass.getMethod("getSystemContext").invoke(systemMain) as Context
        val basePkgCtx = try {
            rawCtx.createPackageContext(SHELL_PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Throwable) {
            rawCtx
        }
        return ShellContextWrapper(basePkgCtx)
    }

    /**
     * Ensures all Binder calls (DisplayManager, PowerManager, ActivityTaskManager, InputManager)
     * report `com.android.shell` with UID 2000 / 0 AttributionSource so Android 12–17 never throws
     * `SecurityException: Package android does not belong to 2000`.
     */
    private class ShellContextWrapper(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PKG).build()
            } else {
                super.getAttributionSource()
            }
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun createShellDisplayManager(context: Context): DisplayManager? {
        return try {
            val ctor: Constructor<DisplayManager> = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(context)
        } catch (_: Throwable) {
            context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        }
    }

    private fun publishBinderToAppProvider(
        context: Context,
        binder: IBinder,
        tokenArg: String?
    ) {
        val bundle = android.os.Bundle().apply {
            putBinder("server_binder", binder)
            if (!tokenArg.isNullOrEmpty()) {
                putString("handshake_nonce", tokenArg)
            }
        }
        val authority = "com.aamaker.app.dualbridge"
        val method = "register_privileged_server"
        for (attempt in 1..3) {
            if (com.aamaker.app.stage.CarStageDisplayServer.callProviderViaActivityManager(authority, method, bundle)) {
                return
            }
            val ok = try {
                val uri = android.net.Uri.parse("content://$authority")
                val reply = context.contentResolver.call(uri, method, null, bundle)
                reply?.getBoolean("ok") == true
            } catch (_: Throwable) {
                false
            }
            if (ok) return
            if (attempt < 3) {
                runCatching { Thread.sleep(250L) }
            }
        }
    }

    /**
     * Hardware panel power controller running in UID 2000 / 0:
     * - Keeps DisplayGroup 0 awake via SCREEN_BRIGHT_WAKE_LOCK so Choreographer VSYNC and GPU RenderThread
     *   for both Primary and Secondary VirtualDisplays never pause or throttle.
     * - Powers off only the physical phone panel (`Display.DEFAULT_DISPLAY`) via `IDisplayManager.requestDisplayPower`
     *   or `SurfaceControl.setDisplayPowerMode(token, POWER_MODE_OFF)`.
     * - Intercepts physical Power button presses while an Android Auto session is active: immediately wakes the
     *   DisplayGroup back up in the background and blanks the physical panel so the phone screen stays dark
     *   without freezing the car projection.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "WakelockTimeout")
    private class PhonePanelPowerController(private val context: Context) {

        companion object {
            private const val WATCH_INTERVAL_MS = 500L
            private const val FIGHT_BLANKS = 3
            private const val FIGHT_WINDOW_MS = 10_000L
            private const val WAKE_SETTLE_MS = 2500L
            private const val POWER_MODE_OFF = 0
            private const val POWER_MODE_NORMAL = 2
        }

        private val powerThread = HandlerThread("aamaker-panel-power").apply { start() }
        private val handler = Handler(powerThread.looper)
        private val dm = createShellDisplayManager(context)
        private val pm = resolveShellPowerManager(context)

        @Suppress("DEPRECATION")
        private val brightWakeLock: PowerManager.WakeLock? = runCatching {
            pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "com.android.shell:AAMakerCarDisplayGroup"
            )?.apply { setReferenceCounted(false) }
        }.getOrNull()

        @Suppress("DEPRECATION")
        private val dimWakeLock: PowerManager.WakeLock? = runCatching {
            pm?.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "com.android.shell:AAMakerCarDimGroup"
            )?.apply { setReferenceCounted(false) }
        }.getOrNull()

        private var heldWakeLock: PowerManager.WakeLock? = null

        @Volatile
        private var sessionActive: Boolean = false

        @Volatile
        private var panelOffRequested: Boolean = false

        @Volatile
        private var panelFightsOff: Boolean = false

        private val blankTimestamps = LongArray(FIGHT_BLANKS)
        private var blankCount = 0
        private var settleUntilMs = 0L

        private var idmInstance: Any? = null
        private var requestDisplayPowerMethod: Method? = null
        private var surfaceControlClass: Class<*>? = null
        private var setDisplayPowerModeMethod: Method? = null
        private var getPhysicalDisplayTokenMethod: Method? = null
        private var iwmInstance: Any? = null
        private var dismissKeyguardMethod: Method? = null
        private var reflectionChecked = false
        private val activeRelaunchTargets = ConcurrentHashMap<Int, Pair<String, String>>()
        private var lastRelaunchTimeMs = 0L

        init {
            dm?.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {
                    activeRelaunchTargets.remove(displayId)
                }
                override fun onDisplayChanged(displayId: Int) {
                    if (sessionActive || panelOffRequested) {
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            handler.post { enforceDesiredState() }
                            handler.postDelayed({ enforceDesiredState() }, 120L)
                            handler.postDelayed({ enforceDesiredState() }, 350L)
                        } else if (hostedDisplays.values.any { it.virtualDisplay.display?.displayId == displayId }) {
                            handler.post { ensureHostedVirtualDisplaysPoweredOn() }
                        }
                    }
                }
            }, handler)
        }

        fun recordActiveDisplayTarget(displayId: Int, pkgName: String, clsName: String) {
            if (displayId > Display.DEFAULT_DISPLAY && pkgName.isNotBlank() && clsName.isNotBlank()) {
                activeRelaunchTargets[displayId] = pkgName to clsName
            }
        }

        fun onHostedDisplaysChanged(hasActiveDisplays: Boolean) {
            handler.post {
                if (hasActiveDisplays) {
                    ensureHostedVirtualDisplaysPoweredOn()
                    if (!sessionActive) {
                        sessionActive = true
                        enforceDesiredState()
                        scheduleWatchLoop()
                    }
                } else {
                    activeRelaunchTargets.clear()
                    if (sessionActive || panelOffRequested) {
                        sessionActive = false
                        panelOffRequested = false
                        panelFightsOff = false
                        blankCount = 0
                        restorePhysicalPanelAndReleaseLocks()
                    }
                }
            }
        }

        fun setPhoneScreenOffMode(screenOff: Boolean, keepSessionAwake: Boolean): Boolean {
            val appliedLatch = java.util.concurrent.CountDownLatch(1)
            var hardwareBlankOk = false
            handler.post {
                try {
                    sessionActive = keepSessionAwake || hostedDisplays.isNotEmpty()
                    if (screenOff) {
                        panelOffRequested = true
                        panelFightsOff = false
                        blankCount = 0
                        val interactive = pm?.isInteractive ?: true
                        // Always power off physical panel FIRST before waking PowerManager so the physical screen never flashes!
                        val preBlanked = setPhysicalPanelPower(off = true)
                        acquireAppropriateWakeLock()
                        if (!interactive) {
                            settleUntilMs = SystemClock.uptimeMillis() + WAKE_SETTLE_MS
                            wakeUpSystemInternal()
                            setPhysicalPanelPower(off = true)
                            dismissKeyguardInternal()
                            relaunchTrackedAppsOnVirtualDisplays()
                        }
                        ensureHostedVirtualDisplaysPoweredOn()
                        enforceDesiredState()
                        scheduleWatchLoop()
                        val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                        hardwareBlankOk = !panelFightsOff && (preBlanked || d?.state == Display.STATE_OFF || setPhysicalPanelPower(off = true))
                    } else {
                        panelOffRequested = false
                        restorePhysicalPanelOnly()
                        if (sessionActive) {
                            acquireAppropriateWakeLock()
                            ensureHostedVirtualDisplaysPoweredOn()
                            scheduleWatchLoop()
                        } else {
                            releaseHeldWakeLock()
                            handler.removeCallbacks(watchRunnable)
                        }
                        hardwareBlankOk = true
                    }
                } finally {
                    appliedLatch.countDown()
                }
            }
            runCatching { appliedLatch.await(800L, java.util.concurrent.TimeUnit.MILLISECONDS) }
            return hardwareBlankOk && !panelFightsOff
        }

        private val watchRunnable = object : Runnable {
            override fun run() {
                if (!sessionActive && !panelOffRequested) return
                enforceDesiredState()
                if (sessionActive || panelOffRequested) {
                    handler.postDelayed(this, WATCH_INTERVAL_MS)
                }
            }
        }

        private fun scheduleWatchLoop() {
            handler.removeCallbacks(watchRunnable)
            handler.postDelayed(watchRunnable, WATCH_INTERVAL_MS)
        }

        fun ensureDisplaysReadyForInput() {
            handler.post {
                if (pm?.isInteractive == false) {
                    panelOffRequested = true
                    setPhysicalPanelPower(off = true)
                    acquireAppropriateWakeLock()
                    wakeUpSystemInternal()
                    setPhysicalPanelPower(off = true)
                    dismissKeyguardInternal()
                }
                ensureHostedVirtualDisplaysPoweredOn()
            }
        }

        private fun enforceDesiredState() {
            val interactive = pm?.isInteractive ?: true
            if (!interactive && (sessionActive || panelOffRequested)) {
                // User pressed physical Power button or system timed out while AA projection is active!
                // Immediately transition into Panel-Off Awake mode: blank physical panel FIRST, then wake
                // PowerManager + dismiss Keyguard so unmodified 3rd-party secondary apps never get occluded or stopped!
                panelOffRequested = true
                settleUntilMs = SystemClock.uptimeMillis() + WAKE_SETTLE_MS
                setPhysicalPanelPower(off = true)
                releaseHeldWakeLock()
                acquireAppropriateWakeLock()
                wakeUpSystemInternal()
                setPhysicalPanelPower(off = true)
                dismissKeyguardInternal()
                ensureHostedVirtualDisplaysPoweredOn()
                relaunchTrackedAppsOnVirtualDisplays()
                handler.postDelayed({
                    if (sessionActive || panelOffRequested) {
                        setPhysicalPanelPower(off = true)
                        if (pm?.isInteractive == false) {
                            releaseHeldWakeLock()
                            acquireAppropriateWakeLock()
                            wakeUpSystemInternal()
                        }
                        setPhysicalPanelPower(off = true)
                        dismissKeyguardInternal()
                        ensureHostedVirtualDisplaysPoweredOn()
                        relaunchTrackedAppsOnVirtualDisplays()
                    }
                }, 140L)
                handler.postDelayed({
                    if (sessionActive || panelOffRequested) {
                        setPhysicalPanelPower(off = true)
                        if (pm?.isInteractive == false) {
                            wakeUpSystemInternal()
                        }
                        setPhysicalPanelPower(off = true)
                        dismissKeyguardInternal()
                        ensureHostedVirtualDisplaysPoweredOn()
                        relaunchTrackedAppsOnVirtualDisplays()
                    }
                }, 400L)
                return
            }

            if (sessionActive || panelOffRequested) {
                acquireAppropriateWakeLock()
                ensureHostedVirtualDisplaysPoweredOn()
            }

            if (panelOffRequested && !panelFightsOff && interactive) {
                val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                if (defaultDisplay == null || defaultDisplay.state != Display.STATE_OFF) {
                    val now = SystemClock.uptimeMillis()
                    if (now >= settleUntilMs) {
                        blankTimestamps[blankCount % FIGHT_BLANKS] = now
                        blankCount++
                        if (blankCount >= FIGHT_BLANKS &&
                            now - blankTimestamps[blankCount % FIGHT_BLANKS] < FIGHT_WINDOW_MS
                        ) {
                            // Flicker guard: OEM ROM refuses hardware panel off; keep awake with minimal brightness
                            panelFightsOff = true
                            acquireAppropriateWakeLock()
                            setPhysicalPanelPower(off = false)
                            return
                        }
                    }
                    val ok = setPhysicalPanelPower(off = true)
                    if (!ok) {
                        panelFightsOff = true
                    }
                }
            }
        }

        private fun acquireAppropriateWakeLock() {
            val targetLock = if (panelOffRequested && !panelFightsOff) {
                brightWakeLock ?: dimWakeLock
            } else {
                dimWakeLock ?: brightWakeLock
            } ?: return

            if (heldWakeLock === targetLock && targetLock.isHeld) return
            runCatching { targetLock.acquire() }
            if (heldWakeLock != null && heldWakeLock !== targetLock) {
                runCatching { heldWakeLock?.release() }
            }
            heldWakeLock = targetLock
        }

        private fun releaseHeldWakeLock() {
            runCatching {
                if (heldWakeLock?.isHeld == true) {
                    heldWakeLock?.release()
                }
            }
            heldWakeLock = null
        }

        private fun restorePhysicalPanelOnly() {
            setPhysicalPanelPower(off = false)
            if (pm?.isInteractive == false) {
                wakeUpSystemInternal()
            }
            handler.postDelayed({
                if (!panelOffRequested) {
                    val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                    if (d != null && d.state == Display.STATE_OFF && pm?.isInteractive == true) {
                        goToSleepInternal()
                        handler.postDelayed({ wakeUpSystemInternal() }, 250L)
                    }
                }
            }, 450L)
        }

        private fun restorePhysicalPanelAndReleaseLocks() {
            handler.removeCallbacks(watchRunnable)
            setPhysicalPanelPower(off = false)
            releaseHeldWakeLock()
            handler.postDelayed({
                if (!panelOffRequested && !sessionActive) {
                    val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                    if (d != null && d.state == Display.STATE_OFF && pm?.isInteractive == true) {
                        goToSleepInternal()
                        handler.postDelayed({ wakeUpSystemInternal() }, 250L)
                    }
                }
            }, 450L)
        }

        private fun ensureDisplayPowerReflection() {
            if (reflectionChecked) return
            reflectionChecked = true
            unlockHiddenApiExemptions()
            try {
                val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance")
                    .invoke(null)
                val dmField: Field = global.javaClass.getDeclaredField("mDm").apply { isAccessible = true }
                val idm = dmField.get(global)
                idmInstance = idm
                if (idm != null) {
                    for (m in idm.javaClass.methods) {
                        if (m.name == "requestDisplayPower" && m.parameterTypes.size == 2) {
                            requestDisplayPowerMethod = m.apply { isAccessible = true }
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            try {
                val sc = Class.forName("android.view.SurfaceControl")
                surfaceControlClass = sc
                setDisplayPowerModeMethod = sc.getDeclaredMethod(
                    "setDisplayPowerMode",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }

                getPhysicalDisplayTokenMethod = runCatching {
                    sc.getDeclaredMethod("getInternalDisplayToken").apply { isAccessible = true }
                }.getOrElse {
                    runCatching {
                        val dcClass = Class.forName("com.android.server.display.DisplayControl")
                        dcClass.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply {
                            isAccessible = true
                        }
                    }.getOrNull()
                }
            } catch (_: Throwable) {
            }

            try {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, Context.WINDOW_SERVICE) as? IBinder
                if (binder != null) {
                    val wm = Class.forName("android.view.IWindowManager\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder)
                    iwmInstance = wm
                    if (wm != null) {
                        for (m in wm.javaClass.methods) {
                            if (m.name == "dismissKeyguard") {
                                dismissKeyguardMethod = m.apply { isAccessible = true }
                                break
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun dismissKeyguardInternal() {
            ensureDisplayPowerReflection()
            val wm = iwmInstance
            val m = dismissKeyguardMethod
            if (wm != null && m != null) {
                try {
                    when (m.parameterTypes.size) {
                        0 -> m.invoke(wm)
                        1 -> m.invoke(wm, null)
                        2 -> m.invoke(wm, null, null)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        private fun ensureHostedVirtualDisplaysPoweredOn() {
            ensureDisplayPowerReflection()
            val idm = idmInstance
            val reqMethod = requestDisplayPowerMethod
            for (entry in hostedDisplays.values) {
                val dispId = entry.virtualDisplay.display?.displayId ?: continue
                if (dispId <= Display.DEFAULT_DISPLAY) continue
                if (idm != null && reqMethod != null) {
                    try {
                        val secondParamType = reqMethod.parameterTypes[1]
                        val arg: Any = if (secondParamType == Boolean::class.javaPrimitiveType) {
                            true
                        } else {
                            Display.STATE_ON
                        }
                        reqMethod.invoke(idm, dispId, arg)
                    } catch (_: Throwable) {
                    }
                }
                if (entry.displayWakeLock?.isHeld != true) {
                    runCatching { entry.displayWakeLock?.acquire() }
                }
            }
        }

        @SuppressLint("PrivateApi")
        private fun relaunchTrackedAppsOnVirtualDisplays() {
            if (activeRelaunchTargets.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRelaunchTimeMs < 1500L) return
            lastRelaunchTimeMs = now
            for ((dispId, target) in activeRelaunchTargets) {
                val (pkgName, clsName) = target
                if (dispId <= Display.DEFAULT_DISPLAY || pkgName.isBlank() || clsName.isBlank()) continue
                val hostedEntry = hostedDisplays.values.firstOrNull { it.virtualDisplay.display?.displayId == dispId }
                    ?: continue
                // Never relaunch the primary app (e.g. ViTV / YouTube) via startActivity when screen turns off,
                // as delivering a new launcher Intent interrupts active MediaCodec/ExoPlayer video streams!
                if (hostedEntry.sessionKey.endsWith("_primary")) continue
                try {
                    val component = ComponentName(pkgName, clsName)
                    val intent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                    val options = ActivityOptions.makeBasic().apply {
                        setLaunchDisplayId(dispId)
                    }.toBundle()
                    val atm = if (Build.VERSION.SDK_INT >= 29) {
                        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                    } else {
                        ActivityManager::class.java.getMethod("getService").invoke(null)
                    } ?: continue
                    for (m in atm.javaClass.methods) {
                        if (m.name != "startActivity") continue
                        val p = m.parameterTypes
                        val args: Array<Any?> = when {
                            p.size == 11 && p[3] == Intent::class.java -> {
                                arrayOf(null, SHELL_PKG, null, intent, null, null, null, 0, 0, null, options)
                            }
                            p.size == 10 && p[2] == Intent::class.java -> {
                                arrayOf(null, SHELL_PKG, intent, null, null, null, 0, 0, null, options)
                            }
                            else -> continue
                        }
                        m.invoke(atm, *args)
                        break
                    }
                } catch (_: Throwable) {
                }
            }
        }

        private fun setPhysicalPanelPower(off: Boolean): Boolean {
            ensureDisplayPowerReflection()
            // 1. Primary path: SurfaceControl.setDisplayPowerMode (direct SurfaceFlinger/HWC panel off)
            // Powers off ONLY the physical OLED/LCD hardware without notifying ActivityTaskManagerService
            // so Display 0 stays STATE_ON logically and media players never receive onPause/onStop!
            val setModeMethod = setDisplayPowerModeMethod
            if (setModeMethod != null) {
                try {
                    val token = resolveInternalDisplayToken()
                    if (token != null) {
                        setModeMethod.invoke(null, token, if (off) POWER_MODE_OFF else POWER_MODE_NORMAL)
                        return true
                    }
                } catch (_: Throwable) {
                }
            }

            // 2. Fallback path: IDisplayManager.requestDisplayPower (Android 12–17+)
            val idm = idmInstance
            val reqMethod = requestDisplayPowerMethod
            if (idm != null && reqMethod != null) {
                try {
                    val secondParamType = reqMethod.parameterTypes[1]
                    val arg: Any = if (secondParamType == Boolean::class.javaPrimitiveType) {
                        !off
                    } else {
                        if (off) Display.STATE_OFF else Display.STATE_UNKNOWN
                    }
                    reqMethod.invoke(idm, Display.DEFAULT_DISPLAY, arg)
                    return true
                } catch (_: Throwable) {
                }
            }
            return false
        }

        private fun resolveInternalDisplayToken(): IBinder? {
            try {
                val tokenMethod = getPhysicalDisplayTokenMethod
                if (tokenMethod != null) {
                    if (tokenMethod.parameterTypes.isEmpty()) {
                        return tokenMethod.invoke(null) as? IBinder
                    } else if (tokenMethod.parameterTypes.size == 1 &&
                        tokenMethod.parameterTypes[0] == Long::class.javaPrimitiveType
                    ) {
                        return tokenMethod.invoke(null, 0L) as? IBinder
                    }
                }
                val sc = surfaceControlClass ?: return null
                val getIds = sc.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }
                val ids = getIds.invoke(null) as? LongArray
                val firstId = ids?.firstOrNull() ?: 0L
                val getToken = sc.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
                return getToken.invoke(null, firstId) as? IBinder
            } catch (_: Throwable) {
                return null
            }
        }

        private fun wakeUpSystemInternal() {
            val powerMgr = pm ?: return
            try {
                try {
                    powerMgr.javaClass.getMethod(
                        "wakeUp",
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    ).invoke(powerMgr, SystemClock.uptimeMillis(), 0, "com.android.shell:AAMaker")
                } catch (_: NoSuchMethodException) {
                    powerMgr.javaClass.getMethod("wakeUp", Long::class.javaPrimitiveType)
                        .invoke(powerMgr, SystemClock.uptimeMillis())
                }
            } catch (_: Throwable) {
            }
        }

        private fun goToSleepInternal() {
            val powerMgr = pm ?: return
            try {
                powerMgr.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
                    .invoke(powerMgr, SystemClock.uptimeMillis())
            } catch (_: Throwable) {
            }
        }

        private fun resolveShellPowerManager(c: Context): PowerManager? {
            return try {
                c.createPackageContext("com.android.shell", 0).getSystemService(PowerManager::class.java)
            } catch (_: Throwable) {
                c.getSystemService(Context.POWER_SERVICE) as? PowerManager
            }
        }
    }

    private class ServerBinder(
        private val context: Context,
        private val mainHandler: Handler
    ) : Binder() {

        private var cachedInputTarget: Any? = null
        private var cachedInjectInputMethod: Method? = null
        private var cachedSetDisplayIdMethod: Method? = null
        private var cachedGetDisplayIdMethod: Method? = null
        private var inputReflectionReady = false
        private val orientationConfiguredDisplays = ConcurrentHashMap.newKeySet<Int>()

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun ensureInputReflection() {
            if (inputReflectionReady) return
            inputReflectionReady = true
            unlockHiddenApiExemptions()
            try {
                val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
                val imgInstance = imgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
                val method = imgClass.getDeclaredMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
                if (imgInstance != null) {
                    cachedInputTarget = imgInstance
                    cachedInjectInputMethod = method
                }
            } catch (_: Throwable) {
            }
            if (cachedInjectInputMethod == null) {
                try {
                    val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
                    val method = InputManager::class.java.getMethod(
                        "injectInputEvent",
                        InputEvent::class.java,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                    if (im != null) {
                        cachedInputTarget = im
                        cachedInjectInputMethod = method
                    }
                } catch (_: Throwable) {
                }
            }
            try {
                cachedSetDisplayIdMethod = InputEvent::class.java.getMethod(
                    "setDisplayId",
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
            } catch (_: Throwable) {
                try {
                    cachedSetDisplayIdMethod = InputEvent::class.java.getDeclaredMethod(
                        "setDisplayId",
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                } catch (_: Throwable) {
                }
            }
            try {
                cachedGetDisplayIdMethod = InputEvent::class.java.getMethod("getDisplayId").apply { isAccessible = true }
            } catch (_: Throwable) {
                try {
                    cachedGetDisplayIdMethod = InputEvent::class.java.getDeclaredMethod("getDisplayId").apply { isAccessible = true }
                } catch (_: Throwable) {
                }
            }
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Only accept calls from UID 2000 (shell), UID 0 (root), or AAMaKer's own UID
            val callingUid = getCallingUid()
            val expectedAppUid = runCatching {
                context.packageManager.getApplicationInfo("com.aamaker.app", 0).uid
            }.getOrDefault(-1)
            if (callingUid != 0 && callingUid != 2000 && callingUid != expectedAppUid) {
                return false
            }

            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TRANSACTION_PING -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeString(SERVICE_PING_NAME)
                    true
                }
                TRANSACTION_CREATE_OR_UPDATE_DISPLAY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val sessionKey = data.readString().orEmpty()
                    val displayName = data.readString().orEmpty()
                    val width = data.readInt().coerceIn(160, 4096)
                    val height = data.readInt().coerceIn(160, 4096)
                    val dpi = data.readInt().coerceIn(100, 640)
                    val hasSurface = data.readInt() != 0
                    val surface = if (hasSurface) Surface.CREATOR.createFromParcel(data) else null

                    val identityToken = clearCallingIdentity()
                    val displayId = try {
                        if (surface != null && surface.isValid && sessionKey.isNotEmpty()) {
                            createOrUpdateHostedDisplay(sessionKey, displayName, width, height, dpi, surface)
                        } else {
                            surface?.release()
                            Display.INVALID_DISPLAY
                        }
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(displayId)
                    true
                }
                TRANSACTION_RELEASE_DISPLAY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val sessionKey = data.readString().orEmpty()
                    val identityToken = clearCallingIdentity()
                    try {
                        releaseHostedDisplay(sessionKey)
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    true
                }
                TRANSACTION_INJECT_MOTION -> {
                    data.enforceInterface(DESCRIPTOR)
                    val displayId = data.readInt()
                    val action = data.readInt()
                    val downTime = data.readLong()
                    val eventTime = data.readLong()
                    val x = data.readFloat()
                    val y = data.readFloat()
                    val identityToken = clearCallingIdentity()
                    val ok = try {
                        injectMotionEventOnDisplay(displayId, action, downTime, eventTime, x, y)
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    true
                }
                TRANSACTION_INJECT_KEY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val displayId = data.readInt()
                    val keyCode = data.readInt()
                    val identityToken = clearCallingIdentity()
                    val ok = try {
                        injectKeyTapOnDisplay(displayId, keyCode)
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    true
                }
                TRANSACTION_INJECT_TAP -> {
                    data.enforceInterface(DESCRIPTOR)
                    val displayId = data.readInt()
                    val x = data.readFloat()
                    val y = data.readFloat()
                    val identityToken = clearCallingIdentity()
                    val ok = try {
                        injectTapGestureOnDisplay(displayId, x, y)
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    true
                }
                TRANSACTION_INJECT_SWIPE -> {
                    data.enforceInterface(DESCRIPTOR)
                    val displayId = data.readInt()
                    val x1 = data.readFloat()
                    val y1 = data.readFloat()
                    val x2 = data.readFloat()
                    val y2 = data.readFloat()
                    val durationMs = data.readLong()
                    val identityToken = clearCallingIdentity()
                    val ok = try {
                        injectSwipeGestureOnDisplay(displayId, x1, y1, x2, y2, durationMs)
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    true
                }
                TRANSACTION_SET_PHONE_PANEL_POWER -> {
                    data.enforceInterface(DESCRIPTOR)
                    val screenOff = data.readInt() != 0
                    val keepSessionAwake = data.readInt() != 0
                    val identityToken = clearCallingIdentity()
                    val ok = try {
                        panelPowerController?.setPhoneScreenOffMode(screenOff, keepSessionAwake) ?: false
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    true
                }
                TRANSACTION_LAUNCH_APP_ON_DISPLAY -> {
                    data.enforceInterface(DESCRIPTOR)
                    val displayId = data.readInt()
                    val pkgName = data.readString().orEmpty()
                    val clsName = data.readString().orEmpty()
                    val identityToken = clearCallingIdentity()
                    val ok = try {
                        launchComponentOnDisplayDirect(displayId, pkgName, clsName)
                    } finally {
                        restoreCallingIdentity(identityToken)
                    }
                    reply?.writeNoException()
                    reply?.writeInt(if (ok) 1 else 0)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        @SuppressLint("PrivateApi")
        private fun launchComponentOnDisplayDirect(
            displayId: Int,
            pkgName: String,
            clsName: String
        ): Boolean {
            if (displayId <= Display.DEFAULT_DISPLAY || pkgName.isBlank() || clsName.isBlank()) return false
            panelPowerController?.recordActiveDisplayTarget(displayId, pkgName, clsName)
            val component = ComponentName(pkgName, clsName)
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            val options = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(displayId)
            }.toBundle()

            return try {
                val atm = if (Build.VERSION.SDK_INT >= 29) {
                    Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                } else {
                    ActivityManager::class.java.getMethod("getService").invoke(null)
                } ?: return false

                // If pkgName (e.g., Google Maps singleTask) is already running on Display 0 or another display,
                // move its existing root task directly onto displayId so Android doesn't silently resume it on Display 0!
                tryMoveExistingTaskToDisplay(atm, pkgName, displayId)

                for (m in atm.javaClass.methods) {
                    if (m.name != "startActivity") continue
                    val p = m.parameterTypes
                    val args: Array<Any?> = when {
                        p.size == 11 && p[3] == Intent::class.java -> {
                            arrayOf(null, SHELL_PKG, null, intent, null, null, null, 0, 0, null, options)
                        }
                        p.size == 10 && p[2] == Intent::class.java -> {
                            arrayOf(null, SHELL_PKG, intent, null, null, null, 0, 0, null, options)
                        }
                        else -> continue
                    }
                    val res = (m.invoke(atm, *args) as? Int) ?: -1
                    if (res >= 0) {
                        tryMoveExistingTaskToDisplay(atm, pkgName, displayId)
                        return true
                    }
                }
                false
            } catch (_: Throwable) {
                false
            }
        }

        @SuppressLint("PrivateApi")
        private fun tryMoveExistingTaskToDisplay(atm: Any, pkgName: String, targetDisplayId: Int) {
            try {
                val getTasksMethod = atm.javaClass.methods.firstOrNull { m ->
                    m.name == "getTasks" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
                } ?: atm.javaClass.methods.firstOrNull { m ->
                    m.name == "getTasks" && m.parameterTypes.size == 3
                } ?: return

                val tasks = when (getTasksMethod.parameterTypes.size) {
                    1 -> getTasksMethod.invoke(atm, 25) as? List<*>
                    3 -> getTasksMethod.invoke(atm, 25, false, false) as? List<*>
                    else -> null
                } ?: return

                val moveMethod = atm.javaClass.methods.firstOrNull { m ->
                    (m.name == "moveRootTaskToDisplay" || m.name == "moveStackToDisplay") &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        m.parameterTypes[1] == Int::class.javaPrimitiveType
                } ?: return

                for (task in tasks) {
                    if (task == null) continue
                    val topComp = runCatching {
                        task.javaClass.getField("topActivity").get(task) as? ComponentName
                    }.getOrNull()
                    val baseComp = runCatching {
                        task.javaClass.getField("baseActivity").get(task) as? ComponentName
                    }.getOrNull()
                    val matchesPkg = topComp?.packageName == pkgName || baseComp?.packageName == pkgName
                    if (!matchesPkg) continue

                    val taskDisplayId = runCatching {
                        task.javaClass.getField("displayId").getInt(task)
                    }.getOrDefault(-1)
                    if (taskDisplayId == targetDisplayId) continue

                    val taskId = runCatching {
                        task.javaClass.getField("taskId").getInt(task)
                    }.getOrElse {
                        runCatching { task.javaClass.getField("id").getInt(task) }.getOrDefault(-1)
                    }
                    if (taskId > 0) {
                        runCatching { moveMethod.invoke(atm, taskId, targetDisplayId) }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun createOrUpdateHostedDisplay(
            sessionKey: String,
            displayName: String,
            width: Int,
            height: Int,
            dpi: Int,
            newSurface: Surface
        ): Int {
            val dm = createShellDisplayManager(context)
                ?: return Display.INVALID_DISPLAY

            val existing = hostedDisplays[sessionKey]
            if (existing != null) {
                val existingDisp = existing.virtualDisplay.display
                if (existingDisp != null && existingDisp.displayId > Display.DEFAULT_DISPLAY) {
                    return try {
                        existing.virtualDisplay.resize(width, height, dpi)
                        existing.virtualDisplay.surface = newSurface
                        if (existing.surface !== newSurface) {
                            runCatching { existing.surface.release() }
                        }
                        existing.surface = newSurface
                        existing.width = width
                        existing.height = height
                        existing.dpi = dpi
                        configureVirtualDisplayPolicies(existingDisp.displayId)
                        panelPowerController?.onHostedDisplaysChanged(hostedDisplays.isNotEmpty())
                        existingDisp.displayId
                    } catch (_: Throwable) {
                        releaseHostedDisplay(sessionKey)
                        createFreshVirtualDisplay(dm, sessionKey, displayName, width, height, dpi, newSurface)
                    }
                } else {
                    releaseHostedDisplay(sessionKey)
                }
            }

            return createFreshVirtualDisplay(dm, sessionKey, displayName, width, height, dpi, newSurface)
        }

        private fun createFreshVirtualDisplay(
            dm: DisplayManager,
            sessionKey: String,
            displayName: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface
        ): Int {
            val safeName = displayName.ifBlank { "AAMakerSplitDisplay_$sessionKey" }
            val sdk = Build.VERSION.SDK_INT
            // Do NOT set VIRTUAL_DISPLAY_FLAG_PRESENTATION on secondary split displays so 3rd-party
            // Presentation/ADAS/HUD apps (e.g. DiCam ADAS) cannot auto-hijack the secondary display!
            val includePresentation = sessionKey.endsWith("_primary")
            val presentationFlag = if (includePresentation) DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION else 0
            val baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                presentationFlag or
                VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT

            val fallbackFlagsList = intArrayOf(
                if (sdk >= 34) {
                    baseFlags or
                        VIRTUAL_DISPLAY_FLAG_TRUSTED or
                        VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                        VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                        VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED or
                        VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                } else {
                    baseFlags or
                        VIRTUAL_DISPLAY_FLAG_TRUSTED or
                        VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                        VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                        VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
                },
                if (sdk >= 34) {
                    baseFlags or
                        VIRTUAL_DISPLAY_FLAG_TRUSTED or
                        VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                        VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED or
                        VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                } else {
                    baseFlags or
                        VIRTUAL_DISPLAY_FLAG_TRUSTED or
                        VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                        VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
                },
                baseFlags or VIRTUAL_DISPLAY_FLAG_TRUSTED or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED,
                baseFlags or VIRTUAL_DISPLAY_FLAG_TRUSTED,
                baseFlags,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )

            for (flags in fallbackFlagsList) {
                try {
                    val vd = dm.createVirtualDisplay(
                        safeName,
                        width,
                        height,
                        dpi,
                        surface,
                        flags,
                        null,
                        mainHandler
                    )
                    val dispId = vd?.display?.displayId ?: Display.INVALID_DISPLAY
                    if (vd != null && dispId > Display.DEFAULT_DISPLAY) {
                        val dispWakeLock = acquireDisplaySpecificWakeLock(dispId, sessionKey)
                        hostedDisplays[sessionKey] = HostedDisplayEntry(
                            sessionKey = sessionKey,
                            virtualDisplay = vd,
                            surface = surface,
                            width = width,
                            height = height,
                            dpi = dpi,
                            displayWakeLock = dispWakeLock
                        )
                        configureVirtualDisplayPolicies(dispId)
                        panelPowerController?.onHostedDisplaysChanged(true)
                        return dispId
                    }
                    vd?.release()
                } catch (_: Throwable) {
                }
            }
            runCatching { surface.release() }
            return Display.INVALID_DISPLAY
        }

        @SuppressLint("WakelockTimeout")
        private fun acquireDisplaySpecificWakeLock(displayId: Int, sessionKey: String): PowerManager.WakeLock? {
            return try {
                val pm = context.createPackageContext("com.android.shell", 0)
                    .getSystemService(PowerManager::class.java)
                    ?: (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?: return null
                val m = PowerManager::class.java.getMethod(
                    "newWakeLock",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
                @Suppress("DEPRECATION")
                val wl = m.invoke(
                    pm,
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "com.android.shell:AAMakerDisplay_$sessionKey",
                    displayId
                ) as? PowerManager.WakeLock
                wl?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Configures IWindowManager policies on the VirtualDisplay so portrait-only apps never rotate/shrink
         * the landscape car pane, and IME opens on the VirtualDisplay when possible.
         */
        @SuppressLint("PrivateApi")
        private fun configureVirtualDisplayPolicies(displayId: Int) {
            if (!orientationConfiguredDisplays.add(displayId)) return
            try {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, Context.WINDOW_SERVICE) as? IBinder ?: return
                val wm = Class.forName("android.view.IWindowManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder) ?: return

                for (m in wm.javaClass.methods) {
                    val p = m.parameterTypes
                    try {
                        when (m.name) {
                            "setIgnoreOrientationRequest" -> {
                                if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Boolean::class.javaPrimitiveType) {
                                    m.invoke(wm, displayId, true)
                                }
                            }
                            "setFixedToUserRotation" -> {
                                if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType) {
                                    m.invoke(wm, displayId, 2)
                                }
                            }
                            "freezeDisplayRotation" -> {
                                if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType) {
                                    m.invoke(wm, displayId, 0)
                                } else if (p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType && p[2] == String::class.java) {
                                    m.invoke(wm, displayId, 0, "AAMaKer")
                                }
                            }
                            "setShouldShowWithInsecureKeyguard" -> {
                                if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Boolean::class.javaPrimitiveType) {
                                    m.invoke(wm, displayId, true)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun releaseHostedDisplay(sessionKey: String) {
            if (sessionKey == "ALL") {
                val keys = hostedDisplays.keys.toList()
                for (k in keys) {
                    releaseSingleHostedDisplay(k)
                }
                panelPowerController?.onHostedDisplaysChanged(false)
                return
            }
            releaseSingleHostedDisplay(sessionKey)
            panelPowerController?.onHostedDisplaysChanged(hostedDisplays.isNotEmpty())
        }

        private fun releaseSingleHostedDisplay(sessionKey: String) {
            val removed = hostedDisplays.remove(sessionKey) ?: return
            val dispId = runCatching { removed.virtualDisplay.display?.displayId ?: -1 }.getOrDefault(-1)
            if (dispId > 0) {
                orientationConfiguredDisplays.remove(dispId)
            }
            runCatching {
                if (removed.displayWakeLock?.isHeld == true) {
                    removed.displayWakeLock?.release()
                }
            }
            runCatching { removed.virtualDisplay.surface = null }
            runCatching { removed.virtualDisplay.release() }
            runCatching { removed.surface.release() }
        }

        private fun setAndVerifyDisplayId(event: InputEvent, displayId: Int): Boolean {
            val setMethod = cachedSetDisplayIdMethod ?: return false
            return try {
                setMethod.invoke(event, displayId)
                val getMethod = cachedGetDisplayIdMethod
                if (getMethod != null) {
                    (getMethod.invoke(event) as? Int) == displayId
                } else {
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }

        private fun invokeInjectInputEvent(event: InputEvent, mode: Int): Boolean {
            val target = cachedInputTarget ?: return false
            val injectMethod = cachedInjectInputMethod ?: return false
            return try {
                val ok = (injectMethod.invoke(target, event, mode) as? Boolean) == true
                if (!ok && mode != 0) {
                    (injectMethod.invoke(target, event, 0) as? Boolean) == true
                } else {
                    ok
                }
            } catch (_: Throwable) {
                if (mode != 0) {
                    try {
                        (injectMethod.invoke(target, event, 0) as? Boolean) == true
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    false
                }
            }
        }

        private fun injectMotionEventOnDisplay(
            displayId: Int,
            action: Int,
            downTime: Long,
            eventTime: Long,
            x: Float,
            y: Float,
            syncMode: Int = 0
        ): Boolean {
            if (displayId <= Display.DEFAULT_DISPLAY) return false
            if (action == MotionEvent.ACTION_DOWN) {
                panelPowerController?.ensureDisplaysReadyForInput()
            }
            ensureInputReflection()
            if (cachedInputTarget == null || cachedInjectInputMethod == null || cachedSetDisplayIdMethod == null) {
                return false
            }

            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
                    size = 1f
                }
            )
            val now = SystemClock.uptimeMillis()
            val safeDown = if (downTime > 0L) downTime else now
            val safeEvent = max(safeDown, if (eventTime > 0L) eventTime else now)

            val ev = MotionEvent.obtain(
                safeDown,
                safeEvent,
                action,
                1,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            return try {
                if (!setAndVerifyDisplayId(ev, displayId)) {
                    return false
                }
                invokeInjectInputEvent(ev, syncMode)
            } catch (_: Throwable) {
                false
            } finally {
                ev.recycle()
            }
        }

        private fun injectTapGestureOnDisplay(displayId: Int, x: Float, y: Float): Boolean {
            if (displayId <= Display.DEFAULT_DISPLAY) return false
            panelPowerController?.ensureDisplaysReadyForInput()
            val now = SystemClock.uptimeMillis()
            // Use async mode (0) so InputDispatcher never blocks the Binder thread if Display 0 is STATE_OFF!
            val downOk = injectMotionEventOnDisplay(
                displayId = displayId,
                action = MotionEvent.ACTION_DOWN,
                downTime = now,
                eventTime = now,
                x = x,
                y = y,
                syncMode = 0
            )
            try {
                Thread.sleep(14L)
            } catch (_: InterruptedException) {
            }
            val upTime = SystemClock.uptimeMillis().coerceAtLeast(now + 14L)
            val upOk = injectMotionEventOnDisplay(
                displayId = displayId,
                action = MotionEvent.ACTION_UP,
                downTime = now,
                eventTime = upTime,
                x = x,
                y = y,
                syncMode = 0
            )
            return downOk && upOk
        }

        private fun injectSwipeGestureOnDisplay(
            displayId: Int,
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long
        ): Boolean {
            if (displayId <= Display.DEFAULT_DISPLAY) return false
            panelPowerController?.ensureDisplaysReadyForInput()
            val downTime = SystemClock.uptimeMillis()
            if (!injectMotionEventOnDisplay(displayId, MotionEvent.ACTION_DOWN, downTime, downTime, x1, y1, syncMode = 0)) {
                return false
            }
            val steps = 8
            val safeDuration = durationMs.coerceIn(40L, 600L)
            val stepSleep = (safeDuration / steps).coerceAtLeast(5L)
            for (i in 1..steps) {
                try {
                    Thread.sleep(stepSleep)
                } catch (_: InterruptedException) {
                }
                val t = i.toFloat() / steps.toFloat()
                val cx = x1 + (x2 - x1) * t
                val cy = y1 + (y2 - y1) * t
                val evTime = SystemClock.uptimeMillis()
                injectMotionEventOnDisplay(displayId, MotionEvent.ACTION_MOVE, downTime, evTime, cx, cy, syncMode = 0)
            }
            val upTime = SystemClock.uptimeMillis()
            return injectMotionEventOnDisplay(displayId, MotionEvent.ACTION_UP, downTime, upTime, x2, y2, syncMode = 0)
        }

        private fun injectKeyTapOnDisplay(displayId: Int, keyCode: Int): Boolean {
            if (displayId <= Display.DEFAULT_DISPLAY) return false
            panelPowerController?.ensureDisplaysReadyForInput()
            ensureInputReflection()
            if (cachedInputTarget == null || cachedInjectInputMethod == null || cachedSetDisplayIdMethod == null) {
                return false
            }
            val now = SystemClock.uptimeMillis()

            val downEv = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            val upEv = KeyEvent(now, now + 15L, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            return try {
                if (!setAndVerifyDisplayId(downEv, displayId) || !setAndVerifyDisplayId(upEv, displayId)) {
                    return false
                }
                val okDown = invokeInjectInputEvent(downEv, 0)
                val okUp = invokeInjectInputEvent(upEv, 0)
                okDown && okUp
            } catch (_: Throwable) {
                false
            }
        }
    }
}
