package com.aamaker.stub.runtime

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Manages phone screen power-off / ultra-dim state when connected to Android Auto.
 *
 * In dual-app patched mode, turning off the phone screen normally (power button / deep sleep)
 * causes OEM ROMs (MIUI, HyperOS, OneUI, ColorOS) to freeze Choreographer VSYNC and GPU RenderThreads
 * for both Primary and Secondary VirtualDisplays.
 *
 * Multi-tier architecture to turn off the phone screen while keeping 60 FPS Dual-App GPU rendering:
 * 1. Tier 1 (Shizuku / Root Privileged Display Server): Powers off ONLY the physical display panel
 *    (`IDisplayManager.requestDisplayPower(DEFAULT_DISPLAY, STATE_OFF)` / `SurfaceControl.setDisplayPowerMode(OFF)`)
 *    while holding a DisplayGroup WakeLock so the GPU and Choreographer run at full speed with 0% backlight.
 * 2. Tier 2 (Physical Power Button Intercept): If the user presses the phone's physical Power button while
 *    Android Auto is projecting, immediately transitions into Tier 1 / Tier 3 so the phone screen stays dark
 *    without pausing either car app.
 * 3. Tier 3 (Non-Root / OLED Fallback): Attaches a pure AMOLED black (`#000000`, 0 lit pixels) `DimScrimView`
 *    to phone-side windows with `screenBrightness = 0.001f` and `FLAG_KEEP_SCREEN_ON`, waking on double-tap.
 * 4. Disconnecting Android Auto immediately restores normal phone display power and brightness.
 */
object PhoneScreenDimManager {

    private const val TAG = "PhoneScreenDimManager"
    private const val TAG_DIM_SCRIM = "AAMAKER_DIM_SCRIM"
    private const val PREFS_DIM = "aamaker_dim_prefs"
    private const val KEY_SAVED_BRIGHTNESS = "saved_system_brightness"
    private const val KEY_SAVED_MODE = "saved_system_mode"

    private val bridgeUri = Uri.parse("content://com.aamaker.app.dualbridge")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var isDimmed: Boolean = false
        private set

    @Volatile
    var isHardwarePanelOffActive: Boolean = false
        private set

    private val originalWindowBrightnessMap = WeakHashMap<Activity, Float>()
    private var dimOverlayView: View? = null
    private var windowManager: WindowManager? = null
    private var phoneScreenWakeLock: PowerManager.WakeLock? = null

    private var bridgeTokenProvider: (() -> String?)? = null
    private var bridgeInvoker: ((String, Bundle) -> Bundle?)? = null

