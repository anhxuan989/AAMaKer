package com.aamaker.app.stage

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService (UID 2000 / shell) for the isolated Car Stage feature.
 *
 * Key capabilities:
 * 1. Android 14+ VirtualDeviceManager host (independent display group / focus / lifecycle),
 *    with automatic fallback to trusted DisplayManager VirtualDisplay.
 * 2. Zero-reload task movement via IActivityTaskManager.moveRootTaskToDisplay.
 * 3. Top-task orientation inspection for the Car Stage display.
 */
class CarStageDisplayServer(private val context: Context? = null) : ICarStageService.Stub() {

    companion object {
        private const val TAG = "CarStageServer"
        private const val DISPLAY_NAME = "AAMaKerCarStage"

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
    }

    private var virtualDeviceObj: Any? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var secondaryVirtualDisplay: VirtualDisplay? = null

    @Volatile
    private var activeDisplayId: Int = -1

    @Volatile
    private var secondaryDisplayId: Int = -1

    @Volatile
    private var imeOnPhone: Boolean = false

    @Volatile
    private var lastTappedDisplayId: Int = -1

    @Volatile
    private var engineMode: String = "STOPPED"

    @Volatile
    private var lastError: String = ""

    override fun startStageDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
        preferVirtualDevice: Boolean
    ): Int {
        stopStageDisplay()
        lastError = ""

        val safeW = width.coerceIn(480, 3840)
        val safeH = height.coerceIn(320, 2160)
        val safeDpi = dpi.coerceIn(120, 640)

        if (preferVirtualDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val vdId = tryCreateViaVirtualDeviceManager(surface, safeW, safeH, safeDpi)
            if (vdId > 0) {
                activeDisplayId = vdId
                engineMode = "VIRTUAL_DEVICE"
                applyDisplayImePolicy(vdId, imeOnPhone)
                Log.i(TAG, "Started Car Stage via VirtualDeviceManager: displayId=$vdId")
                return vdId
            }
        }

        val trustedId = tryCreateTrustedVirtualDisplay(surface, safeW, safeH, safeDpi)
        if (trustedId > 0) {
            activeDisplayId = trustedId
            engineMode = "TRUSTED_DISPLAY"
            applyDisplayImePolicy(trustedId, imeOnPhone)
            Log.i(TAG, "Started Car Stage via Trusted VirtualDisplay: displayId=$trustedId")
            return trustedId
        }

        engineMode = "STOPPED"
        return -1
    }

    override fun stopStageDisplay() {
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

        if (moveExistingTask) {
            val moved = tryMoveRunningTaskToDisplay(packageName, targetDisplayId)
            if (moved) {
                Log.i(TAG, "Moved existing task for $packageName to display $targetDisplayId")
                return true
            }
        }

        return tryLaunchPackageOnDisplay(packageName, targetDisplayId)
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
        val safeW = width.coerceIn(240, 3840)
        val safeH = height.coerceIn(240, 2160)
        val safeDpi = dpi.coerceIn(120, 640)
        val id = tryCreateTrustedVirtualDisplayInternal("${DISPLAY_NAME}_2", surface, safeW, safeH, safeDpi) { vd ->
            secondaryVirtualDisplay = vd
        }
        secondaryDisplayId = id
        if (id > 0) {
            applyDisplayImePolicy(id, imeOnPhone)
        }
        Log.i(TAG, "Started Secondary Car Stage display: displayId=$id (${safeW}x${safeH})")
        return id
    }

    override fun stopSecondaryDisplay() {
        try {
            secondaryVirtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing secondaryVirtualDisplay", t)
        } finally {
            secondaryVirtualDisplay = null
            secondaryDisplayId = -1
        }
    }

    override fun launchOrMoveSecondaryApp(packageName: String?, moveExistingTask: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        val targetDisplayId = secondaryDisplayId
        if (targetDisplayId <= 0) {
            lastError = "Secondary stage display is not active"
            return false
        }
        if (moveExistingTask && tryMoveRunningTaskToDisplay(packageName, targetDisplayId)) {
            Log.i(TAG, "Moved existing task for $packageName to secondary display $targetDisplayId")
            return true
        }
        return tryLaunchPackageOnDisplay(packageName, targetDisplayId)
    }

    override fun injectTapOnSecondary(x: Float, y: Float): Boolean {
        return injectTapOnDisplay(secondaryDisplayId, x, y)
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
        return try {
            val now = SystemClock.uptimeMillis()
            val dt = if (downTime > 0L) downTime else now
            val ev = MotionEvent.obtain(dt, now, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
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
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            val up = MotionEvent.obtain(now, now + 25, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectEventOnDisplay(down, targetDisplayId)
            injectEventOnDisplay(up, targetDisplayId)
            down.recycle()
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
        exitProcess(0)
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
            val ctx = resolveContext() ?: run {
                lastError = "Unable to resolve Context"
                return -1
            }
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: run {
                lastError = "DisplayManager unavailable"
                return -1
            }
            var flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
                FLAG_SUPPORTS_TOUCH or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_OWN_FOCUS
            }
            val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
            if (vd == null) {
                lastError = "createVirtualDisplay returned null"
                return -1
            }
            onCreated(vd)
            vd.display?.displayId ?: -1
        } catch (t: Throwable) {
            lastError = "Trusted VirtualDisplay failed: ${t.message}"
            Log.e(TAG, lastError, t)
            -1
        }
    }

    private fun tryMoveRunningTaskToDisplay(packageName: String, targetDisplayId: Int): Boolean {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "activity_task") as? IBinder ?: return false
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val atm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return false

            val getTasksMethod = atm.javaClass.methods.firstOrNull {
                it.name == "getTasks" && it.parameterTypes.isNotEmpty()
            } ?: return false

            val tasks = when (getTasksMethod.parameterTypes.size) {
                1 -> getTasksMethod.invoke(atm, 30)
                2 -> getTasksMethod.invoke(atm, 30, false)
                3 -> getTasksMethod.invoke(atm, 30, false, false)
                4 -> getTasksMethod.invoke(atm, 30, false, false, Display.INVALID_DISPLAY)
                else -> null
            } as? List<*> ?: return false

            var matchedTaskId = -1
            for (task in tasks) {
                if (task == null) continue
                val topActivity = runCatching { task.javaClass.getField("topActivity").get(task) }.getOrNull()
                val baseActivity = runCatching { task.javaClass.getField("baseActivity").get(task) }.getOrNull()
                val topPkg = topActivity?.let { runCatching { it.javaClass.getMethod("getPackageName").invoke(it) as? String }.getOrNull() }
                val basePkg = baseActivity?.let { runCatching { it.javaClass.getMethod("getPackageName").invoke(it) as? String }.getOrNull() }
                if (topPkg == packageName || basePkg == packageName) {
                    matchedTaskId = runCatching { task.javaClass.getField("taskId").getInt(task) }.getOrDefault(-1)
                    if (matchedTaskId > 0) break
                }
            }

            if (matchedTaskId <= 0) return false

            val moveMethod = atm.javaClass.getMethod(
                "moveRootTaskToDisplay",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            moveMethod.invoke(atm, matchedTaskId, targetDisplayId)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "moveRootTaskToDisplay skipped for $packageName: ${t.message}")
            false
        }
    }

    private fun tryLaunchPackageOnDisplay(packageName: String, targetDisplayId: Int): Boolean {
        val ctx = resolveContext() ?: run {
            lastError = "Unable to resolve Context"
            return false
        }
        val launchIntent = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            lastError = "No launch intent for $packageName"
            return false
        }
        val component = launchIntent.component

        // 1. Primary path: invoke `am start --display` as UID 2000 (com.android.shell).
        // Avoids Background Activity Launch (BAL) / callingPackage restrictions on Android 14+.
        if (component != null) {
            try {
                val cmd = arrayOf(
                    "am", "start",
                    "--user", "0",
                    "--display", targetDisplayId.toString(),
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
                    Log.i(TAG, "Launched $packageName on display $targetDisplayId via am start")
                    return true
                }
                Log.w(TAG, "am start returned exit=$exit out=$out")
            } catch (t: Throwable) {
                Log.w(TAG, "am start fallback needed: ${t.message}")
            }
        }

        // 2. Fallback: direct Context.startActivity with launchDisplayId
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val opts = ActivityOptions.makeBasic()
            opts.launchDisplayId = targetDisplayId
            ctx.startActivity(launchIntent, opts.toBundle())
            true
        } catch (t: Throwable) {
            lastError = "Launch failed for $packageName: ${t.message}"
            Log.e(TAG, lastError, t)
            false
        }
    }

    private fun injectEventOnDisplay(event: InputEvent, displayId: Int) {
        val imClass = Class.forName("android.hardware.input.InputManager")
        val instance = runCatching {
            imClass.getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManagerGlobal")
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

    private fun resolveContext(): Context? {
        if (context != null) return context
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val current = atClass.getMethod("currentActivityThread").invoke(null)
                ?: atClass.getMethod("systemMain").invoke(null)
            atClass.getMethod("getSystemContext").invoke(current) as? Context
        } catch (_: Throwable) {
            null
        }
    }
}
