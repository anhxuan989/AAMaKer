package com.aamaker.stub.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dual-Window (Split-Screen) Session Controller & Compositor for AAMaKer Car Runtime.
 *
 * Supports BOTH:
 * 1. Auto-starting in Dual-Window mode when patched with `DUAL_WINDOW_MODE` (2-App Preset APK).
 * 2. Toggling Split-Screen on the fly `[◫]` from any `UNIVERSAL_INJECTION` patched app on the car screen,
 *    with an interactive on-car App Picker, Split Ratio switcher (50:50 / 60:40 / 70:30),
 *    Left ⇄ Right Swap, and independent per-pane Back buttons.
 */
internal class DualWindowController(
    private val context: Context,
    private val bridgeTokenProvider: () -> String?,
    private val langProvider: () -> String,
    private val onRequestRepaint: () -> Unit,
    private val onPrimaryDisplayResizeRequested: (width: Int, height: Int, dpi: Int) -> Unit
) {

    data class LaunchableAppItem(
        val packageName: String,
        val label: String
    )

    companion object {
        const val DENSITY_MODE_STANDARD = 0
        const val DENSITY_MODE_HIGH = 1
        const val DENSITY_MODE_ULTRA = 2
        private const val PREFS_DUAL = "aamaker_dual_prefs"
        private const val KEY_DENSITY_MODE = "split_density_mode"
    }

    private val bridgeUri: Uri = Uri.parse("content://com.aamaker.app.dualbridge")

    @Volatile
    var isDualModeActive: Boolean = false
        private set

    @Volatile
    var splitRatio: Float = 0.50f
        private set

    @Volatile
    var isSwapped: Boolean = false
        private set

    @Volatile
    var secondaryPackageName: String = ""
        private set

    @Volatile
    var secondaryAppLabel: String = ""
        private set

    @Volatile
    var mixAudioFocus: Boolean = true
        private set

    @Volatile
    var densityMode: Int = DENSITY_MODE_HIGH
        private set

    @Volatile
    var secondaryDisplayId: Int = Display.INVALID_DISPLAY
        private set

    @Volatile
    var isControlMenuExpanded: Boolean = false
        private set

    @Volatile
    var isAppPickerOpen: Boolean = false
        private set

    private var appPickerItems: List<LaunchableAppItem> = emptyList()
    private var appPickerPage: Int = 0

    private var totalWidth: Int = 0
    private var totalHeight: Int = 0
    private var carDpi: Int = 160

    private var secondaryImageReader: ImageReader? = null
    private var secondaryFrameBitmap: Bitmap? = null
    private var secondaryValidWidth: Int = 0
    private var secondaryValidHeight: Int = 0
    private val secondaryBitmapLock = Any()

    @Volatile
    private var lastSecondaryFrameTimeMs: Long = 0L

    @Volatile
    private var isConnectingBridge: Boolean = false

    private var retryConnectAttempts: Int = 0
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var inputThread: HandlerThread? = null
    private var inputHandler: Handler? = null

    // Secondary scroll/drag state
    private var secScrollActive: Boolean = false
    private var secScrollDownTime: Long = 0L
    private var secScrollStartX: Float = 0f
    private var secScrollStartY: Float = 0f
    private var secScrollX: Float = 0f
    private var secScrollY: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1120")
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }
    private val dividerAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.FILL
    }
    private val overlayCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EB0F172A")
        style = Paint.Style.FILL
    }
    private val overlayStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8038BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textAlign = Paint.Align.CENTER
    }

    init {
        loadManifestDefaults()
    }

    private fun loadManifestDefaults() {
        try {
            val appCtx = context.applicationContext
            val ai = appCtx.packageManager.getApplicationInfo(
                appCtx.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            val meta = ai.metaData ?: return
            val autoDual = meta.getBoolean("com.aamaker.DUAL_AUTO_START", false)
            val secPkg = meta.getString("com.aamaker.DUAL_SECONDARY_PKG")?.trim().orEmpty()
            val ratioStr = meta.getString("com.aamaker.DUAL_SPLIT_RATIO")?.trim()
            val parsedRatio = ratioStr?.toFloatOrNull()?.coerceIn(0.30f, 0.70f) ?: 0.50f
            val mixAudio = meta.getBoolean("com.aamaker.DUAL_MIX_AUDIO", true)

            splitRatio = parsedRatio
            mixAudioFocus = mixAudio
            val sp = appCtx.getSharedPreferences(PREFS_DUAL, Context.MODE_PRIVATE)
            densityMode = sp.getInt(KEY_DENSITY_MODE, DENSITY_MODE_HIGH)
            if (secPkg.isNotEmpty()) {
                secondaryPackageName = secPkg
                secondaryAppLabel = resolvePackageLabel(secPkg)
            }
            if (autoDual) {
                isDualModeActive = true
            }
        } catch (_: Throwable) {
        }
    }

    private fun resolvePackageLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
        } catch (_: Throwable) {
            pkg.substringAfterLast('.')
        }
    }

    private fun getOrCreateWorkerHandler(): Handler {
        workerHandler?.let { return it }
        val t = HandlerThread("AAMakerDualWorker").apply { start() }
        val h = Handler(t.looper)
        workerThread = t
        workerHandler = h
        return h
    }

    private fun getOrCreateInputHandler(): Handler {
        inputHandler?.let { return it }
        val t = HandlerThread("AAMakerDualInput", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val h = Handler(t.looper)
        inputThread = t
        inputHandler = h
        return h
    }

    private fun density(): Float = (carDpi.toFloat() / 160f).coerceIn(1f, 3f)

    fun dividerThicknessPx(): Int = (12f * density()).roundToInt().coerceAtLeast(10)

    /**
     * Computes adaptive virtual DPI based on pane width and active densityMode.
     * DENSITY_MODE_HIGH: ~460dp target width (higher information density, compact elements).
     * DENSITY_MODE_ULTRA: ~540dp target width (maximum information density / tablet multi-column layout).
     * DENSITY_MODE_STANDARD: ~380dp target width (standard phone layout).
     */
    fun computeAdaptivePaneDpi(paneWidthPx: Int): Int {
        if (paneWidthPx <= 0) return carDpi
        val targetDp = when (densityMode) {
            DENSITY_MODE_ULTRA -> 540f
            DENSITY_MODE_HIGH -> 460f
            else -> 380f
        }
        val idealDpi = ((paneWidthPx.toFloat() / targetDp) * 160f).roundToInt()
        return idealDpi.coerceIn(100, 480)
    }

    fun setDensityMode(newMode: Int) {
        val clamped = newMode.coerceIn(DENSITY_MODE_STANDARD, DENSITY_MODE_ULTRA)
        if (densityMode == clamped) return
        densityMode = clamped
        try {
            context.applicationContext.getSharedPreferences(PREFS_DUAL, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_DENSITY_MODE, clamped)
                .apply()
        } catch (_: Throwable) {
        }
        if (isDualModeActive && totalWidth > 0 && totalHeight > 0) {
            reconfigureDisplaysForCurrentSplit(launchSecondaryIfReady = false)
        }
        onRequestRepaint()
    }

    fun sendBridgeCommand(method: String, extras: Bundle): Bundle? {
        return try {
            attachAuthToBundle(extras)
            context.applicationContext.contentResolver.call(
                bridgeUri,
                method,
                null,
                extras
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun getPrimaryPaneWidth(): Int {
        if (!isDualModeActive || totalWidth <= 0) return totalWidth
        val usableW = (totalWidth - dividerThicknessPx()).coerceAtLeast(320)
        val leftW = (usableW * splitRatio).roundToInt().coerceIn(160, usableW - 160)
        val rightW = usableW - leftW
        return if (!isSwapped) leftW else rightW
    }

    fun getSecondaryPaneWidth(): Int {
        if (!isDualModeActive || totalWidth <= 0) return 0
        val usableW = (totalWidth - dividerThicknessPx()).coerceAtLeast(320)
        return (usableW - getPrimaryPaneWidth()).coerceAtLeast(160)
    }

    fun getLeftPaneWidth(): Int {
        if (!isDualModeActive || totalWidth <= 0) return totalWidth
        val usableW = (totalWidth - dividerThicknessPx()).coerceAtLeast(320)
        return (usableW * splitRatio).roundToInt().coerceIn(160, usableW - 160)
    }

    fun getDividerCenterX(): Float {
        return getLeftPaneWidth() + dividerThicknessPx() * 0.5f
    }

    fun getPrimaryPaneRect(): Rect {
        if (!isDualModeActive) return Rect(0, 0, totalWidth, totalHeight)
        val leftW = getLeftPaneWidth()
        val div = dividerThicknessPx()
        return if (!isSwapped) {
            Rect(0, 0, leftW, totalHeight)
        } else {
            Rect(leftW + div, 0, totalWidth, totalHeight)
        }
    }

    fun getSecondaryPaneRect(): Rect {
        if (!isDualModeActive) return Rect(0, 0, 0, 0)
        val leftW = getLeftPaneWidth()
        val div = dividerThicknessPx()
        return if (!isSwapped) {
            Rect(leftW + div, 0, totalWidth, totalHeight)
        } else {
            Rect(0, 0, leftW, totalHeight)
        }
    }

    fun hasFreshSecondaryFrame(): Boolean {
        return isDualModeActive &&
            secondaryDisplayId > Display.DEFAULT_DISPLAY &&
            (SystemClock.uptimeMillis() - lastSecondaryFrameTimeMs) < 10_000L &&
            secondaryFrameBitmap != null
    }

    fun prepareInitialDimensions(width: Int, height: Int, dpi: Int) {
        totalWidth = width
        totalHeight = height
        carDpi = dpi.takeIf { it > 0 } ?: 160
    }

    fun onCarSurfaceReady(width: Int, height: Int, dpi: Int, primaryAlreadySized: Boolean = false) {
        totalWidth = width
        totalHeight = height
        carDpi = dpi.takeIf { it > 0 } ?: 160
        if (isDualModeActive) {
            reconfigureDisplaysForCurrentSplit(launchSecondaryIfReady = true, skipPrimaryResize = primaryAlreadySized)
        }
    }

    fun toggleDualMode() {
        isDualModeActive = !isDualModeActive
        isControlMenuExpanded = false
        if (isDualModeActive) {
            reconfigureDisplaysForCurrentSplit(launchSecondaryIfReady = true)
            if (secondaryPackageName.isBlank()) {
                openSecondaryAppPicker()
            }
        } else {
            isAppPickerOpen = false
            teardownSecondaryDisplayAsync()
            if (totalWidth > 0 && totalHeight > 0) {
                onPrimaryDisplayResizeRequested(totalWidth, totalHeight, carDpi)
            }
        }
        onRequestRepaint()
    }

    fun setSplitRatioPreset(newRatio: Float) {
        val clamped = newRatio.coerceIn(0.30f, 0.70f)
        if (kotlin.math.abs(clamped - splitRatio) < 0.01f) return
        splitRatio = clamped
        if (isDualModeActive && totalWidth > 0 && totalHeight > 0) {
            reconfigureDisplaysForCurrentSplit(launchSecondaryIfReady = false)
        }
        onRequestRepaint()
    }

    fun swapPanes() {
        isSwapped = !isSwapped
        splitRatio = (1f - splitRatio).coerceIn(0.30f, 0.70f)
        if (isDualModeActive && totalWidth > 0 && totalHeight > 0) {
            reconfigureDisplaysForCurrentSplit(launchSecondaryIfReady = false)
        }
        onRequestRepaint()
    }

    private fun reconfigureDisplaysForCurrentSplit(
        launchSecondaryIfReady: Boolean,
        skipPrimaryResize: Boolean = false
    ) {
        if (totalWidth <= 0 || totalHeight <= 0) return
        if (!skipPrimaryResize) {
            val priW = getPrimaryPaneWidth()
            val priDpi = computeAdaptivePaneDpi(priW)
            onPrimaryDisplayResizeRequested(priW, totalHeight, priDpi)
        }

        val secW = getSecondaryPaneWidth()
        val secH = totalHeight
        val secDpi = computeAdaptivePaneDpi(secW)

        getOrCreateWorkerHandler().post {
            setupOrResizeSecondaryDisplayInternal(secW, secH, secDpi, launchSecondaryIfReady)
        }
    }

    private fun attachAuthToBundle(bundle: Bundle) {
        val appCtx = context.applicationContext
        bridgeTokenProvider()?.takeIf { it.isNotEmpty() }?.let { token ->
            bundle.putString("bridge_token", token)
        }
        try {
            val pi = PendingIntent.getBroadcast(
                appCtx,
                0,
                Intent("com.aamaker.identity.${appCtx.packageName}").setPackage(appCtx.packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            bundle.putParcelable("caller_identity", pi)
        } catch (_: Throwable) {
        }
    }

    private fun setupOrResizeSecondaryDisplayInternal(
        secW: Int,
        secH: Int,
        secDpi: Int,
        launchApp: Boolean
    ) {
        if (!isDualModeActive || secW <= 0 || secH <= 0) return
        isConnectingBridge = true
        onRequestRepaint()
        try {
            val existingReader = secondaryImageReader
            val reader = if (existingReader != null && existingReader.width == secW && existingReader.height == secH) {
                existingReader
            } else {
                existingReader?.close()
                ImageReader.newInstance(secW, secH, PixelFormat.RGBA_8888, 2).apply {
                    setOnImageAvailableListener({ r ->
                        val image = try {
                            r.acquireLatestImage()
                        } catch (_: Throwable) {
                            null
                        } ?: return@setOnImageAvailableListener
                        try {
                            val planes = image.planes
                            if (planes.isNotEmpty()) {
                                val imgW = image.width.takeIf { it > 0 } ?: secW
                                val imgH = image.height.takeIf { it > 0 } ?: secH
                                val plane = planes[0]
                                val pixelStride = plane.pixelStride
                                val rowStride = plane.rowStride
                                val rowPadding = rowStride - pixelStride * imgW
                                val strideW = imgW + (if (pixelStride > 0) rowPadding / pixelStride else 0)
                                if (strideW >= imgW && imgH > 0) {
                                    val buf = plane.buffer
                                    synchronized(secondaryBitmapLock) {
                                        var bmp = secondaryFrameBitmap
                                        val hasExistingValidFrame = bmp != null && !bmp.isRecycled && lastSecondaryFrameTimeMs > 0L
                                        if (hasExistingValidFrame && isBufferCompletelyBlankOrBlack(buf, imgW, imgH, pixelStride, rowStride)) {
                                            // Ignore transient all-black/blank occlusion frame emitted when Keyguard or Power button transitions
                                            return@setOnImageAvailableListener
                                        }
                                        if (bmp == null || bmp.isRecycled || bmp.width != strideW || bmp.height != imgH) {
                                            bmp?.recycle()
                                            bmp = Bitmap.createBitmap(strideW, imgH, Bitmap.Config.ARGB_8888)
                                            secondaryFrameBitmap = bmp
                                        }
                                        buf.rewind()
                                        bmp?.copyPixelsFromBuffer(buf)
                                        secondaryValidWidth = imgW
                                        secondaryValidHeight = imgH
                                        lastSecondaryFrameTimeMs = SystemClock.uptimeMillis()
                                    }
                                    onRequestRepaint()
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            runCatching { image.close() }
                        }
                    }, getOrCreateWorkerHandler())
                }.also { secondaryImageReader = it }
            }

            val req = Bundle().apply {
                putParcelable("surface", reader.surface)
                putInt("width", secW)
                putInt("height", secH)
                putInt("dpi", secDpi)
                putString("slot", "secondary")
                attachAuthToBundle(this)
            }
            val res = context.applicationContext.contentResolver.call(
                bridgeUri,
                "create_secondary_display",
                null,
                req
            )
            val dispId = res?.getInt("display_id", Display.INVALID_DISPLAY) ?: Display.INVALID_DISPLAY
            secondaryDisplayId = dispId

            if (dispId > Display.DEFAULT_DISPLAY) {
                retryConnectAttempts = 0
                if (launchApp || lastSecondaryFrameTimeMs == 0L) {
                    val pkg = secondaryPackageName
                    if (pkg.isNotBlank()) {
                        launchSecondaryPackageInternal(dispId, pkg)
                    }
                }
            } else if (isDualModeActive && retryConnectAttempts < 3) {
                retryConnectAttempts++
                getOrCreateWorkerHandler().postDelayed({
                    if (isDualModeActive && secondaryDisplayId <= Display.DEFAULT_DISPLAY) {
                        setupOrResizeSecondaryDisplayInternal(secW, secH, secDpi, launchApp)
                    }
                }, 1400L)
            }
        } catch (_: Throwable) {
        } finally {
            isConnectingBridge = false
            onRequestRepaint()
        }
    }

    private fun launchSecondaryPackageInternal(displayId: Int, targetPkg: String) {
        if (displayId <= Display.DEFAULT_DISPLAY || targetPkg.isBlank()) return
        try {
            val req = Bundle().apply {
                putInt("display_id", displayId)
                putString("target_package", targetPkg)
                putBoolean("mix_audio", mixAudioFocus)
                attachAuthToBundle(this)
            }
            context.applicationContext.contentResolver.call(
                bridgeUri,
                "launch_secondary_app",
                null,
                req
            )
        } catch (_: Throwable) {
        }
    }

    fun selectAndLaunchSecondaryApp(pkg: String, label: String) {
        secondaryPackageName = pkg
        secondaryAppLabel = label.ifBlank { resolvePackageLabel(pkg) }
        isAppPickerOpen = false
        isControlMenuExpanded = false
        getOrCreateWorkerHandler().post {
            val dispId = secondaryDisplayId
            if (dispId > Display.DEFAULT_DISPLAY) {
                launchSecondaryPackageInternal(dispId, pkg)
            } else {
                val secW = getSecondaryPaneWidth()
                val secDpi = computeAdaptivePaneDpi(secW)
                setupOrResizeSecondaryDisplayInternal(secW, totalHeight, secDpi, launchApp = true)
            }
        }
        onRequestRepaint()
    }

    /**
     * Fast 25-point grid sample on the incoming RGBA_8888 ByteBuffer to detect if SurfaceFlinger
     * emitted a completely blank/black frame (`RGB == 0`) during Keyguard lock or display power transition.
     */
    private fun isBufferCompletelyBlankOrBlack(
        buf: java.nio.ByteBuffer,
        imgW: Int,
        imgH: Int,
        pixelStride: Int,
        rowStride: Int
    ): Boolean {
        if (imgW < 16 || imgH < 16 || pixelStride < 4 || rowStride < imgW * 4) return false
        return try {
            val limit = buf.limit()
            val sampleFractions = floatArrayOf(0.15f, 0.32f, 0.50f, 0.68f, 0.85f)
            for (fy in sampleFractions) {
                val y = (imgH * fy).toInt().coerceIn(0, imgH - 1)
                val rowOffset = y * rowStride
                for (fx in sampleFractions) {
                    val x = (imgW * fx).toInt().coerceIn(0, imgW - 1)
                    val offset = rowOffset + x * pixelStride
                    if (offset + 3 >= limit) continue
                    val r = buf.get(offset).toInt() and 0xFF
                    val g = buf.get(offset + 1).toInt() and 0xFF
                    val b = buf.get(offset + 2).toInt() and 0xFF
                    if (r > 2 || g > 2 || b > 2) {
                        return false
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun ensureSecondaryAliveWhenScreenOff() {
        if (!isDualModeActive) return
        val pkg = secondaryPackageName
        if (pkg.isBlank()) return
        val worker = getOrCreateWorkerHandler()
        val relaunchTask = Runnable {
            if (!isDualModeActive || secondaryPackageName.isBlank()) return@Runnable
            val dispId = secondaryDisplayId
            val ageMs = SystemClock.uptimeMillis() - lastSecondaryFrameTimeMs
            if (dispId > Display.DEFAULT_DISPLAY) {
                if (lastSecondaryFrameTimeMs == 0L || ageMs > 900L) {
                    launchSecondaryPackageInternal(dispId, secondaryPackageName)
                }
            } else if (totalWidth > 0 && totalHeight > 0) {
                val secW = getSecondaryPaneWidth()
                val secDpi = computeAdaptivePaneDpi(secW)
                setupOrResizeSecondaryDisplayInternal(secW, totalHeight, secDpi, launchApp = true)
            }
            onRequestRepaint()
        }
        worker.post(relaunchTask)
        worker.postDelayed(relaunchTask, 550L)
    }

    fun openSecondaryAppPicker() {
        isAppPickerOpen = true
        isControlMenuExpanded = false
        appPickerPage = 0
        onRequestRepaint()
        getOrCreateWorkerHandler().post {
            try {
                val req = Bundle().apply { attachAuthToBundle(this) }
                val res = context.applicationContext.contentResolver.call(
                    bridgeUri,
                    "list_launchable_apps",
                    null,
                    req
                )
                val pkgs = res?.getStringArrayList("packages").orEmpty()
                val labels = res?.getStringArrayList("labels").orEmpty()
                val list = ArrayList<LaunchableAppItem>(pkgs.size)
                for (i in pkgs.indices) {
                    val p = pkgs[i]
                    val l = labels.getOrNull(i) ?: p
                    list.add(LaunchableAppItem(p, l))
                }
                appPickerItems = list
                onRequestRepaint()
            } catch (_: Throwable) {
            }
        }
    }

    fun sendBackToSecondaryDisplay() {
        val dispId = secondaryDisplayId
        if (dispId <= Display.DEFAULT_DISPLAY) return
        getOrCreateInputHandler().post {
            try {
                val req = Bundle().apply {
                    putInt("display_id", dispId)
                    putInt("key_code", KeyEvent.KEYCODE_BACK)
                    attachAuthToBundle(this)
                }
                context.applicationContext.contentResolver.call(
                    bridgeUri,
                    "inject_secondary_key",
                    null,
                    req
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun injectMotionToSecondaryAsync(action: Int, downTime: Long, eventTime: Long, localX: Float, localY: Float) {
        val dispId = secondaryDisplayId
        if (dispId <= Display.DEFAULT_DISPLAY) return
        getOrCreateInputHandler().post {
            try {
                val req = Bundle().apply {
                    putInt("display_id", dispId)
                    putInt("action", action)
                    putLong("down_time", downTime)
                    putLong("event_time", eventTime)
                    putFloat("x", localX)
                    putFloat("y", localY)
                    attachAuthToBundle(this)
                }
                context.applicationContext.contentResolver.call(
                    bridgeUri,
                    "inject_secondary_motion",
                    null,
                    req
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun injectTapToSecondaryAsync(localX: Float, localY: Float) {
        val dispId = secondaryDisplayId
        if (dispId <= Display.DEFAULT_DISPLAY) return
        getOrCreateInputHandler().post {
            try {
                val req = Bundle().apply {
                    putInt("display_id", dispId)
                    putString("gesture_type", "tap")
                    putFloat("x", localX)
                    putFloat("y", localY)
                    attachAuthToBundle(this)
                }
                context.applicationContext.contentResolver.call(
                    bridgeUri,
                    "inject_secondary_motion",
                    null,
                    req
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun injectSwipeToSecondaryAsync(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ) {
        val dispId = secondaryDisplayId
        if (dispId <= Display.DEFAULT_DISPLAY) return
        getOrCreateInputHandler().post {
            try {
                val req = Bundle().apply {
                    putInt("display_id", dispId)
                    putString("gesture_type", "swipe")
                    putFloat("x", startX)
                    putFloat("y", startY)
                    putFloat("x2", endX)
                    putFloat("y2", endY)
                    putLong("duration_ms", durationMs)
                    attachAuthToBundle(this)
                }
                context.applicationContext.contentResolver.call(
                    bridgeUri,
                    "inject_secondary_motion",
                    null,
                    req
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun isModalOrMenuOpen(): Boolean = isDualModeActive && (isAppPickerOpen || isControlMenuExpanded)

    fun isCarPointOnSecondarySide(rawX: Float, rawY: Float): Boolean {
        if (!isDualModeActive || totalWidth <= 0) return false
        val leftW = getLeftPaneWidth().toFloat()
        val divW = dividerThicknessPx().toFloat()
        val divCx = leftW + divW * 0.5f
        return if (!isSwapped) {
            rawX >= (divCx - 4f)
        } else {
            rawX <= (divCx + 4f)
        }
    }

    /**
     * Hit-test and handle clicks on:
     * 1. Bottom-left Split-Screen toggle button `[◫]` (available in both Single & Dual mode).
     * 2. App Picker modal (when selecting 2nd app).
     * 3. Center Divider Pill & Expanded Split Quick Toolbar (Ratio / Swap / Back / Pick App / Exit Split).
     * 4. Secondary App Pane (routes tap to Secondary VirtualDisplay).
     *
     * @return `true` if DualWindowController consumed the click; `false` if caller should route to Primary App!
     */
    fun handleCarClick(
        rawX: Float,
        rawY: Float,
        onSendPrimaryBack: () -> Unit
    ): Boolean {
        val d = density()

        if (!isDualModeActive) return false

        // 1. Handle App Picker modal if open
        if (isAppPickerOpen) {
            handleAppPickerClick(rawX, rawY)
            return true
        }

        // 3. Handle Expanded Split Quick Menu if open
        if (isControlMenuExpanded) {
            if (handleQuickMenuClick(rawX, rawY, onSendPrimaryBack)) {
                return true
            }
            isControlMenuExpanded = false
            onRequestRepaint()
            return true
        }

        // 4. Check Center Divider Handle Pill hit
        val divX = getDividerCenterX()
        val handleHalfW = 24f * d
        val handleHalfH = 44f * d
        val cy = totalHeight * 0.5f
        if (rawX in (divX - handleHalfW)..(divX + handleHalfW) &&
            rawY in (cy - handleHalfH)..(cy + handleHalfH)
        ) {
            isControlMenuExpanded = true
            onRequestRepaint()
            return true
        }

        // 5. Check if tap falls inside Secondary Pane or on Secondary Side
        if (isCarPointOnSecondarySide(rawX, rawY)) {
            if (secondaryPackageName.isBlank()) {
                openSecondaryAppPicker()
                return true
            }
            if (secondaryDisplayId <= Display.DEFAULT_DISPLAY) {
                reconfigureDisplaysForCurrentSplit(launchSecondaryIfReady = true)
                return true
            }
            val secRect = getSecondaryPaneRect()
            val localX = (rawX - secRect.left).coerceIn(0f, max(1f, secRect.width().toFloat() - 1f))
            val localY = (rawY - secRect.top).coerceIn(0f, max(1f, secRect.height().toFloat() - 1f))
            injectTapToSecondaryAsync(localX, localY)
            return true
        }

        return false
    }

    /**
     * Translates car coordinates into Primary Display coordinates `[0..primaryWidth]`.
     */
    fun mapCarPointToPrimaryLocal(rawX: Float, rawY: Float): Pair<Float, Float> {
        if (!isDualModeActive) return rawX to rawY
        val priRect = getPrimaryPaneRect()
        val lx = (rawX - priRect.left).coerceIn(0f, max(1f, priRect.width().toFloat() - 1f))
        val ly = (rawY - priRect.top).coerceIn(0f, max(1f, priRect.height().toFloat() - 1f))
        return lx to ly
    }

    fun handleSecondaryScrollIfTargeted(focusX: Float, distanceX: Float, distanceY: Float): Boolean {
        if (!isDualModeActive || isAppPickerOpen || isControlMenuExpanded) return false
        if (!isCarPointOnSecondarySide(focusX, totalHeight * 0.5f)) return false
        if (secondaryDisplayId <= Display.DEFAULT_DISPLAY) return true

        val secRect = getSecondaryPaneRect()
        val paneW = secRect.width().toFloat().coerceAtLeast(100f)
        val paneH = secRect.height().toFloat().coerceAtLeast(100f)
        val now = SystemClock.uptimeMillis()
        if (!secScrollActive) {
            secScrollActive = true
            secScrollDownTime = now
            secScrollStartX = paneW * 0.5f
            secScrollStartY = paneH * 0.5f
            secScrollX = secScrollStartX
            secScrollY = secScrollStartY
            injectMotionToSecondaryAsync(MotionEvent.ACTION_DOWN, secScrollDownTime, now, secScrollX, secScrollY)
        }
        secScrollX = (secScrollX - distanceX * 1.5f).coerceIn(16f, paneW - 16f)
        secScrollY = (secScrollY - distanceY * 1.5f).coerceIn(16f, paneH - 16f)
        injectMotionToSecondaryAsync(MotionEvent.ACTION_MOVE, secScrollDownTime, now, secScrollX, secScrollY)

        val handler = getOrCreateInputHandler()
        handler.removeCallbacks(finishSecScrollRunnable)
        handler.postDelayed(finishSecScrollRunnable, 95L)
        return true
    }

    fun cancelActiveSecondaryScroll() {
        if (!secScrollActive) return
        secScrollActive = false
        val handler = getOrCreateInputHandler()
        handler.removeCallbacks(finishSecScrollRunnable)
        val now = SystemClock.uptimeMillis()
        injectMotionToSecondaryAsync(MotionEvent.ACTION_CANCEL, secScrollDownTime, now, secScrollX, secScrollY)
    }

    private val finishSecScrollRunnable = Runnable {
        if (!secScrollActive) return@Runnable
        secScrollActive = false
        val up = SystemClock.uptimeMillis()
        injectMotionToSecondaryAsync(MotionEvent.ACTION_UP, secScrollDownTime, up, secScrollX, secScrollY)
        val dx = kotlin.math.abs(secScrollX - secScrollStartX)
        val dy = kotlin.math.abs(secScrollY - secScrollStartY)
        if (dx > 8f || dy > 8f) {
            injectSwipeToSecondaryAsync(secScrollStartX, secScrollStartY, secScrollX, secScrollY, 90L)
        }
    }

    fun isSplitToggleButtonHit(x: Float, y: Float): Boolean = false

    private fun getSplitToggleFabRect(): RectF {
        val d = density()
        val btnSize = 32f * d
        val margin = 12f * d
        val gap = 8f * d
        val left = margin + btnSize + gap
        val top = totalHeight - margin - btnSize
        return RectF(left, top, left + btnSize, top + btnSize)
    }

    fun drawSplitToggleFab(canvas: Canvas, isInteracting: Boolean = true) {
        // Redundant bottom-left button removed per Minimalist Car Screen UI rules
    }

    /**
     * Composites Primary Pane bitmap + Divider + Secondary Pane bitmap + Floating Controls onto Car Surface Canvas.
     */
    fun compositeDualFrame(
        surfaceCanvas: Canvas,
        primaryBitmap: Bitmap?,
        primaryValidWidth: Int,
        primaryValidHeight: Int,
        isInteracting: Boolean = false,
        drawPrimaryOpenButton: ((Canvas, Int, Int) -> Unit)? = null
    ) {
        val w = totalWidth
        val h = totalHeight
        if (w <= 0 || h <= 0) return

        surfaceCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val priRect = getPrimaryPaneRect()
        val secRect = getSecondaryPaneRect()

        // 1. Draw Primary App Pane
        if (primaryBitmap != null && !primaryBitmap.isRecycled && primaryValidWidth > 0 && primaryValidHeight > 0) {
            val src = Rect(0, 0, min(primaryValidWidth, primaryBitmap.width), min(primaryValidHeight, primaryBitmap.height))
            surfaceCanvas.drawBitmap(primaryBitmap, src, priRect, null)
        }

        // 2. Draw Secondary App Pane
        var drewSecondary = false
        synchronized(secondaryBitmapLock) {
            val secBmp = secondaryFrameBitmap
            if (secBmp != null && !secBmp.isRecycled && secondaryDisplayId > Display.DEFAULT_DISPLAY && lastSecondaryFrameTimeMs > 0L) {
                val srcW = min(secondaryValidWidth.takeIf { it > 0 } ?: secRect.width(), secBmp.width)
                val srcH = min(secondaryValidHeight.takeIf { it > 0 } ?: secRect.height(), secBmp.height)
                if (srcW > 0 && srcH > 0) {
                    val src = Rect(0, 0, srcW, srcH)
                    surfaceCanvas.drawBitmap(secBmp, src, secRect, null)
                    drewSecondary = true
                }
            }
        }
        if (!drewSecondary) {
            drawSecondaryPlaceholder(surfaceCanvas, secRect)
        }

        // 3. Draw Center Divider & Pill Handle
        val leftW = getLeftPaneWidth().toFloat()
        val divW = dividerThicknessPx().toFloat()
        surfaceCanvas.drawRect(leftW, 0f, leftW + divW, h.toFloat(), dividerPaint)

        val d = density()
        val pillW = (4.5f * d).coerceAtLeast(4f)
        val pillH = 44f * d
        val divCx = leftW + divW * 0.5f
        val divCy = h * 0.5f
        val pillRect = RectF(divCx - pillW * 0.5f, divCy - pillH * 0.5f, divCx + pillW * 0.5f, divCy + pillH * 0.5f)
        surfaceCanvas.drawRoundRect(pillRect, pillW, pillW, dividerAccentPaint)

        // 4. Draw Quick Control Menu or App Picker Overlay
        if (isAppPickerOpen) {
            drawAppPickerOverlay(surfaceCanvas, w, h)
        } else if (isControlMenuExpanded) {
            drawQuickControlMenu(surfaceCanvas, w, h)
        }
    }

    private fun drawSecondaryPlaceholder(canvas: Canvas, secRect: Rect) {
        val d = density()
        val cx = secRect.exactCenterX()
        val cy = secRect.exactCenterY()
        val lang = langProvider()

        textPaint.textSize = 18f * d
        subTextPaint.textSize = 13f * d

        val title = when {
            secondaryPackageName.isBlank() -> when (lang) {
                "vi" -> "➕ Chạm để chọn Ứng dụng thứ 2"
                "zh" -> "➕ 点击选择第二个应用"
                else -> "➕ Tap to Choose 2nd App"
            }
            isConnectingBridge || retryConnectAttempts in 1..2 -> when (lang) {
                "vi" -> "🔄 Đang khởi tạo màn hình thứ 2..."
                "zh" -> "🔄 正在初始化第二屏幕..."
                else -> "🔄 Initializing 2nd VirtualDisplay..."
            }
            secondaryDisplayId <= Display.DEFAULT_DISPLAY -> when (lang) {
                "vi" -> "⚡ Cần bật Shizuku, Root hoặc LSPosed"
                "zh" -> "⚡ 请启用 Shizuku、Root 或 LSPosed"
                else -> "⚡ Enable Shizuku, Root, or LSPosed"
            }
            else -> when (lang) {
                "vi" -> "🚀 Đang mở ${secondaryAppLabel.ifBlank { secondaryPackageName }}..."
                "zh" -> "🚀 正在启动 ${secondaryAppLabel.ifBlank { secondaryPackageName }}..."
                else -> "🚀 Launching ${secondaryAppLabel.ifBlank { secondaryPackageName }}..."
            }
        }

        val sub = when {
            secondaryPackageName.isBlank() -> when (lang) {
                "vi" -> "Chạy song song Bản đồ, YouTube, Trình duyệt, Camera..."
                else -> "Run Maps, YouTube, Browser, Dashcam side-by-side"
            }
            secondaryDisplayId <= Display.DEFAULT_DISPLAY -> when (lang) {
                "vi" -> "Chạm để thử kết nối lại cầu nối hiển thị đa cửa sổ"
                else -> "Tap to retry privileged dual-display connection"
            }
            else -> secondaryPackageName
        }

        canvas.drawText(title, cx, cy - 8f * d, textPaint)
        canvas.drawText(sub, cx, cy + 18f * d, subTextPaint)
    }

    private data class MenuActionItem(
        val id: String,
        val label: String,
        val active: Boolean = false
    )

    private fun buildQuickMenuActions(): List<MenuActionItem> {
        val lang = langProvider()
        val swapLbl = if (lang == "vi") "⇄ Đổi bên" else "⇄ Swap"
        val pickLbl = if (lang == "vi") "📱 Đổi App 2" else "📱 App 2"
        val backLeftLbl = if (lang == "vi") "◀ Back Trái" else "◀ Back L"
        val backRightLbl = if (lang == "vi") "Back Phải ▶" else "Back R ▶"
        val audioLbl = if (lang == "vi") {
            if (mixAudioFocus) "🔊 Âm: BẬT" else "🔈 Âm: TẮT"
        } else {
            if (mixAudioFocus) "🔊 Audio: ON" else "🔈 Audio: OFF"
        }
        val densityLbl = when (densityMode) {
            DENSITY_MODE_HIGH -> if (lang == "vi") "📐 Mật độ: Cao" else "📐 Density: High"
            DENSITY_MODE_ULTRA -> if (lang == "vi") "📐 Mật độ: Cực đại" else "📐 Density: Max"
            else -> if (lang == "vi") "📐 Mật độ: Chuẩn" else "📐 Density: Std"
        }
        val isDim = PhoneScreenDimManager.isDimmed
        val dimLbl = if (lang == "vi") {
            if (isDim) "🌙 Tắt MH: BẬT" else "🔆 Tắt MH: TẮT"
        } else {
            if (isDim) "🌙 Screen Off: ON" else "🔆 Screen Off: OFF"
        }
        val closeSplitLbl = if (lang == "vi") "✕ Tắt chia đôi" else "✕ Fullscreen"

        return listOf(
            MenuActionItem("ratio_50", "50 : 50", kotlin.math.abs(splitRatio - 0.50f) < 0.03f),
            MenuActionItem("ratio_60", "60 : 40", kotlin.math.abs(splitRatio - 0.60f) < 0.03f),
            MenuActionItem("ratio_70", "70 : 30", kotlin.math.abs(splitRatio - 0.70f) < 0.03f),
            MenuActionItem("toggle_density", densityLbl, densityMode != DENSITY_MODE_STANDARD),
            MenuActionItem("swap", swapLbl, isSwapped),
            MenuActionItem("back_left", backLeftLbl),
            MenuActionItem("back_right", backRightLbl),
            MenuActionItem("pick_app", pickLbl),
            MenuActionItem("toggle_audio", audioLbl, mixAudioFocus),
            MenuActionItem("toggle_dim", dimLbl, isDim),
            MenuActionItem("refresh_panes", if (lang == "vi") "🔄 Làm mới" else "🔄 Refresh"),
            MenuActionItem("exit_split", closeSplitLbl)
        )
    }

    private fun getQuickMenuCardRect(w: Int, h: Int): RectF {
        val d = density()
        val cardW = min(w * 0.90f, 560f * d)
        val cardH = min(h * 0.82f, 230f * d)
        val left = (w - cardW) * 0.5f
        val top = (h - cardH) * 0.5f
        return RectF(left, top, left + cardW, top + cardH)
    }

    private fun drawQuickControlMenu(canvas: Canvas, w: Int, h: Int) {
        val d = density()
        val card = getQuickMenuCardRect(w, h)
        canvas.drawRoundRect(card, 16f * d, 16f * d, overlayCardPaint)
        canvas.drawRoundRect(card, 16f * d, 16f * d, overlayStrokePaint)

        val lang = langProvider()
        val header = if (lang == "vi") "Điều khiển Đa cửa sổ (Split-Screen)" else "Split-Screen Controls"
        textPaint.textSize = 15f * d
        canvas.drawText(header, card.centerX(), card.top + 26f * d, textPaint)

        val actions = buildQuickMenuActions()
        val cols = 4
        val rows = 3
        val padX = 12f * d
        val topOffset = 42f * d
        val gap = 7f * d
        val cellW = (card.width() - padX * 2f - gap * (cols - 1)) / cols
        val cellH = (card.height() - topOffset - 12f * d - gap * (rows - 1)) / rows

        textPaint.textSize = 12f * d
        for ((idx, item) in actions.withIndex()) {
            val r = idx / cols
            val c = idx % cols
            val left = card.left + padX + c * (cellW + gap)
            val top = card.top + topOffset + r * (cellH + gap)
            val rect = RectF(left, top, left + cellW, top + cellH)
            canvas.drawRoundRect(rect, 10f * d, 10f * d, if (item.active) activePillPaint else pillPaint)
            canvas.drawText(item.label, rect.centerX(), rect.centerY() + 4.5f * d, textPaint)
        }
    }

    private fun handleQuickMenuClick(
        x: Float,
        y: Float,
        onSendPrimaryBack: () -> Unit
    ): Boolean {
        val d = density()
        val card = getQuickMenuCardRect(totalWidth, totalHeight)
        if (!card.contains(x, y)) return false

        val actions = buildQuickMenuActions()
        val cols = 4
        val rows = 3
        val padX = 12f * d
        val topOffset = 42f * d
        val gap = 7f * d
        val cellW = (card.width() - padX * 2f - gap * (cols - 1)) / cols
        val cellH = (card.height() - topOffset - 12f * d - gap * (rows - 1)) / rows

        for ((idx, item) in actions.withIndex()) {
            val r = idx / cols
            val c = idx % cols
            val left = card.left + padX + c * (cellW + gap)
            val top = card.top + topOffset + r * (cellH + gap)
            val rect = RectF(left, top, left + cellW, top + cellH)
            if (rect.contains(x, y)) {
                when (item.id) {
                    "ratio_50" -> {
                        isControlMenuExpanded = false
                        setSplitRatioPreset(0.50f)
                    }
                    "ratio_60" -> {
                        isControlMenuExpanded = false
                        setSplitRatioPreset(0.60f)
                    }
                    "ratio_70" -> {
                        isControlMenuExpanded = false
                        setSplitRatioPreset(0.70f)
                    }
                    "toggle_density" -> {
                        val nextMode = (densityMode + 1) % 3
                        setDensityMode(nextMode)
                    }
                    "swap" -> {
                        isControlMenuExpanded = false
                        swapPanes()
                    }
                    "back_left" -> {
                        isControlMenuExpanded = false
                        if (!isSwapped) onSendPrimaryBack() else sendBackToSecondaryDisplay()
                        onRequestRepaint()
                    }
                    "back_right" -> {
                        isControlMenuExpanded = false
                        if (!isSwapped) sendBackToSecondaryDisplay() else onSendPrimaryBack()
                        onRequestRepaint()
                    }
                    "pick_app" -> {
                        openSecondaryAppPicker()
                    }
                    "toggle_audio" -> {
                        mixAudioFocus = !mixAudioFocus
                        val dispId = secondaryDisplayId
                        val pkg = secondaryPackageName
                        if (dispId > Display.DEFAULT_DISPLAY && pkg.isNotBlank()) {
                            getOrCreateWorkerHandler().post { launchSecondaryPackageInternal(dispId, pkg) }
                        }
                        onRequestRepaint()
                    }
                    "toggle_dim" -> {
                        PhoneScreenDimManager.toggleDim(context)
                        onRequestRepaint()
                    }
                    "refresh_panes" -> {
                        isControlMenuExpanded = false
                        reconfigureDisplaysForCurrentSplit(launchSecondaryIfReady = true)
                        onRequestRepaint()
                    }
                    "exit_split" -> {
                        toggleDualMode()
                    }
                }
                return true
            }
        }
        return true
    }

    private fun getAppPickerCardRect(w: Int, h: Int): RectF {
        val d = density()
        val cardW = min(w * 0.90f, 620f * d)
        val cardH = min(h * 0.86f, 320f * d)
        val left = (w - cardW) * 0.5f
        val top = (h - cardH) * 0.5f
        return RectF(left, top, left + cardW, top + cardH)
    }

    private fun drawAppPickerOverlay(canvas: Canvas, w: Int, h: Int) {
        val d = density()
        val card = getAppPickerCardRect(w, h)
        canvas.drawRoundRect(card, 16f * d, 16f * d, overlayCardPaint)
        canvas.drawRoundRect(card, 16f * d, 16f * d, overlayStrokePaint)

        val lang = langProvider()
        val title = if (lang == "vi") "Chọn Ứng dụng chạy ở Cửa sổ thứ 2" else "Select 2nd App for Split-Screen"
        textPaint.textSize = 15f * d
        canvas.drawText(title, card.centerX(), card.top + 26f * d, textPaint)

        // Close [X] button top-right
        val closeRect = RectF(card.right - 44f * d, card.top + 8f * d, card.right - 10f * d, card.top + 34f * d)
        canvas.drawRoundRect(closeRect, 8f * d, 8f * d, pillPaint)
        textPaint.textSize = 13f * d
        canvas.drawText("✕", closeRect.centerX(), closeRect.centerY() + 4.5f * d, textPaint)

        if (appPickerItems.isEmpty()) {
            subTextPaint.textSize = 13.5f * d
            val msg = if (lang == "vi") {
                "Đang tải danh sách ứng dụng từ điện thoại (hãy mở AAMaKer & bật Shizuku/Root)..."
            } else {
                "Loading installed apps from phone (ensure Shizuku/Root is enabled in AAMaKer)..."
            }
            canvas.drawText(msg, card.centerX(), card.centerY(), subTextPaint)
            return
        }

        val cols = 3
        val rows = 3
        val perPage = cols * rows
        val totalPages = max(1, (appPickerItems.size + perPage - 1) / perPage)
        val page = appPickerPage.coerceIn(0, totalPages - 1)
        val startIdx = page * perPage

        val padX = 14f * d
        val topOffset = 42f * d
        val bottomFooter = 44f * d
        val gap = 8f * d
        val cellW = (card.width() - padX * 2f - gap * (cols - 1)) / cols
        val cellH = (card.height() - topOffset - bottomFooter - gap * (rows - 1)) / rows

        textPaint.textSize = 12.5f * d
        for (slot in 0 until perPage) {
            val item = appPickerItems.getOrNull(startIdx + slot) ?: break
            val r = slot / cols
            val c = slot % cols
            val left = card.left + padX + c * (cellW + gap)
            val top = card.top + topOffset + r * (cellH + gap)
            val rect = RectF(left, top, left + cellW, top + cellH)
            val isSelected = item.packageName == secondaryPackageName
            canvas.drawRoundRect(rect, 9f * d, 9f * d, if (isSelected) activePillPaint else pillPaint)
            val truncated = if (item.label.length > 18) item.label.take(17) + "…" else item.label
            canvas.drawText(truncated, rect.centerX(), rect.centerY() + 4.5f * d, textPaint)
        }

        // Pagination footer
        val footerY = card.bottom - 16f * d
        val prevRect = RectF(card.left + padX, card.bottom - 36f * d, card.left + padX + 86f * d, card.bottom - 8f * d)
        val nextRect = RectF(card.right - padX - 86f * d, card.bottom - 36f * d, card.right - padX, card.bottom - 8f * d)

        if (page > 0) {
            canvas.drawRoundRect(prevRect, 8f * d, 8f * d, pillPaint)
            canvas.drawText("◀ Prev", prevRect.centerX(), prevRect.centerY() + 4f * d, textPaint)
        }
        subTextPaint.textSize = 12f * d
        canvas.drawText("${page + 1} / $totalPages", card.centerX(), footerY, subTextPaint)
        if (page < totalPages - 1) {
            canvas.drawRoundRect(nextRect, 8f * d, 8f * d, pillPaint)
            canvas.drawText("Next ▶", nextRect.centerX(), nextRect.centerY() + 4f * d, textPaint)
        }
    }

    private fun handleAppPickerClick(x: Float, y: Float) {
        val d = density()
        val card = getAppPickerCardRect(totalWidth, totalHeight)
        if (!card.contains(x, y)) {
            isAppPickerOpen = false
            onRequestRepaint()
            return
        }

        val closeRect = RectF(card.right - 44f * d, card.top + 8f * d, card.right - 10f * d, card.top + 34f * d)
        if (closeRect.contains(x, y)) {
            isAppPickerOpen = false
            onRequestRepaint()
            return
        }

        val cols = 3
        val rows = 3
        val perPage = cols * rows
        val totalPages = max(1, (appPickerItems.size + perPage - 1) / perPage)
        val page = appPickerPage.coerceIn(0, totalPages - 1)
        val startIdx = page * perPage

        val padX = 14f * d
        val prevRect = RectF(card.left + padX, card.bottom - 36f * d, card.left + padX + 86f * d, card.bottom - 8f * d)
        val nextRect = RectF(card.right - padX - 86f * d, card.bottom - 36f * d, card.right - padX, card.bottom - 8f * d)

        if (page > 0 && prevRect.contains(x, y)) {
            appPickerPage = page - 1
            onRequestRepaint()
            return
        }
        if (page < totalPages - 1 && nextRect.contains(x, y)) {
            appPickerPage = page + 1
            onRequestRepaint()
            return
        }

        val topOffset = 42f * d
        val bottomFooter = 44f * d
        val gap = 8f * d
        val cellW = (card.width() - padX * 2f - gap * (cols - 1)) / cols
        val cellH = (card.height() - topOffset - bottomFooter - gap * (rows - 1)) / rows

        for (slot in 0 until perPage) {
            val item = appPickerItems.getOrNull(startIdx + slot) ?: break
            val r = slot / cols
            val c = slot % cols
            val left = card.left + padX + c * (cellW + gap)
            val top = card.top + topOffset + r * (cellH + gap)
            val rect = RectF(left, top, left + cellW, top + cellH)
            if (rect.contains(x, y)) {
                selectAndLaunchSecondaryApp(item.packageName, item.label)
                return
            }
        }
    }

    private fun teardownSecondaryDisplayAsync() {
        val oldReader = secondaryImageReader
        secondaryImageReader = null
        secondaryDisplayId = Display.INVALID_DISPLAY
        lastSecondaryFrameTimeMs = 0L
        synchronized(secondaryBitmapLock) {
            secondaryFrameBitmap?.recycle()
            secondaryFrameBitmap = null
        }
        getOrCreateWorkerHandler().post {
            runCatching { oldReader?.close() }
            try {
                val req = Bundle().apply {
                    putString("slot", "secondary")
                    attachAuthToBundle(this)
                }
                context.applicationContext.contentResolver.call(
                    bridgeUri,
                    "release_secondary_display",
                    null,
                    req
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun destroy() {
        isAppPickerOpen = false
        isControlMenuExpanded = false
        secScrollActive = false
        teardownSecondaryDisplayAsync()
        inputHandler?.post {
            runCatching { inputThread?.quitSafely() }
        }
        inputHandler = null
        inputThread = null
        workerHandler?.post {
            runCatching { workerThread?.quitSafely() }
        }
        workerHandler = null
        workerThread = null
    }
}