    private var disconnectReceiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    private fun acquirePhoneScreenWakeLock(context: Context) {
        try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val existing = phoneScreenWakeLock
            if (existing != null && existing.isHeld) return
            val wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AAMaKer:PhoneCpuKeepAlive"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            phoneScreenWakeLock = wl
        } catch (_: Throwable) {
        }
    }

    private fun releasePhoneScreenWakeLock() {
        try {
            phoneScreenWakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {
        } finally {
            phoneScreenWakeLock = null
        }
    }

    /**
     * Initializes bridge communication handles from DualWindowController or CarAppService.
     */
    fun initBridge(
        tokenProvider: () -> String?,
        invoker: (String, Bundle) -> Bundle?
    ) {
        this.bridgeTokenProvider = tokenProvider
        this.bridgeInvoker = invoker
    }

    /**
     * Notifies the privileged server that an Android Auto projection session is active so it can
     * keep DisplayGroup VSYNC alive and intercept physical Power button presses without freezing.
     */
    fun notifySessionState(context: Context, active: Boolean) {
        val appCtx = context.applicationContext
        if (active) {
            acquirePhoneScreenWakeLock(appCtx)
        } else {
            releasePhoneScreenWakeLock()
        }
        bgExecutor.execute {
            try {
                val bundle = Bundle().apply {
                    putBoolean("dim", if (active) isDimmed else false)
                    putBoolean("keep_session_awake", active)
                    putInt("restore_brightness", -1)
                    putInt("restore_mode", -1)
                }
                invokeBridgeCallInternal(appCtx, METHOD_SET_PHONE_BRIGHTNESS, bundle)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Called when `Intent.ACTION_SCREEN_OFF` is detected during an active Android Auto projection session.
     * Ensures the phone screen stays dark while waking the DisplayGroup in the background ONLY via
     * PrivilegedDisplayServer (if needed), without turning the physical phone screen back on.
     */
    fun onPhysicalScreenOffIntercepted(context: Context) {
        val appCtx = context.applicationContext
        acquirePhoneScreenWakeLock(appCtx)
        dimPhoneScreen(appCtx)
    }

    /**
     * Powers off the physical phone panel (or applies pure AMOLED black + 0.1% brightness fallback)
     * and keeps the GPU display pipeline awake.
     */
    fun dimPhoneScreen(
        context: Context,
        onSendBridgeCommand: ((String, Bundle) -> Bundle?)? = null
    ) {
        if (onSendBridgeCommand != null) {
            this.bridgeInvoker = onSendBridgeCommand
        }
        val alreadyDimmed = isDimmed
        isDimmed = true

        val appCtx = context.applicationContext
        acquirePhoneScreenWakeLock(appCtx)

        // 1. Record original system brightness into persistent preferences (never overwrite with <= 5!)
        val sp = appCtx.getSharedPreferences(PREFS_DIM, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_SAVED_BRIGHTNESS)) {
            val resolver = appCtx.contentResolver
            val currentBrightness = try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) {
                128
            }
            val currentMode = try {
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            } catch (_: Throwable) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }
            val safeBrightness = if (currentBrightness in 25..255) currentBrightness else 128
            sp.edit()
                .putInt(KEY_SAVED_BRIGHTNESS, safeBrightness)
                .putInt(KEY_SAVED_MODE, currentMode)
                .apply()
        }

        // 2. Register disconnect broadcast receiver (unplugging USB cable / power disconnected)
        registerDisconnectReceiver(appCtx)

        // 3. Attempt direct system setting dim if WRITE_SETTINGS is granted
        try {
            val resolver = appCtx.contentResolver
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(appCtx)) {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 1)
            }
        } catch (_: Throwable) {
        }

        // 4. Request privileged hardware panel power-off (`requestDisplayPower(STATE_OFF)`) via Bridge
        sendBridgeBrightnessCommand(
            context = appCtx,
            dim = true,
            keepSessionAwake = true,
            restoreBrightness = -1,
            restoreMode = -1
        ) { panelOffOk ->
            isHardwarePanelOffActive = panelOffOk
            mainHandler.post {
                if (!isDimmed) return@post
                dimActiveActivitiesOnPhone(appCtx, attachBlackScrim = !panelOffOk)
                if (!panelOffOk) {
                    showDimOverlayIfPermitted(appCtx)
                } else {
                    removeDimOverlay()
                }
            }
        }

        // 5. Immediately apply window brightness & wake flags on UI thread
        if (!alreadyDimmed) {
            mainHandler.post {
                if (isDimmed) {
                    dimActiveActivitiesOnPhone(appCtx, attachBlackScrim = true)
                }
            }
        }
    }

    /**
     * Restores original phone screen power and brightness, removes black scrims, and clears wake flags.
     */
    fun restorePhoneScreen(
        context: Context,
        keepSessionAwake: Boolean = true,
        onSendBridgeCommand: ((String, Bundle) -> Bundle?)? = null
    ) {
        if (onSendBridgeCommand != null) {
            this.bridgeInvoker = onSendBridgeCommand
        }
        val wasDimmed = isDimmed
        isDimmed = false
        isHardwarePanelOffActive = false

        val appCtx = context.applicationContext

        // 1. Unregister disconnect broadcast receiver if session is ending
        if (!keepSessionAwake) {
            unregisterDisconnectReceiver(appCtx)
            releasePhoneScreenWakeLock()
        }

        // 2. Retrieve original system brightness from preferences (guaranteed >= 40, default 128)
        val sp = appCtx.getSharedPreferences(PREFS_DIM, Context.MODE_PRIVATE)
        val hasSaved = sp.contains(KEY_SAVED_BRIGHTNESS)
        val restoreBrightness = sp.getInt(KEY_SAVED_BRIGHTNESS, 128).coerceIn(40, 255)
        val restoreMode = sp.getInt(KEY_SAVED_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        if (hasSaved) {
            sp.edit().remove(KEY_SAVED_BRIGHTNESS).remove(KEY_SAVED_MODE).apply()
        }

        // 3. Restore system settings directly if WRITE_SETTINGS is granted
        if (wasDimmed || hasSaved) {
            try {
                val resolver = appCtx.contentResolver
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(appCtx)) {
                    Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, restoreBrightness)
                    if (restoreMode in 0..1) {
                        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, restoreMode)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 4. Restore privileged panel power & system brightness via Bridge (Shizuku / Root)
        sendBridgeBrightnessCommand(
            context = appCtx,
            dim = false,
            keepSessionAwake = keepSessionAwake,
            restoreBrightness = restoreBrightness,
            restoreMode = restoreMode,
            onResult = null
        )

        // 5. Restore UI thread activity window flags & remove scrims and overlay
        mainHandler.post {
            restoreActivitiesOnPhone()
            removeDimOverlay()
        }
    }

    /**
     * Toggles phone screen-off / dimming state (used by quick menu button or double-tap gesture).
     */
    fun toggleDim(context: Context) {
        if (isDimmed) {
            restorePhoneScreen(context, keepSessionAwake = true)
        } else {
            dimPhoneScreen(context)
        }
    }

    /**
     * Applies dimming attributes and optional AMOLED black scrim to an Activity running on the phone's primary display.
     */
    fun applyDimToActivityWindow(activity: Activity, attachBlackScrim: Boolean = !isHardwarePanelOffActive) {
        if (!isDimmed || activity.isFinishing || activity.isDestroyed) return
        mainHandler.post {
            try {
                if (!isDimmed || activity.isFinishing || activity.isDestroyed) return@post
                val actDisplayId = activity.window?.decorView?.display?.displayId ?: activity.display?.displayId
                if (actDisplayId != null && actDisplayId != Display.DEFAULT_DISPLAY) {
                    // Never dim or cover activities running on Car VirtualDisplays!
                    return@post
                }
                val win = activity.window ?: return@post
                val lp = win.attributes ?: return@post
                if (!originalWindowBrightnessMap.containsKey(activity)) {
                    originalWindowBrightnessMap[activity] = lp.screenBrightness
                }
                lp.screenBrightness = 0.001f // Minimum non-zero float to turn off backlight without triggering OS sleep
                win.attributes = lp
                win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val decor = win.decorView as? ViewGroup ?: return@post
                val existingScrim = decor.findViewWithTag<View>(TAG_DIM_SCRIM)
                if (attachBlackScrim) {
                    if (existingScrim == null) {
                        val scrim = createDimScrimView(activity).apply {
                            tag = TAG_DIM_SCRIM
                        }
                        decor.addView(
                            scrim,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        scrim.bringToFront()
                    } else {
                        existingScrim.bringToFront()
                    }
                } else if (existingScrim != null) {
                    decor.removeView(existingScrim)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun dimActiveActivitiesOnPhone(context: Context, attachBlackScrim: Boolean) {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activitiesMap = activitiesField.get(currentAt) as? Map<*, *> ?: return
            for (record in activitiesMap.values) {
                if (record == null) continue
                val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val act = actField.get(record) as? Activity ?: continue
                if (act.isFinishing || act.isDestroyed) continue

                val actDisplayId = act.window?.decorView?.display?.displayId ?: act.display?.displayId
                if (actDisplayId == Display.DEFAULT_DISPLAY || actDisplayId == null) {
                    applyDimToActivityWindow(act, attachBlackScrim)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreActivitiesOnPhone() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activitiesMap = activitiesField.get(currentAt) as? Map<*, *> ?: return
            for (record in activitiesMap.values) {
                if (record == null) continue
                val actField = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                val act = actField.get(record) as? Activity ?: continue
                if (act.isFinishing || act.isDestroyed) continue

                val win = act.window ?: continue
                val oldBrightness = originalWindowBrightnessMap[act]
                    ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                val lp = win.attributes ?: continue
                lp.screenBrightness = oldBrightness
                win.attributes = lp
                win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // Remove DimScrimView from decor
                val decor = win.decorView as? ViewGroup
                val scrim = decor?.findViewWithTag<View>(TAG_DIM_SCRIM)
                if (scrim != null) {
                    decor.removeView(scrim)
                }
            }
        } catch (_: Throwable) {
        }
        originalWindowBrightnessMap.clear()
    }

    /**
     * Creates a full-screen pure black FrameLayout (#000000) with double-tap gesture listener to wake the phone.
     * Hint text auto-hides after 2.5 seconds so 100% of AMOLED pixels turn completely off.
     */
    private fun createDimScrimView(context: Context): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK) // Pure AMOLED black (#000000)
            isClickable = true
            isFocusable = true
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            alpha = 0.75f
        }

        val iconView = TextView(context).apply {
            text = "🌙"
            textSize = 32f
            gravity = Gravity.CENTER
            alpha = 0.65f
        }

        val titleView = TextView(context).apply {
            text = "AAMaKer Dual-App Projection"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0.65f
            setPadding(0, 10, 0, 4)
        }

        val subView = TextView(context).apply {
            text = "Màn hình ĐT đã tắt để chạy 2 App mượt 60 FPS trên xe"
            textSize = 11.5f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            alpha = 0.5f
            setPadding(0, 0, 0, 14)
        }

        val hintPill = TextView(context).apply {
            text = "Chạm 2 lần để bật sáng màn hình điện thoại"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            alpha = 0.75f
            setPadding(28, 14, 28, 14)
            setBackgroundColor(0x28FFFFFF)
        }

        contentLayout.addView(iconView)
        contentLayout.addView(titleView)
        contentLayout.addView(subView)
        contentLayout.addView(hintPill)

        root.addView(
            contentLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val hideHintRunnable = Runnable {
            contentLayout.animate().alpha(0f).setDuration(400).start()
        }
        mainHandler.postDelayed(hideHintRunnable, 2500L)

        // GestureDetector that intercepts double-tap to wake and single-tap to briefly show hint
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                mainHandler.removeCallbacks(hideHintRunnable)
                contentLayout.animate().alpha(0.85f).setDuration(150).start()
                mainHandler.postDelayed(hideHintRunnable, 2200L)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                mainHandler.removeCallbacks(hideHintRunnable)
                restorePhoneScreen(context, keepSessionAwake = true)
                return true
            }
        })

        root.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true // Always consume touches so underlying views on phone are not accidentally clicked
        }

        return root
    }

    private fun showDimOverlayIfPermitted(context: Context) {
        if (!isDimmed || dimOverlayView != null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                return
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val scrim = createDimScrimView(context)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                screenBrightness = 0.001f
            }

            wm.addView(scrim, lp)
            dimOverlayView = scrim
            windowManager = wm
        } catch (_: Throwable) {
        }
    }

    private fun removeDimOverlay() {
        val v = dimOverlayView ?: return
        val wm = windowManager ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        } finally {
            dimOverlayView = null
            windowManager = null
        }
    }

    private fun invokeBridgeCallInternal(appCtx: Context, method: String, bundle: Bundle): Bundle? {
        val invoker = bridgeInvoker
        if (invoker != null) {
            return invoker(method, bundle)
        }
        bridgeTokenProvider?.invoke()?.takeIf { it.isNotEmpty() }?.let { token ->
            bundle.putString("bridge_token", token)
        }
        val pi = PendingIntent.getBroadcast(
            appCtx,
            0,
            Intent("com.aamaker.identity.${appCtx.packageName}").setPackage(appCtx.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        bundle.putParcelable("caller_identity", pi)
        return appCtx.contentResolver.call(bridgeUri, method, null, bundle)
    }

    private fun sendBridgeBrightnessCommand(
        context: Context,
        dim: Boolean,
        keepSessionAwake: Boolean,
        restoreBrightness: Int,
        restoreMode: Int,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        bgExecutor.execute {
            var panelOffApplied = false
            try {
                val bundle = Bundle().apply {
                    putBoolean("dim", dim)
                    putBoolean("keep_session_awake", keepSessionAwake)
                    putInt("restore_brightness", restoreBrightness)
                    putInt("restore_mode", restoreMode)
                }
                val res = invokeBridgeCallInternal(appCtx, METHOD_SET_PHONE_BRIGHTNESS, bundle)
                panelOffApplied = res?.getBoolean("panel_off_applied", false) == true
            } catch (_: Throwable) {
            }
            onResult?.invoke(panelOffApplied)
        }
    }

    private const val METHOD_SET_PHONE_BRIGHTNESS = "set_phone_brightness"

    private fun registerDisconnectReceiver(context: Context) {
        if (disconnectReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    when (action) {
                        Intent.ACTION_POWER_DISCONNECTED -> {
                            restorePhoneScreen(context, keepSessionAwake = false)
                        }
                        "android.hardware.usb.action.USB_STATE" -> {
                            val connected = intent.getBooleanExtra("connected", true)
                            if (!connected) {
                                restorePhoneScreen(context, keepSessionAwake = false)
                            }
                        }
                        "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED" -> {
                            val connType = intent.getIntExtra("connection_type", -1)
                            if (connType == 0) { // CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                                restorePhoneScreen(context, keepSessionAwake = false)
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction("android.hardware.usb.action.USB_STATE")
                addAction("androidx.car.app.connection.action.CAR_CONNECTION_UPDATED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            disconnectReceiver = receiver
        } catch (_: Throwable) {
        }
    }

    private fun unregisterDisconnectReceiver(context: Context) {
        val r = disconnectReceiver ?: return
        try {
            context.unregisterReceiver(r)
        } catch (_: Throwable) {
        } finally {
            disconnectReceiver = null
        }
    }
}
