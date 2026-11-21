package com.aamaker.app.stage

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService (UID 2000 / shell) or In-Process Direct Mode server for Car Stage.
 *
 * Key capabilities:
 * 1. Android 14+ VirtualDeviceManager host (independent display group / focus / lifecycle),
 *    with multi-tier flag fallback to trusted DisplayManager VirtualDisplay using ShellContext.
 * 2. Zero-reload task movement via IActivityTaskManager.moveRootTaskToDisplay + direct IActivityTaskManager.startActivity.
 * 3. Fixed landscape display rotation so portrait-only apps are letterboxed instead of rotating the car display.
 */
class CarStageDisplayServer(
    private val context: Context? = null,
    private val inProcess: Boolean = false
) : ICarStageService.Stub() {

    constructor() : this(null, false)
    constructor(base: Context?) : this(base, false)

    companion object {
        private const val TAG = "CarStageServer"
        private const val DISPLAY_NAME = "AAMaKerCarStage"
        private const val SHELL_PKG = "com.android.shell"

        // Hidden DisplayManager.VIRTUAL_DISPLAY_FLAG_* constants
        private const val FLAG_PUBLIC = 1
        private const val FLAG_PRESENTATION = 1 shl 1
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_BASE = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH

        private const val PROVIDER_AUTHORITY = "com.aamaker.app.dualbridge"
        private const val METHOD_REGISTER_SERVER = "register_car_stage_server"

        /**
         * Entry point when launched directly via Local Wireless ADB `app_process` (UID 2000)
         * as a complete replacement for Shizuku UserService.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            unlockHiddenApiExemptions()
            initApplicationSharedMemoryIfNeeded()
            val nonce = args.firstOrNull()
            try {
                @Suppress("DEPRECATION")
                android.os.Looper.prepareMainLooper()
            } catch (_: Throwable) {
                if (android.os.Looper.myLooper() == null) {
                    android.os.Looper.prepare()
                }
            }
            val server = CarStageDisplayServer(null, false)
            // Eagerly initialize ActivityThread.systemMain(), ShellContext, and DisplayManager
            // on the Main Thread (where Looper is prepared) so Binder worker threads never hit
            // "Can't create handler inside thread that has not called Looper.prepare()".
            server.resolveContext()
            server.resolveDisplayManager()
            Thread({
                publishBinderToAppProvider(server, nonce)
            }, "CarStage-BinderPublish").apply {
                isDaemon = true
                start()
            }
            android.os.Looper.loop()
        }

        internal fun initApplicationSharedMemoryIfNeeded() {
            try {
                val asmClass = Class.forName("android.app.ApplicationSharedMemory")
                val getInstance = runCatching {
                    asmClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
                }.getOrNull()
                if (runCatching { getInstance?.invoke(null) }.getOrNull() != null) return
                val createMethod = asmClass.getDeclaredMethod("create").apply { isAccessible = true }
                val setInstanceMethod = asmClass.getDeclaredMethod("setInstance", asmClass).apply { isAccessible = true }
                val instance = createMethod.invoke(null)
                if (instance != null) {
                    setInstanceMethod.invoke(null, instance)
                }
            } catch (_: Throwable) {
            }
        }

        private fun publishBinderToAppProvider(server: CarStageDisplayServer, nonce: String?) {
            val bundle = Bundle().apply {
                putBinder("server_binder", server.asBinder())
                if (!nonce.isNullOrEmpty()) {
                    putString("handshake_nonce", nonce)
                }
            }
            for (attempt in 1..3) {
                if (callProviderViaActivityManager(PROVIDER_AUTHORITY, METHOD_REGISTER_SERVER, bundle)) {
                    Log.i(TAG, "Published CarStageDisplayServer binder via IActivityManager (attempt=$attempt)")
                    return
                }
                val ctx = server.resolveContext()
                if (ctx != null) {
                    val ok = runCatching {
                        val uri = android.net.Uri.parse("content://$PROVIDER_AUTHORITY")
                        val reply = ctx.contentResolver.call(uri, METHOD_REGISTER_SERVER, null, bundle)
                        reply?.getBoolean("ok") == true
                    }.getOrDefault(false)
                    if (ok) {
                        Log.i(TAG, "Published CarStageDisplayServer binder via ContentResolver (attempt=$attempt)")
                        return
                    }
                }
                if (attempt < 3) {
                    runCatching { Thread.sleep(250L) }
                }
            }
            Log.e(TAG, "Failed to publish CarStageDisplayServer binder after retries")
        }

        internal fun callProviderViaActivityManager(
            authority: String,
            method: String,
            extras: Bundle
        ): Boolean {
            val am = try {
                ActivityManager::class.java.getMethod("getService").invoke(null)
            } catch (_: Throwable) {
                null
            } ?: return false

            val token = android.os.Binder()
            var holder: Any? = null
            return try {
                val getCpe = am.javaClass.methods.firstOrNull {
                    it.name == "getContentProviderExternal" && it.parameterTypes.size == 4
                } ?: am.javaClass.methods.firstOrNull {
                    it.name == "getContentProviderExternal" && it.parameterTypes.size == 3
                }
                holder = when (getCpe?.parameterTypes?.size) {
                    4 -> getCpe.invoke(am, authority, 0, token, "*cmd*")
                    3 -> getCpe.invoke(am, authority, 0, token)
                    else -> null
                } ?: return false

                val provider = holder.javaClass.getField("provider").get(holder) ?: return false
                for (m in provider.javaClass.methods) {
                    if (m.name != "call") continue
                    val p = m.parameterTypes
                    val reply: Bundle? = try {
                        when {
                            Build.VERSION.SDK_INT >= 31 && p.size == 5 &&
                                p[0].name == "android.content.AttributionSource" -> {
                                val attr = android.content.AttributionSource.Builder(Process.myUid())
                                    .setPackageName(SHELL_PKG)
                                    .build()
                                m.invoke(provider, attr, authority, method, null, extras) as? Bundle
                            }
                            p.size == 6 && p[0] == String::class.java -> {
                                m.invoke(provider, SHELL_PKG, null, authority, method, null, extras) as? Bundle
                            }
                            p.size == 5 && p[0] == String::class.java -> {
                                m.invoke(provider, SHELL_PKG, authority, method, null, extras) as? Bundle
                            }
                            p.size == 4 && p[0] == String::class.java -> {
                                m.invoke(provider, SHELL_PKG, method, null, extras) as? Bundle
                            }
                            else -> continue
                        }
                    } catch (_: Throwable) {
                        continue
                    }
                    if (reply?.getBoolean("ok") == true) {
                        return true
                    }
                }
                false
            } catch (t: Throwable) {
                Log.w(TAG, "getContentProviderExternal($authority) failed: ${t.message}")
                false
            } finally {
                if (holder != null) {
                    runCatching {
                        val removeMethod = am.javaClass.methods.firstOrNull {
                            it.name == "removeContentProviderExternalAsUser" && it.parameterTypes.size == 3
                        } ?: am.javaClass.methods.firstOrNull {
                            it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2
                        }
                        when (removeMethod?.parameterTypes?.size) {
                            3 -> removeMethod.invoke(am, authority, token, 0)
                            2 -> removeMethod.invoke(am, authority, token)
                        }
                    }
                }
            }
        }

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
    }

    private var cachedShellContext: Context? = null
    private var cachedDisplayManager: DisplayManager? = null

    private var virtualDeviceObj: Any? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var secondaryVirtualDisplay: VirtualDisplay? = null

    @Volatile
    private var activeDisplayId: Int = -1
    @Volatile
    private var primaryBaseWidth: Int = 0
    @Volatile
    private var primaryBaseHeight: Int = 0

    @Volatile
    private var secondaryDisplayId: Int = -1
    @Volatile
    private var secondaryBaseWidth: Int = 0
    @Volatile
    private var secondaryBaseHeight: Int = 0

    @Volatile
    private var imeOnPhone: Boolean = false

    @Volatile
    private var keepPlaybackAlive: Boolean = true

    @Volatile
    private var hasActiveCarSurface: Boolean = false

    @Volatile
    private var lastPrimaryPkg: String = ""

    @Volatile
    private var lastSecondaryPkg: String = ""

    @Volatile
    private var powerController: CarStagePowerController? = null

    private val bgWhitelistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CarStage-Whitelist").apply { isDaemon = true }
    }

    @Volatile
    private var lastTappedDisplayId: Int = -1

    @Volatile
    private var engineMode: String = "STOPPED"

    @Volatile
    private var lastError: String = ""

    private val clientDeathLock = Any()
    private var linkedClientToken: IBinder? = null
    private val clientDeathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Host app process died; releasing Car Stage displays and exiting")
        destroy()
    }

    init {
        unlockHiddenApiExemptions()
        if (!inProcess) {
            killStaleSiblingProcesses()
        }
    }

    override fun registerClientToken(token: IBinder?) {
        if (inProcess) return
        synchronized(clientDeathLock) {
            linkedClientToken?.let { old ->
                runCatching { old.unlinkToDeath(clientDeathRecipient, 0) }
            }
            linkedClientToken = token
            if (token != null) {
                try {
                    token.linkToDeath(clientDeathRecipient, 0)
                    Log.i(TAG, "Linked DeathRecipient to host app client token")
                } catch (t: Throwable) {
                    Log.w(TAG, "Client token already dead while linking; exiting", t)
                    destroy()
                }
            }
        }
    }

    /**
     * Scans /proc for any older lingering `:car_stage` or `aamaker_car_stage` processes from previous runs/crashes
     * and terminates them so at most one CarStageDisplayServer instance can exist on the device.
     */
    private fun killStaleSiblingProcesses() {
        val myPid = Process.myPid()
        val targetSuffix = ":car_stage"
        val procDir = File("/proc")
        val entries = procDir.listFiles() ?: return
        var killed = 0
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            val cmdline = runCatching {
                File(entry, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull() ?: continue
            val isStaleSibling = (cmdline.contains("com.aamaker.app") && cmdline.endsWith(targetSuffix)) ||
                cmdline.startsWith("aamaker_car_stage") ||
                (cmdline.contains("app_process") && cmdline.contains("CarStageDisplayServer"))
            if (isStaleSibling) {
                runCatching {
                    Process.killProcess(pid)
                    killed++
                    Log.w(TAG, "Killed stale zombie car_stage process pid=$pid ($cmdline)")
                }
            }
        }
        if (killed > 0) {
            Log.i(TAG, "Cleaned up $killed stale car_stage process(es)")
        }
    }

    override fun startStageDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        preferVirtualDevice: Boolean
    ): Int {
        stopStageDisplay()
        lastError = ""
        hasActiveCarSurface = surface != null
        whitelistPackageForScreenOff("com.aamaker.app")

        val safeW = width.coerceIn(160, 3840)
        val safeH = height.coerceIn(160, 2160)
        val safeDpi = dpi.coerceIn(110, 640)

        // Primary engine: PUBLIC | TRUSTED VirtualDisplay with OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | OWN_FOCUS
        // (same architecture as scrcpy --new-display & Shizuku AnyAuto DisplayService so 100% of 3rd-party
        // apps are permitted by ActivityTaskSupervisor to launch directly onto the Android Auto surface
        // without ever redirecting back to Display 0!)
        val trustedId = tryCreateTrustedVirtualDisplay(surface, safeW, safeH, safeDpi)
        if (trustedId > 0) {
            activeDisplayId = trustedId
            primaryBaseWidth = safeW
            primaryBaseHeight = safeH
            engineMode = "TRUSTED_DISPLAY"
            keepDisplayLandscape(trustedId)
            applyDisplayImePolicy(trustedId, imeOnPhone)
            ensurePowerController()?.onStageStateChanged(
                active = hasActiveCarSurface,
                keepAwake = keepPlaybackAlive,
                imeOnPhone = imeOnPhone
            )
            Log.i(TAG, "Started Car Stage via Trusted VirtualDisplay: displayId=$trustedId")
            return trustedId
        }

        if (preferVirtualDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val vdId = tryCreateViaVirtualDeviceManager(surface, safeW, safeH, safeDpi)
            if (vdId > 0) {
                activeDisplayId = vdId
                primaryBaseWidth = safeW
                primaryBaseHeight = safeH
                engineMode = "VIRTUAL_DEVICE"
                keepDisplayLandscape(vdId)
                applyDisplayImePolicy(vdId, imeOnPhone)
                ensurePowerController()?.onStageStateChanged(
                    active = hasActiveCarSurface,
                    keepAwake = keepPlaybackAlive,
                    imeOnPhone = imeOnPhone
                )
                Log.i(TAG, "Started Car Stage via VirtualDeviceManager: displayId=$vdId")
                return vdId
            }
        }

        hasActiveCarSurface = false
        engineMode = "STOPPED"
        return -1
    }

    override fun stopStageDisplay() {
        hasActiveCarSurface = false
        powerController?.onStageStateChanged(active = false, keepAwake = keepPlaybackAlive, imeOnPhone = imeOnPhone)
        stopSecondaryDisplay()
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing virtualDisplay", t)
        } finally {
            virtualDisplay = null
        }

        try {
            virtualDeviceObj?.let { vd ->
                vd.javaClass.getMethod("close").invoke(vd)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing virtualDevice", t)
        } finally {
            virtualDeviceObj = null
        }

        activeDisplayId = -1
        engineMode = "STOPPED"
    }

    override fun launchOrMoveApp(packageName: String?, moveExistingTask: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        val targetDisplayId = activeDisplayId
        if (targetDisplayId <= 0) {
            lastError = "Stage display is not active"
            return false
        }
        lastPrimaryPkg = packageName
        whitelistPackageForScreenOff(packageName)
        ensurePowerController()?.ensureVirtualDisplaysAwake()
        return launchOrMovePackageToDisplay(packageName, targetDisplayId, moveExistingTask)
    }

    override fun getActiveDisplayId(): Int = activeDisplayId

    override fun getEngineMode(): String = engineMode

    override fun getTopTaskOrientation(): Int {
        val targetDisplayId = activeDisplayId
        if (targetDisplayId <= 0) return 0
        return try {
            val ctx = resolveContext() ?: return 0
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return 0
            val display = dm.getDisplay(targetDisplayId) ?: return 0
            when (display.rotation) {
                Surface.ROTATION_90, Surface.ROTATION_270 -> 2 // landscape
                else -> 1 // portrait
            }
        } catch (_: Throwable) {
            0
        }
    }

    override fun getLastError(): String = lastError

    override fun sendBackKey(): Boolean {
        val targetDisplayId = activeDisplayId
        if (targetDisplayId <= 0) return false
        powerController?.onCarInteraction()
        return try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(
                now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            val up = KeyEvent(
                now, now + 15, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
            )
            injectEventOnDisplay(down, targetDisplayId)
            injectEventOnDisplay(up, targetDisplayId)
            true
        } catch (t: Throwable) {
            lastError = "Back key injection failed: ${t.message}"
            false
        }
    }

    override fun injectTap(x: Float, y: Float): Boolean {
        return injectTapOnDisplay(activeDisplayId, x, y)
    }

    override fun startSecondaryDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int
    ): Int {
        stopSecondaryDisplay()
        val safeW = width.coerceIn(160, 3840)
        val safeH = height.coerceIn(160, 2160)
        val safeDpi = dpi.coerceIn(110, 640)
        val id = tryCreateTrustedVirtualDisplayInternal("${DISPLAY_NAME}_2", surface, safeW, safeH, safeDpi) { vd ->
            secondaryVirtualDisplay = vd
        }
        secondaryDisplayId = id
        if (id > 0) {
            secondaryBaseWidth = safeW
            secondaryBaseHeight = safeH
            keepDisplayLandscape(id)
            applyDisplayImePolicy(id, imeOnPhone)
            ensurePowerController()?.ensureVirtualDisplaysAwake()
        }
        Log.i(TAG, "Started Secondary Car Stage display: displayId=$id (${safeW}x${safeH}@${safeDpi}dpi)")
        return id
    }

    override fun resizeStageDisplay(width: Int, height: Int, dpi: Int): Boolean {
        val vd = virtualDisplay ?: return false
        val id = activeDisplayId
        if (id <= 0) return false
        val safeW = width.coerceIn(160, 3840)
        val safeH = height.coerceIn(160, 2160)
        val safeDpi = dpi.coerceIn(110, 640)
        return try {
            vd.resize(safeW, safeH, safeDpi)
            primaryBaseWidth = safeW
            primaryBaseHeight = safeH
            keepDisplayLandscape(id)
            Log.i(TAG, "Resized Primary Car Stage display $id in-place -> ${safeW}x${safeH}@${safeDpi}dpi")
            true
        } catch (t: Throwable) {
            lastError = "Resize primary display failed: ${t.message}"
            Log.w(TAG, lastError, t)
            false
        }
    }

    override fun resizeSecondaryDisplay(width: Int, height: Int, dpi: Int): Boolean {
        val vd = secondaryVirtualDisplay ?: return false
        val id = secondaryDisplayId
        if (id <= 0) return false
        val safeW = width.coerceIn(160, 3840)
        val safeH = height.coerceIn(160, 2160)
        val safeDpi = dpi.coerceIn(110, 640)
        return try {
            vd.resize(safeW, safeH, safeDpi)
            secondaryBaseWidth = safeW
            secondaryBaseHeight = safeH
            keepDisplayLandscape(id)
            Log.i(TAG, "Resized Secondary Car Stage display $id in-place -> ${safeW}x${safeH}@${safeDpi}dpi")
            true
        } catch (t: Throwable) {
            lastError = "Resize secondary display failed: ${t.message}"
            Log.w(TAG, lastError, t)
            false
        }
    }

    override fun stopSecondaryDisplay() {
        val oldSecId = secondaryDisplayId
        try {
            secondaryVirtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing secondaryVirtualDisplay", t)
        } finally {
            secondaryVirtualDisplay = null
            secondaryDisplayId = -1
            if (oldSecId > 0) {
                powerController?.releaseDisplayWakeLock(oldSecId)
            }
        }
    }

    override fun launchOrMoveSecondaryApp(packageName: String?, moveExistingTask: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        val targetDisplayId = secondaryDisplayId
        if (targetDisplayId <= 0) {
            lastError = "Secondary stage display is not active"
            return false
        }
        lastSecondaryPkg = packageName
        whitelistPackageForScreenOff(packageName)
        ensurePowerController()?.ensureVirtualDisplaysAwake()
        return launchOrMovePackageToDisplay(packageName, targetDisplayId, moveExistingTask)
    }

    override fun injectTapOnSecondary(x: Float, y: Float): Boolean {
        return injectTapOnDisplay(secondaryDisplayId, x, y)
    }

    /**
     * Maps buffer coordinates (0..baseW, 0..baseH) to the display's actual logical layerStack space
     * in case an OEM ROM (e.g. Xiaomi HyperOS / MIUI) applies a logical override DisplayInfo size
     * with SurfaceFlinger letterbox/pillarbox projection!
     */
    private fun mapBufferToLogicalDisplayCoords(
        displayId: Int,
        bufX: Float,
        bufY: Float,
        baseW: Int,
        baseH: Int
    ): Pair<Float, Float> {
        if (displayId <= 0 || baseW <= 0 || baseH <= 0) return bufX to bufY
        return try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null) ?: return bufX to bufY
            val info = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(dmg, displayId) ?: return bufX to bufY
            val logicalW = info.javaClass.getField("logicalWidth").getInt(info)
            val logicalH = info.javaClass.getField("logicalHeight").getInt(info)
            if (logicalW <= 0 || logicalH <= 0 || (logicalW == baseW && logicalH == baseH)) {
                return bufX to bufY
            }
            // SurfaceFlinger fits logicalW x logicalH inside baseW x baseH preserving aspect ratio (centered)
            val scale = minOf(baseW.toFloat() / logicalW.toFloat(), baseH.toFloat() / logicalH.toFloat())
            val projW = logicalW * scale
            val projH = logicalH * scale
            val offsetX = (baseW - projW) * 0.5f
            val offsetY = (baseH - projH) * 0.5f
            val lx = (((bufX - offsetX) / projW).coerceIn(0f, 1f) * (logicalW - 1))
            val ly = (((bufY - offsetY) / projH).coerceIn(0f, 1f) * (logicalH - 1))
            lx to ly
        } catch (_: Throwable) {
            bufX to bufY
        }
    }

    override fun injectMotion(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        isSecondary: Boolean
    ): Boolean {
        val targetDisplayId = if (isSecondary) secondaryDisplayId else activeDisplayId
        if (targetDisplayId <= 0) return false
        if (action == MotionEvent.ACTION_DOWN) {
            powerController?.onCarInteraction()
        }
        val baseW = if (isSecondary) secondaryBaseWidth else primaryBaseWidth
        val baseH = if (isSecondary) secondaryBaseHeight else primaryBaseHeight
        val (lx, ly) = mapBufferToLogicalDisplayCoords(targetDisplayId, x, y, baseW, baseH)
        return try {
            val now = SystemClock.uptimeMillis()
            val dt = if (downTime > 0L) downTime else now
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = lx
                    this.y = ly
                    pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1f
                    size = 1f
                }
            )
            val ev = MotionEvent.obtain(
                dt, now, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            injectEventOnDisplay(ev, targetDisplayId)
            ev.recycle()
            true
        } catch (t: Throwable) {
            lastError = "Motion injection failed: ${t.message}"
            false
        }
    }

    private fun injectTapOnDisplay(targetDisplayId: Int, x: Float, y: Float): Boolean {
        if (targetDisplayId <= 0) return false
        powerController?.onCarInteraction()
        val isSec = targetDisplayId == secondaryDisplayId
        val baseW = if (isSec) secondaryBaseWidth else primaryBaseWidth
        val baseH = if (isSec) secondaryBaseHeight else primaryBaseHeight
        val (lx, ly) = mapBufferToLogicalDisplayCoords(targetDisplayId, x, y, baseW, baseH)
        if (targetDisplayId != lastTappedDisplayId) {
            lastTappedDisplayId = targetDisplayId
            // Exclusively assign IME ownership to the tapped display so Android's
            // InputMethodManagerService never drops the soft keyboard between App 1 & App 2.
            val otherId = if (targetDisplayId == activeDisplayId) secondaryDisplayId else activeDisplayId
            if (otherId > 0) {
                applyDisplayImePolicyRaw(otherId, 2) // DISPLAY_IME_POLICY_HIDE on inactive display
            }
            applyDisplayImePolicy(targetDisplayId, imeOnPhone)
            runCatching { Thread.sleep(15L) }
        }
        return try {
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            val downCoords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = lx
                    this.y = ly
                    pressure = 1f
                    size = 1f
                }
            )
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, downCoords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            injectEventOnDisplay(down, targetDisplayId)
            down.recycle()

            // Real wall-clock delay (>= 40ms) between ACTION_DOWN and ACTION_UP so Android View,
            // Jetpack Compose, Chromium/WebView, and MIUI InputDispatcher all register a genuine click!
            runCatching { Thread.sleep(42L) }

            val upCoords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = lx
                    this.y = ly
                    pressure = 0f
                    size = 1f
                }
            )
            val upTime = SystemClock.uptimeMillis().coerceAtLeast(downTime + 40L)
            val up = MotionEvent.obtain(
                downTime, upTime, MotionEvent.ACTION_UP, 1, props, upCoords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            injectEventOnDisplay(up, targetDisplayId)
            up.recycle()
            true
        } catch (t: Throwable) {
            lastError = "Tap injection failed: ${t.message}"
            false
        }
    }

    override fun injectPinchZoom(
        focusX: Float,
        focusY: Float,
        scaleFactor: Float,
        isSecondary: Boolean
    ): Boolean {
        val targetDisplayId = if (isSecondary) secondaryDisplayId else activeDisplayId
        if (targetDisplayId <= 0) return false
        val clampedScale = scaleFactor.coerceIn(0.55f, 1.85f)
        if (kotlin.math.abs(clampedScale - 1f) < 0.01f) return true

        return try {
            val downTime = SystemClock.uptimeMillis()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )
            // Ensure span change exceeds Android's ScaleGestureDetector touch slop (~32px)
            val isZoomIn = clampedScale > 1f
            val startR = if (isZoomIn) 65f else 165f
            val endR = if (isZoomIn) {
                (startR + 55f + (clampedScale - 1f) * 200f).coerceIn(115f, 250f)
            } else {
                (startR - 55f - (1f - clampedScale) * 200f).coerceIn(32f, 110f)
            }

            fun coordsAt(radius: Float): Array<MotionEvent.PointerCoords> = arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = (focusX - radius).coerceAtLeast(4f)
                    y = (focusY - radius * 0.35f).coerceAtLeast(4f)
                    pressure = 1f
                    size = 1f
                },
                MotionEvent.PointerCoords().apply {
                    x = focusX + radius
                    y = focusY + radius * 0.35f
                    pressure = 1f
                    size = 1f
                }
            )

            fun obtainMulti(eventTime: Long, action: Int, count: Int, coords: Array<MotionEvent.PointerCoords>): MotionEvent {
                return MotionEvent.obtain(
                    downTime, eventTime, action, count, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
            }

            val c0 = coordsAt(startR)
            val eDown = obtainMulti(downTime, MotionEvent.ACTION_DOWN, 1, c0)
            injectEventOnDisplay(eDown, targetDisplayId)
            eDown.recycle()
            Thread.sleep(12L)

            val ePtrDown = obtainMulti(
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                c0
            )
            injectEventOnDisplay(ePtrDown, targetDisplayId)
            ePtrDown.recycle()
            Thread.sleep(12L)

            val steps = 6
            for (i in 1..steps) {
                val t = i.toFloat() / steps.toFloat()
                val r = startR + (endR - startR) * t
                val ev = obtainMulti(SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 2, coordsAt(r))
                injectEventOnDisplay(ev, targetDisplayId)
                ev.recycle()
                Thread.sleep(14L)
            }

            val cEnd = coordsAt(endR)
            val ePtrUp = obtainMulti(
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2,
                cEnd
            )
            injectEventOnDisplay(ePtrUp, targetDisplayId)
            ePtrUp.recycle()
            Thread.sleep(10L)

            val eUp = obtainMulti(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, cEnd)
            injectEventOnDisplay(eUp, targetDisplayId)
            eUp.recycle()
            true
        } catch (t: Throwable) {
            lastError = "Pinch zoom failed: ${t.message}"
            false
        }
    }

    override fun restartAppOnDisplay(packageName: String?, isSecondary: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        val targetDisplayId = if (isSecondary) secondaryDisplayId else activeDisplayId
        if (targetDisplayId <= 0) return false
        return try {
            val stopProc = ProcessBuilder("am", "force-stop", packageName)
                .redirectErrorStream(true)
                .start()
            stopProc.waitFor()
            Thread.sleep(150L)
            tryLaunchPackageOnDisplay(packageName, targetDisplayId)
        } catch (t: Throwable) {
            lastError = "Restart failed for $packageName: ${t.message}"
            false
        }
    }

    override fun setImeOnPhone(onPhone: Boolean) {
        imeOnPhone = onPhone
        if (activeDisplayId > 0) applyDisplayImePolicy(activeDisplayId, onPhone)
        if (secondaryDisplayId > 0) applyDisplayImePolicy(secondaryDisplayId, onPhone)
        powerController?.onStageStateChanged(
            active = hasActiveCarSurface && activeDisplayId > 0,
            keepAwake = keepPlaybackAlive,
            imeOnPhone = onPhone
        )
    }

    override fun setKeepPlaybackAlive(enabled: Boolean) {
        keepPlaybackAlive = enabled
        ensurePowerController()?.onStageStateChanged(
            active = hasActiveCarSurface && activeDisplayId > 0,
            keepAwake = enabled,
            imeOnPhone = imeOnPhone
        )
    }

    private fun applyDisplayImePolicy(displayId: Int, onPhone: Boolean) {
        // 0 = DISPLAY_IME_POLICY_LOCAL (show keyboard on car screen)
        // 1 = DISPLAY_IME_POLICY_FALLBACK_DISPLAY (show keyboard on phone screen)
        applyDisplayImePolicyRaw(displayId, if (onPhone) 1 else 0)
    }

    private fun applyDisplayImePolicyRaw(displayId: Int, policy: Int) {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "window") as? IBinder ?: return
            val wmStub = Class.forName("android.view.IWindowManager\$Stub")
            val wm = wmStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return
            wm.javaClass.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(wm, displayId, policy)
            Log.i(TAG, "Applied display $displayId IME policy=$policy")
        } catch (t: Throwable) {
            Log.w(TAG, "setDisplayImePolicy failed for display $displayId: ${t.message}")
        }
    }

    override fun destroy() {
        stopStageDisplay()
        powerController?.shutdown()
        if (!inProcess) {
            exitProcess(0)
        }
    }

    /**
     * Prevents portrait-only apps from rotating the virtual display into a narrow vertical column.
     * NOTE: Never call `clearForcedDisplaySize` or `clearForcedDisplayDensityForUser` here because on
     * Xiaomi HyperOS / MIUI, those methods overwrite the VirtualDisplay's native size & DPI with Display 0's
     * physical phone resolution (e.g. 1440x2064 @ 560dpi), causing severe letterbox bars and broken touch coordinates!
     */
    private fun keepDisplayLandscape(displayId: Int) {
        if (displayId <= 0) return
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, Context.WINDOW_SERVICE) as? IBinder ?: return
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return

            for (m in wm.javaClass.methods) {
                val p = m.parameterTypes
                runCatching {
                    when (m.name) {
                        "setIgnoreOrientationRequest" -> {
                            if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Boolean::class.javaPrimitiveType) {
                                m.invoke(wm, displayId, true)
                            }
                        }
                        "setFixedToUserRotation" -> {
                            if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType) {
                                m.invoke(wm, displayId, 2) // FIXED_TO_USER_ROTATION_ENABLED
                            }
                        }
                        "freezeDisplayRotation" -> {
                            if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType) {
                                m.invoke(wm, displayId, Surface.ROTATION_0)
                            } else if (p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType && p[2] == String::class.java) {
                                m.invoke(wm, displayId, Surface.ROTATION_0, DISPLAY_NAME)
                            }
                        }
                        "setWindowingMode" -> {
                            // WINDOWING_MODE_FULLSCREEN = 1 (ensures no freeform / split bounds inherited from Display 0)
                            if (p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType) {
                                m.invoke(wm, displayId, 1)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "keepDisplayLandscape failed for display $displayId: ${t.message}")
        }
    }

    private fun tryCreateViaVirtualDeviceManager(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int
    ): Int {
        return try {
            val ctx = resolveContext() ?: return -1
            val vdmClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
            val vdm = ctx.getSystemService(vdmClass) ?: return -1

            val paramsBuilderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val builder = paramsBuilderClass.getDeclaredConstructor().newInstance()
            runCatching {
                paramsBuilderClass.getMethod("setName", String::class.java).invoke(builder, DISPLAY_NAME)
            }
            val params = paramsBuilderClass.getMethod("build").invoke(builder)

            val createVdMethod = vdmClass.methods.firstOrNull {
                it.name == "createVirtualDevice" && it.parameterTypes.size == 2
            } ?: return -1

            // associationId = 0 is accepted by shell/system or falls back if OEM requires a real association
            val vd = createVdMethod.invoke(vdm, 0, params) ?: return -1
            virtualDeviceObj = vd

            val configBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val cfgBuilder = configBuilderClass.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).newInstance(DISPLAY_NAME, width, height, dpi)

            val flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
                FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED or FLAG_ALWAYS_UNLOCKED or FLAG_OWN_FOCUS
            runCatching {
                configBuilderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(cfgBuilder, flags)
            }
            if (surface != null) {
                // setSurface MUST succeed, otherwise VirtualDisplay would be headless (black screen)
                configBuilderClass.getMethod("setSurface", Surface::class.java).invoke(cfgBuilder, surface)
            }
            val cfg = configBuilderClass.getMethod("build").invoke(cfgBuilder)

            val createDisplayMethod = vd.javaClass.methods.firstOrNull {
                it.name == "createVirtualDisplay" && it.parameterTypes.size >= 1
            } ?: return -1

            val displayObj = when (createDisplayMethod.parameterTypes.size) {
                1 -> createDisplayMethod.invoke(vd, cfg)
                2 -> createDisplayMethod.invoke(vd, cfg, null)
                3 -> createDisplayMethod.invoke(vd, cfg, null, null)
                else -> null
            } as? VirtualDisplay ?: return -1

            if (surface != null && displayObj.surface == null) {
                displayObj.release()
                return -1
            }

            virtualDisplay = displayObj
            displayObj.display?.displayId ?: -1
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDeviceManager unavailable, will fallback to Trusted VirtualDisplay: ${t.message}")
            try {
                virtualDeviceObj?.javaClass?.getMethod("close")?.invoke(virtualDeviceObj)
            } catch (_: Throwable) {
            }
            virtualDeviceObj = null
            -1
        }
    }

    private fun tryCreateTrustedVirtualDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int
    ): Int = tryCreateTrustedVirtualDisplayInternal(DISPLAY_NAME, surface, width, height, dpi) { vd ->
        virtualDisplay = vd
    }

    private fun tryCreateTrustedVirtualDisplayInternal(
        name: String,
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        onCreated: (VirtualDisplay) -> Unit
    ): Int {
        return try {
            val dm = resolveDisplayManager()
            val sdk = Build.VERSION.SDK_INT
            val flagCandidates = intArrayOf(
                if (sdk >= 34) {
                    FLAG_BASE or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                        FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS
                } else -1,
                if (sdk >= 33) {
                    FLAG_BASE or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                        FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
                } else -1,
                // Crucial intermediate tiers when OEM ROMs (Xiaomi HyperOS / Samsung OneUI / ColorOS)
                // reject OWN_DISPLAY_GROUP or OWN_FOCUS: keep FLAG_ALWAYS_UNLOCKED so the VirtualDisplay
                // NEVER locks or goes black when the phone screen turns off!
                if (sdk >= 34) {
                    FLAG_BASE or FLAG_TRUSTED or FLAG_ALWAYS_UNLOCKED or
                        FLAG_TOUCH_FEEDBACK_DISABLED or FLAG_OWN_FOCUS
                } else -1,
                if (sdk >= 33) {
                    FLAG_BASE or FLAG_TRUSTED or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
                } else -1,
                if (sdk >= 33) {
                    FLAG_BASE or FLAG_TRUSTED or FLAG_ALWAYS_UNLOCKED
                } else -1,
                FLAG_BASE or FLAG_TRUSTED,
                FLAG_BASE
            )

            var lastErr: Throwable? = null
            for (flags in flagCandidates) {
                if (flags < 0) continue
                try {
                    val vd = dm?.createVirtualDisplay(name, width, height, dpi, surface, flags)
                        ?: createVirtualDisplayViaGlobal(name, surface, width, height, dpi, flags)
                    if (vd != null) {
                        onCreated(vd)
                        val id = vd.display?.displayId ?: -1
                        Log.i(TAG, "Created VirtualDisplay '$name' id=$id (${width}x${height}@${dpi}) flags=0x${Integer.toHexString(flags)}")
                        return id
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "createVirtualDisplay '$name' flags=0x${Integer.toHexString(flags)} refused: ${describe(e)}")
                    lastErr = e
                }
            }

            lastError = "Trusted VirtualDisplay failed: ${lastErr?.let { describe(it) } ?: "returned null"}"
            Log.e(TAG, lastError, lastErr)
            -1
        } catch (t: Throwable) {
            lastError = "Trusted VirtualDisplay failed: ${describe(t)}"
            Log.e(TAG, lastError, t)
            -1
        }
    }

    /**
     * Direct scrcpy-style `DisplayManagerGlobal.getInstance().createVirtualDisplay(...)` path
     * that communicates straight with `IDisplayManager` using `com.android.shell` package attribution,
     * working even if `DisplayManager(Context)` or `ActivityThread` is unavailable on custom ROMs.
     */
    private fun createVirtualDisplayViaGlobal(
        name: String,
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int
    ): VirtualDisplay? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null) ?: return null
            val shellCtx = resolveContext() ?: ShellContext(ContextWrapper(null))
            val cfgBuilderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val builder = cfgBuilderClass.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).newInstance(name, width, height, dpi)
            cfgBuilderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
            if (surface != null) {
                cfgBuilderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
            }
            val cfg = cfgBuilderClass.getMethod("build").invoke(builder)
            for (m in dmgClass.methods) {
                if (m.name != "createVirtualDisplay") continue
                val p = m.parameterTypes
                val vd = when (p.size) {
                    5 -> m.invoke(dmg, shellCtx, null, cfg, null, null)
                    6 -> m.invoke(dmg, shellCtx, null, cfg, null, null, null)
                    else -> continue
                } as? VirtualDisplay
                if (vd != null) return vd
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "createVirtualDisplayViaGlobal failed: ${describe(t)}")
            throw t
        }
    }

    private data class MatchedRunningTask(
        val taskId: Int,
        val displayId: Int,
        val hasTopActivity: Boolean
    )

    private fun resolveActivityTaskManager(): Any? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            } else {
                ActivityManager::class.java.getMethod("getService").invoke(null)
            }
        }.getOrNull() ?: runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val binder = smClass.getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder ?: return@runCatching null
            Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }

    private fun findRunningTaskForPackage(atm: Any, packageName: String): MatchedRunningTask? {
        return try {
            var tasks: List<*>? = null
            for (m in atm.javaClass.methods) {
                if (m.name != "getTasks" || m.parameterTypes.isEmpty()) continue
                val p = m.parameterTypes
                val args = arrayOfNulls<Any>(p.size)
                var supported = true
                var seenFirstInt = false
                for (i in p.indices) {
                    when (p[i]) {
                        Int::class.javaPrimitiveType -> {
                            args[i] = if (!seenFirstInt) {
                                seenFirstInt = true
                                60
                            } else {
                                Display.INVALID_DISPLAY
                            }
                        }
                        Boolean::class.javaPrimitiveType -> args[i] = false
                        else -> {
                            supported = false
                            break
                        }
                    }
                }
                if (!supported) continue
                tasks = runCatching { m.invoke(atm, *args) as? List<*> }.getOrNull()
                if (tasks != null) break
            }
            if (tasks == null) return null

            for (task in tasks) {
                if (task == null) continue
                val topActivity = runCatching { task.javaClass.getField("topActivity").get(task) as? ComponentName }.getOrNull()
                val baseActivity = runCatching { task.javaClass.getField("baseActivity").get(task) as? ComponentName }.getOrNull()
                val baseIntent = runCatching { task.javaClass.getField("baseIntent").get(task) as? Intent }.getOrNull()
                val topPkg = topActivity?.packageName
                val basePkg = baseActivity?.packageName
                val intentPkg = baseIntent?.component?.packageName ?: baseIntent?.`package`
                if (topPkg == packageName || basePkg == packageName || intentPkg == packageName) {
                    val taskId = runCatching { task.javaClass.getField("taskId").getInt(task) }.getOrDefault(-1)
                    if (taskId > 0) {
                        val dispId = runCatching {
                            android.app.TaskInfo::class.java.getField("displayId").getInt(task)
                        }.getOrElse {
                            runCatching { task.javaClass.getField("displayId").getInt(task) }.getOrDefault(Display.INVALID_DISPLAY)
                        }
                        return MatchedRunningTask(
                            taskId = taskId,
                            displayId = dispId,
                            hasTopActivity = topActivity != null
                        )
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun moveTaskToDisplayInternal(atm: Any, taskId: Int, targetDisplayId: Int): Boolean {
        if (taskId <= 0 || targetDisplayId <= 0) return false
        return try {
            val moveMethod = atm.javaClass.getMethod(
                "moveRootTaskToDisplay",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            moveMethod.invoke(atm, taskId, targetDisplayId)
            // Clear any freeform / multi-window override bounds left over from phone split-screen or PiP
            // so the task fills the target VirtualDisplay cleanly at 1:1 native aspect ratio.
            runCatching {
                for (m in atm.javaClass.methods) {
                    if (m.name == "resizeTask" && m.parameterTypes.size == 3 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        m.parameterTypes[1] == android.graphics.Rect::class.java &&
                        m.parameterTypes[2] == Int::class.javaPrimitiveType
                    ) {
                        m.invoke(atm, taskId, null, 0)
                        break
                    }
                }
            }
            runCatching {
                atm.javaClass.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType)
                    .invoke(atm, taskId)
            }
            keepDisplayLandscape(targetDisplayId)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "moveRootTaskToDisplay($taskId -> $targetDisplayId) failed: ${t.message}")
            false
        }
    }

    private fun launchOrMovePackageToDisplay(
        packageName: String,
        targetDisplayId: Int,
        moveExistingTask: Boolean
    ): Boolean {
        val atm = resolveActivityTaskManager()
        if (atm != null && moveExistingTask) {
            val existing = findRunningTaskForPackage(atm, packageName)
            if (existing != null && existing.taskId > 0) {
                if (existing.displayId != targetDisplayId) {
                    moveTaskToDisplayInternal(atm, existing.taskId, targetDisplayId)
                } else {
                    runCatching {
                        atm.javaClass.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType)
                            .invoke(atm, existing.taskId)
                    }
                }
                val afterMove = findRunningTaskForPackage(atm, packageName)
                if (existing.hasTopActivity && afterMove?.displayId == targetDisplayId) {
                    Log.i(TAG, "Moved live task ${existing.taskId} for $packageName to display $targetDisplayId")
                    return true
                }
            }
        }
        return tryLaunchPackageOnDisplay(packageName, targetDisplayId)
    }

    private fun resolveLaunchComponent(ctx: Context?, packageName: String): Pair<Intent?, ComponentName?> {
        if (ctx != null) {
            val pm = runCatching { ctx.packageManager }.getOrNull()
            if (pm != null) {
                val launchIntent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()
                if (launchIntent?.component != null) {
                    return launchIntent to launchIntent.component
                }
                val fallbackQuery = Intent(Intent.ACTION_MAIN).setPackage(packageName)
                val resolves = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.queryIntentActivities(fallbackQuery, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(fallbackQuery, 0)
                    }
                }.getOrDefault(emptyList())
                val actInfo = resolves.firstOrNull {
                    it.activityInfo != null && !it.activityInfo.name.contains("CarAppPermissionActivity")
                }?.activityInfo
                if (actInfo != null) {
                    val comp = ComponentName(packageName, actInfo.name)
                    val intent = Intent(Intent.ACTION_MAIN).setComponent(comp)
                    return intent to comp
                }
            }
        }
        // Zero-Context fallback via `cmd package resolve-activity`
        val resolvedFromCmd = runCatching {
            val proc = ProcessBuilder(
                "cmd", "package", "resolve-activity", "--brief",
                "-a", Intent.ACTION_MAIN,
                "-c", Intent.CATEGORY_LAUNCHER,
                packageName
            ).redirectErrorStream(true).start()
            val lines = proc.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            proc.waitFor()
            val compLine = lines.lastOrNull { it.contains('/') && !it.startsWith("No ", ignoreCase = true) }
            compLine?.let { ComponentName.unflattenFromString(it) }
        }.getOrNull()
        if (resolvedFromCmd != null) {
            return Intent(Intent.ACTION_MAIN).setComponent(resolvedFromCmd) to resolvedFromCmd
        }
        return null to null
    }

    /**
     * Ensures that if Android's ActivityStarter resumed an existing task on Display 0 (phone screen),
     * that task is immediately relocated onto `targetDisplayId` (Android Auto VirtualDisplay).
     */
    private fun ensureTaskOnTargetDisplay(packageName: String, component: ComponentName?, targetDisplayId: Int) {
        val atm = resolveActivityTaskManager() ?: return
        val matched = findRunningTaskForPackage(atm, packageName) ?: return
        if (matched.taskId > 0 && matched.displayId != targetDisplayId) {
            Log.w(TAG, "Task ${matched.taskId} for $packageName landed on display ${matched.displayId}; moving to $targetDisplayId")
            moveTaskToDisplayInternal(atm, matched.taskId, targetDisplayId)
            val recheck = findRunningTaskForPackage(atm, packageName)
            if (recheck != null && recheck.displayId != targetDisplayId && component != null) {
                runCatching {
                    val cmd = arrayOf(
                        "am", "start",
                        "--user", "0",
                        "--display", targetDisplayId.toString(),
                        "--windowingMode", "1",
                        "--activity-new-task",
                        "--activity-multiple-task",
                        "-n", component.flattenToShortString()
                    )
                    ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor()
                }
            }
        }
    }

    private fun tryLaunchPackageOnDisplay(packageName: String, targetDisplayId: Int): Boolean {
        val ctx = resolveContext()
        val (launchIntent, component) = resolveLaunchComponent(ctx, packageName)
        if (launchIntent == null && component == null) {
            lastError = "No launch intent for $packageName"
            return false
        }

        // 1. Primary path: direct IActivityTaskManager.startActivity with callingPackage = com.android.shell (or host package in direct mode)
        if (component != null) {
            val directOk = tryStartActivityDirectAtm(component, targetDisplayId)
            if (directOk) {
                ensureTaskOnTargetDisplay(packageName, component, targetDisplayId)
                Log.i(TAG, "Launched $packageName on display $targetDisplayId via IActivityTaskManager.startActivity")
                return true
            }
        }

        // 2. Secondary path: invoke `am start --display` as UID 2000 (com.android.shell).
        if (component != null) {
            try {
                val cmd = arrayOf(
                    "am", "start",
                    "--user", "0",
                    "--display", targetDisplayId.toString(),
                    "--windowingMode", "1",
                    "-a", Intent.ACTION_MAIN,
                    "-c", Intent.CATEGORY_LAUNCHER,
                    "-f", "0x10200000",
                    "-n", component.flattenToShortString()
                )
                val proc = ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val exit = proc.waitFor()
                if (exit == 0 && !out.contains("Error:", ignoreCase = true) && !out.contains("Exception", ignoreCase = true)) {
                    ensureTaskOnTargetDisplay(packageName, component, targetDisplayId)
                    Log.i(TAG, "Launched $packageName on display $targetDisplayId via am start")
                    return true
                }
                Log.w(TAG, "am start returned exit=$exit out=$out")
            } catch (t: Throwable) {
                Log.w(TAG, "am start fallback needed: ${t.message}")
            }
        }

        // 3. Fallback: direct Context.startActivity with launchDisplayId
        val fallbackIntent = launchIntent ?: return false
        val safeCtx = ctx ?: return false
        return try {
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val opts = ActivityOptions.makeBasic()
            opts.launchDisplayId = targetDisplayId
            safeCtx.startActivity(fallbackIntent, opts.toBundle())
            ensureTaskOnTargetDisplay(packageName, component, targetDisplayId)
            true
        } catch (t: Throwable) {
            lastError = "Launch failed for $packageName: ${describe(t)}"
            Log.e(TAG, lastError, t)
            false
        }
    }

    private fun tryStartActivityDirectAtm(component: ComponentName, targetDisplayId: Int): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val options: Bundle = ActivityOptions.makeBasic().setLaunchDisplayId(targetDisplayId).toBundle()
            val callerPkg = if (inProcess) (context?.packageName ?: SHELL_PKG) else SHELL_PKG

            val atm = resolveActivityTaskManager() ?: return false

            for (m: Method in atm.javaClass.methods) {
                if (m.name != "startActivity") continue
                val p = m.parameterTypes
                val args: Array<Any?> = when {
                    p.size == 11 && p[3] == Intent::class.java -> {
                        arrayOf(null, callerPkg, null, intent, null, null, null, 0, 0, null, options)
                    }
                    p.size == 10 && p[2] == Intent::class.java -> {
                        arrayOf(null, callerPkg, intent, null, null, null, 0, 0, null, options)
                    }
                    else -> continue
                }
                val res = m.invoke(atm, *args) as? Int ?: -1
                if (res >= 0) return true
                Log.w(TAG, "ATM startActivity(${component.flattenToShortString()}) returned code=$res")
                return false
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "tryStartActivityDirectAtm failed: ${describe(t)}")
            false
        }
    }

    private fun injectEventOnDisplay(event: InputEvent, displayId: Int) {
        val instance = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal")
                .getMethod("getInstance")
                .invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager")
            .getMethod("getInstance")
            .invoke(null)

        runCatching {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }
        val injectMethod = instance.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
        injectMethod.invoke(instance, event, 0)
    }

    @Synchronized
    private fun resolveDisplayManager(): DisplayManager? {
        cachedDisplayManager?.let { return it }
        val ctx = resolveContext() ?: return null
        if (inProcess) {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (dm != null) {
                cachedDisplayManager = dm
                return dm
            }
        }
        // Instantiate DisplayManager bound directly to ShellContext so all binder IPC calls carry com.android.shell (UID 2000)
        val dm = runCatching {
            val ctor: Constructor<DisplayManager> = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(ctx)
        }.getOrNull() ?: (ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
        cachedDisplayManager = dm
        return dm
    }

    @Synchronized
    private fun resolveContext(): Context? {
        cachedShellContext?.let { return it }
        initApplicationSharedMemoryIfNeeded()
        val base = context ?: try {
            val atClass = Class.forName("android.app.ActivityThread")
            var current = atClass.getMethod("currentActivityThread").invoke(null)
            if (current == null) {
                val mainLooper = android.os.Looper.getMainLooper()
                if (android.os.Looper.myLooper() != null) {
                    current = atClass.getMethod("systemMain").invoke(null)
                } else if (mainLooper != null) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var resolvedOnMain: Any? = null
                    android.os.Handler(mainLooper).post {
                        try {
                            resolvedOnMain = atClass.getMethod("currentActivityThread").invoke(null)
                                ?: atClass.getMethod("systemMain").invoke(null)
                        } catch (e: Throwable) {
                            Log.e(TAG, "systemMain on main looper failed", e)
                        } finally {
                            latch.countDown()
                        }
                    }
                    latch.await(3000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    current = resolvedOnMain
                } else {
                    android.os.Looper.prepare()
                    current = atClass.getMethod("systemMain").invoke(null)
                }
            }
            val rawSysCtx = if (current != null) {
                atClass.getMethod("getSystemContext").invoke(current) as? Context
            } else {
                null
            }
            if (rawSysCtx != null && !inProcess) {
                runCatching {
                    rawSysCtx.createPackageContext(
                        SHELL_PKG,
                        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                    )
                }.getOrDefault(rawSysCtx)
            } else {
                rawSysCtx
            }
        } catch (t: Throwable) {
            Log.e(TAG, "resolveContext failed: ${describe(t)}", t)
            null
        } ?: return null

        val wrapped = if (inProcess) base else ShellContext(base)
        cachedShellContext = wrapped
        return wrapped
    }

    @Synchronized
    private fun ensurePowerController(): CarStagePowerController? {
        powerController?.let { return it }
        val ctx = resolveContext() ?: return null
        val created = runCatching { CarStagePowerController(ctx) }.getOrNull()
        powerController = created
        return created
    }

    private fun whitelistPackageForScreenOff(packageName: String) {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isEmpty() || !cleanPkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return
        bgWhitelistExecutor.execute {
            runCatching {
                val script = "cmd deviceidle whitelist +'$cleanPkg' >/dev/null 2>&1; " +
                    "cmd deviceidle tempwhitelist -d 3600000 '$cleanPkg' >/dev/null 2>&1; " +
                    "appops set '$cleanPkg' RUN_IN_BACKGROUND allow >/dev/null 2>&1; " +
                    "appops set '$cleanPkg' RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
                    "am set-standby-bucket '$cleanPkg' active >/dev/null 2>&1"
                ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start().waitFor()
            }
        }
    }

    /**
     * Keeps Car Stage VirtualDisplays & apps running at 60 FPS when the phone screen turns off:
     * 1. Holds SCREEN_BRIGHT_WAKE_LOCK / SCREEN_DIM_WAKE_LOCK + per-display WakeLocks as `com.android.shell` (UID 2000)
     *    so Android's PowerManager & ActivityTaskManager never pause/stop Car Stage activities or freeze VSYNC.
     * 2. Powers off ONLY the physical OLED/LCD phone panel (`DisplayControl` + `SurfaceControl.setDisplayPowerMode(0)`
     *    or `IDisplayManager.requestDisplayPower(DEFAULT_DISPLAY, OFF)`) when the user presses the Power button
     *    or after idle timeout, keeping the phone cool & dark while Android Auto projection stays 100% live.
     * 3. Pressing the Power button again while the panel is already dark toggles the physical phone panel back ON
     *    so the user can always unlock and use their phone whenever needed.
     */
    @Suppress("DEPRECATION")
    private inner class CarStagePowerController(private val shellCtx: Context) {

        private val watchIntervalMs = 400L
        private val autoBlankDelayMs = 15_000L
        private val manualWakeGraceMs = 30_000L
        private val wakeSettleMs = 2_500L
        private val fightBlanks = 3
        private val fightWindowMs = 10_000L
        private val powerModeOff = 0
        private val powerModeNormal = 2

        private val powerThread = HandlerThread("CarStage-PanelPower").apply { start() }
        private val handler = Handler(powerThread.looper)
        private val dm: DisplayManager? = resolveDisplayManager()
        private val pm: PowerManager? = runCatching {
            shellCtx.createPackageContext(SHELL_PKG, 0).getSystemService(PowerManager::class.java)
        }.getOrNull() ?: (shellCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager)

        private val brightWakeLock: PowerManager.WakeLock? = runCatching {
            pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$SHELL_PKG:CarStageBright"
            )?.apply { setReferenceCounted(false) }
        }.getOrNull()

        private val dimWakeLock: PowerManager.WakeLock? = runCatching {
            pm?.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$SHELL_PKG:CarStageDim"
            )?.apply { setReferenceCounted(false) }
        }.getOrNull()

        private var heldWakeLock: PowerManager.WakeLock? = null
        private val displayWakeLocks = ConcurrentHashMap<Int, PowerManager.WakeLock>()

        @Volatile
        private var stageActive = false
        @Volatile
        private var keepAwake = true
        @Volatile
        private var keepPhoneLitForIme = false
        @Volatile
        private var panelOffMode = false
        @Volatile
        private var surfaceControlBlanked = false
        @Volatile
        private var panelFightsOff = false

        private val blankTimestamps = LongArray(fightBlanks)
        private var blankCount = 0
        private var settleUntilMs = 0L

        private var reflectionChecked = false
        private var idmInstance: Any? = null
        private var requestDisplayPowerMethod: Method? = null
        private var setDisplayPowerModeMethod: Method? = null
        private var physicalDisplayTokenCached: IBinder? = null
        private var iwmInstance: Any? = null
        private var dismissKeyguardMethod: Method? = null

        private val watchRunnable = object : Runnable {
            override fun run() {
                if (!stageActive || !keepAwake) return
                enforceDesiredState()
                if (stageActive && keepAwake) {
                    handler.postDelayed(this, watchIntervalMs)
                }
            }
        }

        private val autoBlankRunnable = Runnable {
            if (stageActive && keepAwake && !keepPhoneLitForIme && !panelOffMode && !panelFightsOff) {
                panelOffMode = true
                settleUntilMs = SystemClock.uptimeMillis() + wakeSettleMs
                acquireTargetWakeLock()
                setPhysicalPanelPower(off = true)
                ensureVirtualDisplaysAwakeInternal()
            }
        }

        init {
            runCatching {
                dm?.registerDisplayListener(object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) {}
                    override fun onDisplayRemoved(displayId: Int) {
                        releaseDisplayWakeLock(displayId)
                    }
                    override fun onDisplayChanged(displayId: Int) {
                        if (!stageActive || !keepAwake) return
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            handler.post { enforceDesiredState() }
                            handler.postDelayed({ enforceDesiredState() }, 120L)
                            handler.postDelayed({ enforceDesiredState() }, 350L)
                        } else if (displayId == activeDisplayId || displayId == secondaryDisplayId) {
                            handler.post { ensureVirtualDisplaysAwakeInternal() }
                        }
                    }
                }, handler)
            }
        }

        fun onStageStateChanged(active: Boolean, keepAwake: Boolean, imeOnPhone: Boolean) {
            handler.post {
                this.stageActive = active
                this.keepAwake = keepAwake
                this.keepPhoneLitForIme = imeOnPhone

                if (!active || !keepAwake) {
                    handler.removeCallbacks(watchRunnable)
                    handler.removeCallbacks(autoBlankRunnable)
                    restorePhysicalPanelAndReleaseLocks()
                    return@post
                }

                if (imeOnPhone && panelOffMode) {
                    panelOffMode = false
                    surfaceControlBlanked = false
                    setPhysicalPanelPower(off = false)
                }

                ensureVirtualDisplaysAwakeInternal()
                enforceDesiredState()
                scheduleWatchLoop()
                if (!imeOnPhone && !panelOffMode && !panelFightsOff) {
                    handler.removeCallbacks(autoBlankRunnable)
                    handler.postDelayed(autoBlankRunnable, autoBlankDelayMs)
                }
            }
        }

        fun onCarInteraction() {
            if (!stageActive || !keepAwake) return
            handler.post {
                if (pm?.isInteractive == false) {
                    if (!keepPhoneLitForIme) {
                        panelOffMode = true
                        settleUntilMs = SystemClock.uptimeMillis() + wakeSettleMs
                        setPhysicalPanelPower(off = true)
                    }
                    acquireTargetWakeLock()
                    wakeUpSystemInternal()
                    if (panelOffMode) {
                        setPhysicalPanelPower(off = true)
                    }
                    dismissKeyguardInternal()
                    refocusActiveStageTasks()
                }
                ensureVirtualDisplaysAwakeInternal()
            }
        }

        fun ensureVirtualDisplaysAwake() {
            handler.post { ensureVirtualDisplaysAwakeInternal() }
        }

        fun releaseDisplayWakeLock(displayId: Int) {
            val lock = displayWakeLocks.remove(displayId) ?: return
            runCatching {
                if (lock.isHeld) lock.release()
            }
        }

        fun shutdown() {
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                try {
                    stageActive = false
                    handler.removeCallbacks(watchRunnable)
                    handler.removeCallbacks(autoBlankRunnable)
                    restorePhysicalPanelAndReleaseLocks()
                } finally {
                    latch.countDown()
                }
            }
            runCatching { latch.await(600L, java.util.concurrent.TimeUnit.MILLISECONDS) }
            runCatching { powerThread.quitSafely() }
        }

        private fun scheduleWatchLoop() {
            handler.removeCallbacks(watchRunnable)
            handler.postDelayed(watchRunnable, watchIntervalMs)
        }

        private fun enforceDesiredState() {
            if (!stageActive || !keepAwake) return
            val interactive = pm?.isInteractive ?: true
            val now = SystemClock.uptimeMillis()

            if (!interactive) {
                if (panelOffMode && now > settleUntilMs) {
                    // Physical panel was ALREADY blanked and user pressed Power button -> wake phone screen ON!
                    panelOffMode = false
                    surfaceControlBlanked = false
                    acquireTargetWakeLock()
                    setPhysicalPanelPower(off = false)
                    wakeUpSystemInternal()
                    ensureVirtualDisplaysAwakeInternal()
                    if (!keepPhoneLitForIme && !panelFightsOff) {
                        handler.removeCallbacks(autoBlankRunnable)
                        handler.postDelayed(autoBlankRunnable, manualWakeGraceMs)
                    }
                    return
                }

                // Phone screen was ON and user pressed Power button (or system timed out) ->
                // enter Panel-Off Awake Mode so phone screen turns dark while Car Stage stays 100% live!
                handler.removeCallbacks(autoBlankRunnable)
                if (!keepPhoneLitForIme && !panelFightsOff) {
                    panelOffMode = true
                    settleUntilMs = now + wakeSettleMs
                    setPhysicalPanelPower(off = true)
                }
                releaseHeldWakeLock()
                acquireTargetWakeLock()
                wakeUpSystemInternal()
                if (panelOffMode) {
                    setPhysicalPanelPower(off = true)
                }
                dismissKeyguardInternal()
                ensureVirtualDisplaysAwakeInternal()
                refocusActiveStageTasks()

                handler.postDelayed({
                    if (stageActive && keepAwake) {
                        if (panelOffMode) setPhysicalPanelPower(off = true)
                        if (pm?.isInteractive == false) {
                            acquireTargetWakeLock()
                            wakeUpSystemInternal()
                        }
                        if (panelOffMode) setPhysicalPanelPower(off = true)
                        dismissKeyguardInternal()
                        ensureVirtualDisplaysAwakeInternal()
                        refocusActiveStageTasks()
                    }
                }, 130L)
                return
            }

            acquireTargetWakeLock()
            ensureVirtualDisplaysAwakeInternal()

            if (panelOffMode && !keepPhoneLitForIme && !panelFightsOff) {
                // If SurfaceControl blanking succeeded, Display 0 stays STATE_ON logically while HWC panel is off;
                // only re-check Display.STATE_OFF when relying on IDisplayManager.requestDisplayPower.
                if (!surfaceControlBlanked) {
                    val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                    if (d != null && d.state != Display.STATE_OFF) {
                        if (now >= settleUntilMs) {
                            blankTimestamps[blankCount % fightBlanks] = now
                            blankCount++
                            if (blankCount >= fightBlanks &&
                                now - blankTimestamps[blankCount % fightBlanks] < fightWindowMs
                            ) {
                                panelFightsOff = true
                                panelOffMode = false
                                acquireTargetWakeLock()
                                setPhysicalPanelPower(off = false)
                                return
                            }
                        }
                        if (!setPhysicalPanelPower(off = true)) {
                            panelFightsOff = true
                            panelOffMode = false
                        }
                    }
                }
            }
        }

        private fun acquireTargetWakeLock() {
            val target = if (panelOffMode && !panelFightsOff && !keepPhoneLitForIme) {
                brightWakeLock ?: dimWakeLock
            } else {
                dimWakeLock ?: brightWakeLock
            } ?: return
            if (heldWakeLock === target && target.isHeld) return
            runCatching { target.acquire() }
            if (heldWakeLock != null && heldWakeLock !== target) {
                runCatching { heldWakeLock?.release() }
            }
            heldWakeLock = target
        }

        private fun releaseHeldWakeLock() {
            runCatching {
                if (heldWakeLock?.isHeld == true) {
                    heldWakeLock?.release()
                }
            }
            heldWakeLock = null
        }

        private fun restorePhysicalPanelAndReleaseLocks() {
            panelOffMode = false
            surfaceControlBlanked = false
            panelFightsOff = false
            blankCount = 0
            setPhysicalPanelPower(off = false)
            releaseHeldWakeLock()
            for ((dispId, _) in displayWakeLocks) {
                releaseDisplayWakeLock(dispId)
            }
            handler.postDelayed({
                if (!stageActive && !panelOffMode) {
                    val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                    if (d != null && d.state == Display.STATE_OFF && pm?.isInteractive == true) {
                        goToSleepInternal()
                        handler.postDelayed({ wakeUpSystemInternal() }, 250L)
                    }
                }
            }, 450L)
        }

        private fun ensureReflection() {
            if (reflectionChecked) return
            reflectionChecked = true
            unlockHiddenApiExemptions()

            runCatching {
                val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance")
                    .invoke(null)
                val dmField: Field = global.javaClass.getDeclaredField("mDm").apply { isAccessible = true }
                val idm = dmField.get(global)
                idmInstance = idm
                if (idm != null) {
                    requestDisplayPowerMethod = idm.javaClass.methods.firstOrNull {
                        it.name == "requestDisplayPower" && it.parameterTypes.size == 2
                    }?.apply { isAccessible = true }
                }
            }

            runCatching {
                val sc = Class.forName("android.view.SurfaceControl")
                setDisplayPowerModeMethod = sc.getDeclaredMethod(
                    "setDisplayPowerMode",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
                physicalDisplayTokenCached = resolvePhysicalDisplayToken(sc)
            }

            runCatching {
                val binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, Context.WINDOW_SERVICE) as? IBinder
                if (binder != null) {
                    val wm = Class.forName("android.view.IWindowManager\$Stub")
                        .getMethod("asInterface", IBinder::class.java)
                        .invoke(null, binder)
                    iwmInstance = wm
                    dismissKeyguardMethod = wm?.javaClass?.methods?.firstOrNull {
                        it.name == "dismissKeyguard"
                    }?.apply { isAccessible = true }
                }
            }
        }

        private fun resolvePhysicalDisplayToken(scClass: Class<*>): IBinder? {
            // 1. Android 10–13: SurfaceControl.getInternalDisplayToken() / getPhysicalDisplayToken(id)
            runCatching {
                val m = scClass.getDeclaredMethod("getInternalDisplayToken").apply { isAccessible = true }
                (m.invoke(null) as? IBinder)?.let { return it }
            }
            runCatching {
                val getIds = scClass.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }
                val ids = getIds.invoke(null) as? LongArray
                val firstId = ids?.firstOrNull() ?: 0L
                val getTok = scClass.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
                (getTok.invoke(null, firstId) as? IBinder)?.let { return it }
            }
            // 2. Android 14+ (scrcpy DisplayControl technique via /system/framework/services.jar + android_servers)
            return runCatching {
                runCatching { System.loadLibrary("android_servers") }
                val dcClass = runCatching {
                    Class.forName("com.android.server.display.DisplayControl")
                }.getOrElse {
                    val cl = PathClassLoader("/system/framework/services.jar", ClassLoader.getSystemClassLoader())
                    cl.loadClass("com.android.server.display.DisplayControl")
                }
                val getIds = dcClass.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }
                val ids = getIds.invoke(null) as? LongArray
                val firstId = ids?.firstOrNull() ?: return@runCatching null
                val getTok = dcClass.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
                getTok.invoke(null, firstId) as? IBinder
            }.getOrNull()
        }

        private fun setPhysicalPanelPower(off: Boolean): Boolean {
            ensureReflection()
            // 1. Primary path: HWC hardware panel power off via SurfaceControl.setDisplayPowerMode
            val setMode = setDisplayPowerModeMethod
            val token = physicalDisplayTokenCached
            if (setMode != null && token != null) {
                val scOk = runCatching {
                    setMode.invoke(null, token, if (off) powerModeOff else powerModeNormal)
                    true
                }.getOrDefault(false)
                if (scOk) {
                    surfaceControlBlanked = off
                    return true
                }
            }
            surfaceControlBlanked = false

            // 2. Fallback path: IDisplayManager.requestDisplayPower on Display.DEFAULT_DISPLAY
            val idm = idmInstance
            val req = requestDisplayPowerMethod
            if (idm != null && req != null) {
                return runCatching {
                    val pType = req.parameterTypes[1]
                    val arg: Any = if (pType == Boolean::class.javaPrimitiveType) {
                        !off
                    } else {
                        if (off) Display.STATE_OFF else Display.STATE_UNKNOWN
                    }
                    req.invoke(idm, Display.DEFAULT_DISPLAY, arg)
                    true
                }.getOrDefault(false)
            }
            return false
        }

        private fun ensureVirtualDisplaysAwakeInternal() {
            ensureReflection()
            val idm = idmInstance
            val req = requestDisplayPowerMethod
            val ids = intArrayOf(activeDisplayId, secondaryDisplayId)
            for (dispId in ids) {
                if (dispId <= Display.DEFAULT_DISPLAY) continue
                if (idm != null && req != null) {
                    runCatching {
                        val pType = req.parameterTypes[1]
                        val arg: Any = if (pType == Boolean::class.javaPrimitiveType) true else Display.STATE_ON
                        req.invoke(idm, dispId, arg)
                    }
                }
                var lock = displayWakeLocks[dispId]
                if (lock == null) {
                    lock = runCatching {
                        val m = PowerManager::class.java.getMethod(
                            "newWakeLock",
                            Int::class.javaPrimitiveType,
                            String::class.java,
                            Int::class.javaPrimitiveType
                        )
                        (m.invoke(
                            pm,
                            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "$SHELL_PKG:CarStageVD_$dispId",
                            dispId
                        ) as? PowerManager.WakeLock)?.apply { setReferenceCounted(false) }
                    }.getOrNull()
                    if (lock != null) {
                        displayWakeLocks[dispId] = lock
                    }
                }
                if (lock != null && !lock.isHeld) {
                    runCatching { lock.acquire() }
                }
            }
        }

        private fun refocusActiveStageTasks() {
            val atm = resolveActivityTaskManager() ?: return
            val pId = activeDisplayId
            val pPkg = lastPrimaryPkg
            if (pId > 0 && pPkg.isNotBlank()) {
                val matched = findRunningTaskForPackage(atm, pPkg)
                if (matched != null && matched.taskId > 0) {
                    if (matched.displayId != pId) {
                        moveTaskToDisplayInternal(atm, matched.taskId, pId)
                    } else {
                        runCatching {
                            atm.javaClass.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType)
                                .invoke(atm, matched.taskId)
                        }
                    }
                    if (!matched.hasTopActivity) {
                        tryLaunchPackageOnDisplay(pPkg, pId)
                    }
                }
            }
            val sId = secondaryDisplayId
            val sPkg = lastSecondaryPkg
            if (sId > 0 && sPkg.isNotBlank()) {
                val matchedSec = findRunningTaskForPackage(atm, sPkg)
                if (matchedSec != null && matchedSec.taskId > 0) {
                    if (matchedSec.displayId != sId) {
                        moveTaskToDisplayInternal(atm, matchedSec.taskId, sId)
                    }
                    if (!matchedSec.hasTopActivity) {
                        tryLaunchPackageOnDisplay(sPkg, sId)
                    }
                }
            }
        }

        private fun dismissKeyguardInternal() {
            ensureReflection()
            val wm = iwmInstance ?: return
            val m = dismissKeyguardMethod ?: return
            runCatching {
                when (m.parameterTypes.size) {
                    0 -> m.invoke(wm)
                    1 -> m.invoke(wm, null)
                    2 -> m.invoke(wm, null, null)
                }
            }
        }

        private fun wakeUpSystemInternal() {
            val powerMgr = pm ?: return
            runCatching {
                try {
                    powerMgr.javaClass.getMethod(
                        "wakeUp",
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    ).invoke(powerMgr, SystemClock.uptimeMillis(), 0, "$SHELL_PKG:CarStage")
                } catch (_: NoSuchMethodException) {
                    powerMgr.javaClass.getMethod("wakeUp", Long::class.javaPrimitiveType)
                        .invoke(powerMgr, SystemClock.uptimeMillis())
                }
            }
        }

        private fun goToSleepInternal() {
            val powerMgr = pm ?: return
            runCatching {
                powerMgr.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
                    .invoke(powerMgr, SystemClock.uptimeMillis())
            }
        }
    }

    private fun describe(t: Throwable): String {
        val cause = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    /**
     * Ensures system service calls made from UID 2000 report packageName = "com.android.shell"
     * instead of "android" (which DisplayManagerService / ActivityManagerService reject with SecurityException).
     */
    private class ShellContext(base: Context?) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        override fun getApplicationContext(): Context = this

        override fun getAttributionSource(): android.content.AttributionSource {
            return if (Build.VERSION.SDK_INT >= 31) {
                android.content.AttributionSource.Builder(Process.myUid())
                    .setPackageName(SHELL_PKG)
                    .build()
            } else {
                super.getAttributionSource()
            }
        }
    }
}
