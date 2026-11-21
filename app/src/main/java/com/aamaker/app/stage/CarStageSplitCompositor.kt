package com.aamaker.app.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class StagePickerApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?
)

/**
 * OpenGL ES 2.0 Dual-Slot Surface Compositor for Car Stage:
 * - Renders directly into Android Auto's SurfaceContainer using EGL14 WindowSurface + SurfaceTexture
 *   (EXTERNAL_OES for zero-copy GPU VirtualDisplay textures, TEXTURE_2D for interactive OLED app pickers,
 *   seam divider, ratio menu, and status overlay).
 * - Avoids `Surface.lockCanvas(null)` which fails with `INVALID_OPERATION` on Android Auto's remote
 *   SurfaceContainer once a GPU producer or EGL consumer has attached, and avoids `lockHardwareCanvas()`
 *   which causes native HwuiContext crashes on teardown.
 * - Supports both Split-Screen (50:50, 60:40, 70:30, custom drag) and 100% Single-Screen modes.
 */
class CarStageSplitCompositor {

    companion object {
        private const val TAG = "CarStageCompositor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER =
            "attribute vec4 aPos;\n" +
                "attribute vec4 aTex;\n" +
                "uniform mat4 uTexM;\n" +
                "varying vec2 vTex;\n" +
                "void main() {\n" +
                "  gl_Position = aPos;\n" +
                "  vTex = (uTexM * aTex).xy;\n" +
                "}\n"

        private const val FRAGMENT_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTex;\n" +
                "uniform samplerExternalOES sTex;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTex, vTex);\n" +
                "}\n"

        private const val FRAGMENT_2D =
            "precision mediump float;\n" +
                "varying vec2 vTex;\n" +
                "uniform sampler2D sTex;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTex, vTex);\n" +
                "}\n"

        private val FLIP_Y = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )

        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglPbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglWindow: EGLSurface = EGL14.EGL_NO_SURFACE

    private var progOes = 0
    private var aPosOes = 0
    private var aTexOes = 0
    private var uTexMOes = 0

    private var prog2D = 0
    private var aPos2D = 0
    private var aTex2D = 0
    private var uTexM2D = 0

    private var primaryOesTex = 0
    private var secondaryOesTex = 0
    private var uiOverlayTex = 0

    private var quadBuffer: FloatBuffer? = null

    private var primarySurfaceTexture: SurfaceTexture? = null
    private var secondarySurfaceTexture: SurfaceTexture? = null
    private var primaryInputSurface: Surface? = null
    private var secondaryInputSurface: Surface? = null

    private val primaryTexMatrix = FloatArray(16)
    private val secondaryTexMatrix = FloatArray(16)
    @Volatile
    private var hasPrimaryFrame = false
    @Volatile
    private var hasSecondaryFrame = false

    private var primaryBufW = 640
    private var primaryBufH = 720
    private var secondaryBufW = 640
    private var secondaryBufH = 720

    private var surfaceW = 1280
    private var surfaceH = 720

    private var uiBitmap: Bitmap? = null
    private var uiCanvas: Canvas? = null

    private var outputSurface: Surface? = null
    private var bounds: StageSlotBounds? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var primaryRatio: Float = 0.5f
    private var isSplitMode: Boolean = true
    private var onViewportSizeChanged: ((primaryViewport: Rect, secondaryViewport: Rect?) -> Unit)? = null
    private var lastReportedPrimaryW = 0
    private var lastReportedPrimaryH = 0
    private var lastReportedSecondaryW = 0
    private var lastReportedSecondaryH = 0
    private val primaryDrawRect = Rect()
    private val secondaryDrawRect = Rect()

    @Volatile
    var showLeftPicker: Boolean = false
        private set

    @Volatile
    var showRightPicker: Boolean = false
        private set

    @Volatile
    var showRatioMenu: Boolean = false
        private set

    @Volatile
    var isDraggingSeam: Boolean = false
        private set

    @Volatile
    var controlsExpanded: Boolean = false
        private set

    @Volatile
    private var app1ActionLabel: String = "A1"

    @Volatile
    private var app2ActionLabel: String = "A2"

    private var topLeftToggleRect = RectF()
    private val topLeftActionHitBoxes = mutableListOf<Pair<RectF, String>>()

    private val topLeftFabIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 15, 23, 42)
        style = Paint.Style.FILL
    }
    private val topLeftBarActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(228, 15, 23, 42)
        style = Paint.Style.FILL
    }
    private val topLeftStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val topLeftChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val topLeftTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val topLeftLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    @Volatile
    private var statusMessageTitle: String = ""

    @Volatile
    private var statusMessageSubtitle: String = ""

    val currentPrimaryRatio: Float get() = primaryRatio

    @Volatile
    private var leftPickerApps: List<StagePickerApp> = emptyList()

    @Volatile
    private var leftPickerHeaderTitle: String = ""

    @Volatile
    private var pickerApps: List<StagePickerApp> = emptyList()

    @Volatile
    private var pickerHeaderTitle: String = ""

    private val leftTileHitBoxes = mutableListOf<Pair<RectF, String>>()
    private val tileHitBoxes = mutableListOf<Pair<RectF, String>>()
    private val ratioMenuHitBoxes = mutableListOf<Pair<RectF, StageLayoutPreset>>()
    private var seamHandleRect = RectF()
    private var primaryCardRect = RectF()
    private var secondaryCardRect = RectF()

    @Volatile
    private var leftPickerScrollY: Float = 0f
    private var leftPickerMaxScrollY: Float = 0f

    @Volatile
    private var rightPickerScrollY: Float = 0f
    private var rightPickerMaxScrollY: Float = 0f

    private val seamPaint = Paint().apply {
        color = Color.rgb(18, 20, 26)
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 220, 225, 235)
        style = Paint.Style.FILL
    }
    private val handleActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 165, 250)
        style = Paint.Style.FILL
    }
    private val pickerBgPaint = Paint().apply {
        color = Color.rgb(12, 15, 22)
        style = Paint.Style.FILL
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 28, 34, 46)
        style = Paint.Style.FILL
    }
    private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val headerPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(228, 234, 245)
        textSize = 22f
        isFakeBoldText = true
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 240, 248)
        textSize = 19f
        textAlign = Paint.Align.CENTER
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 210, 220, 235)
        textSize = 16f
        textAlign = Paint.Align.CENTER
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    val primarySurface: Surface? get() = primaryInputSurface
    val secondarySurface: Surface? get() = secondaryInputSurface
    val currentBounds: StageSlotBounds? get() = bounds
    val currentPrimaryBufferSize: Pair<Int, Int> get() = primaryBufW to primaryBufH
    val currentSecondaryBufferSize: Pair<Int, Int> get() = secondaryBufW to secondaryBufH

    @Synchronized
    fun setOnViewportSizeChangedListener(listener: ((primaryViewport: Rect, secondaryViewport: Rect?) -> Unit)?) {
        onViewportSizeChanged = listener
    }

    /**
     * Updates split mode / ratio and SurfaceTexture default buffer sizes in-place without tearing down
     * the EGL context or detaching input Surfaces from live VirtualDisplays!
     */
    @Synchronized
    fun reconfigureLayoutInPlace(
        split: Boolean,
        ratio: Float
    ): Pair<Rect, Rect?>? {
        val h = handler ?: return null
        val surface = outputSurface?.takeIf { it.isValid } ?: return null
        isSplitMode = split
        primaryRatio = if (split) ratio.coerceIn(0.30f, 0.75f) else 1.0f

        val contentArea = CarStageLayoutEngine.resolveContentArea(surfaceW, surfaceH, visibleArea, stableArea)
        val nextSlots = CarStageLayoutEngine.computeSlotsInArea(contentArea, isSplitMode, primaryRatio)
        val pVp = CarStageLayoutEngine.computePrimaryViewport(nextSlots)
        val sVp = CarStageLayoutEngine.computeSecondaryViewport(nextSlots)

        bounds = nextSlots
        primaryCardRect = RectF(pVp)
        if (sVp != null) {
            secondaryCardRect = RectF(sVp)
        } else {
            secondaryCardRect.setEmpty()
        }

        val nextPW = pVp.width().coerceAtLeast(160)
        val nextPH = pVp.height().coerceAtLeast(160)
        val nextSW = (sVp?.width() ?: nextPW).coerceAtLeast(160)
        val nextSH = (sVp?.height() ?: nextPH).coerceAtLeast(160)

        primaryBufW = nextPW
        primaryBufH = nextPH
        secondaryBufW = nextSW
        secondaryBufH = nextSH
        lastReportedPrimaryW = nextPW
        lastReportedPrimaryH = nextPH
        lastReportedSecondaryW = if (sVp != null) nextSW else 0
        lastReportedSecondaryH = if (sVp != null) nextSH else 0

        h.post {
            if (surface.isValid) {
                runCatching { primarySurfaceTexture?.setDefaultBufferSize(nextPW, nextPH) }
                runCatching { secondarySurfaceTexture?.setDefaultBufferSize(nextSW, nextSH) }
                compositeNowOnGlThread()
            }
        }
        return pVp to sVp
    }

    /**
     * Attaches the Android Auto SurfaceContainer and prepares GL textures & input Surfaces immediately.
     * Safe to call before Shizuku connects so the user sees the App Picker UI right away instead of a black screen.
     * If already running on the exact same carSurface, reconfigures in-place cleanly without dropping frames.
     */
    @Synchronized
    fun start(
        carSurface: Surface,
        slotBounds: StageSlotBounds,
        ratio: Float = 0.5f,
        fullWidth: Int = 1280,
        fullHeight: Int = 720,
        preservePickers: Boolean = true
    ) {
        val wantSplit = slotBounds.secondary != null
        val cleanRatio = if (wantSplit) ratio.coerceIn(0.30f, 0.75f) else 1.0f

        if (preservePickers &&
            outputSurface === carSurface &&
            carSurface.isValid &&
            handler != null &&
            primaryInputSurface?.isValid == true
        ) {
            surfaceW = fullWidth.coerceAtLeast(320)
            surfaceH = fullHeight.coerceAtLeast(240)
            reconfigureLayoutInPlace(split = wantSplit, ratio = cleanRatio)
            return
        }

        val savedLeftShow = showLeftPicker
        val savedLeftTitle = leftPickerHeaderTitle
        val savedLeftApps = leftPickerApps
        val savedRightShow = showRightPicker
        val savedRightTitle = pickerHeaderTitle
        val savedRightApps = pickerApps
        val savedStatusTitle = statusMessageTitle
        val savedStatusSub = statusMessageSubtitle
        val savedControlsExpanded = controlsExpanded
        val savedApp1Label = app1ActionLabel
        val savedApp2Label = app2ActionLabel
        val savedVisibleArea = visibleArea
        val savedStableArea = stableArea

        stopInternal(preservePickers = preservePickers)

        outputSurface = carSurface
        isSplitMode = wantSplit
        primaryRatio = cleanRatio
        if (preservePickers) {
            visibleArea = savedVisibleArea
            stableArea = savedStableArea
        }

        val secRect = slotBounds.secondary
        surfaceW = fullWidth.coerceAtLeast(
            secRect?.right ?: slotBounds.primary.right.coerceAtLeast(480)
        )
        surfaceH = fullHeight.coerceAtLeast(slotBounds.primary.bottom.coerceAtLeast(320))

        val contentArea = CarStageLayoutEngine.resolveContentArea(surfaceW, surfaceH, visibleArea, stableArea)
        val effectiveSlots = CarStageLayoutEngine.computeSlotsInArea(contentArea, isSplitMode, primaryRatio)
        bounds = effectiveSlots

        val pVp = CarStageLayoutEngine.computePrimaryViewport(effectiveSlots)
        val sVp = CarStageLayoutEngine.computeSecondaryViewport(effectiveSlots)
        primaryCardRect = RectF(pVp)
        if (sVp != null) secondaryCardRect = RectF(sVp) else secondaryCardRect.setEmpty()

        primaryBufW = pVp.width().coerceAtLeast(160)
        primaryBufH = pVp.height().coerceAtLeast(160)
        secondaryBufW = (sVp?.width() ?: primaryBufW).coerceAtLeast(160)
        secondaryBufH = (sVp?.height() ?: primaryBufH).coerceAtLeast(160)
        lastReportedPrimaryW = primaryBufW
        lastReportedPrimaryH = primaryBufH
        lastReportedSecondaryW = if (sVp != null) secondaryBufW else 0
        lastReportedSecondaryH = if (sVp != null) secondaryBufH else 0

        if (preservePickers) {
            showLeftPicker = savedLeftShow
            leftPickerHeaderTitle = savedLeftTitle
            leftPickerApps = savedLeftApps
            showRightPicker = savedRightShow
            pickerHeaderTitle = savedRightTitle
            pickerApps = savedRightApps
            statusMessageTitle = savedStatusTitle
            statusMessageSubtitle = savedStatusSub
            controlsExpanded = savedControlsExpanded
            app1ActionLabel = savedApp1Label
            app2ActionLabel = savedApp2Label
        }

        val ht = HandlerThread("CarStageGLCompositor").also { it.start() }
        val h = Handler(ht.looper)
        thread = ht
        handler = h

        runSync(h) {
            initGlOnThread(carSurface)
            compositeNowOnGlThread()
        }
    }

    @Synchronized
    fun setControlsExpanded(expanded: Boolean, app1Label: String = app1ActionLabel, app2Label: String = app2ActionLabel) {
        controlsExpanded = expanded
        if (app1Label.isNotBlank()) app1ActionLabel = app1Label
        if (app2Label.isNotBlank()) app2ActionLabel = app2Label
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun hitTestTopLeftControl(x: Float, y: Float): String? {
        if (!controlsExpanded) {
            val hitRect = RectF(
                topLeftToggleRect.left - 10f,
                topLeftToggleRect.top - 10f,
                topLeftToggleRect.right + 10f,
                topLeftToggleRect.bottom + 10f
            )
            return if (hitRect.contains(x, y)) "expand" else null
        }
        return topLeftActionHitBoxes.firstOrNull { (rect, _) -> rect.contains(x, y) }?.second
    }

    @Synchronized
    fun setStatusBanner(title: String = "", subtitle: String = "") {
        statusMessageTitle = title
        statusMessageSubtitle = subtitle
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun updateVisibleArea(area: Rect?) {
        val next = area?.takeIf { !it.isEmpty }?.let { Rect(it) }
        if (next == visibleArea) return
        visibleArea = next
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun updateStableArea(area: Rect?) {
        val next = area?.takeIf { !it.isEmpty }?.let { Rect(it) }
        if (next == stableArea) return
        stableArea = next
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun setRatioMenu(show: Boolean) {
        showRatioMenu = show
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun setDraggingSeam(dragging: Boolean) {
        isDraggingSeam = dragging
        if (dragging) showRatioMenu = false
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun adjustRatioByDelta(deltaX: Float): Float {
        val availW = (visibleArea?.width() ?: bounds?.let {
            (it.secondary?.right ?: it.primary.right) - it.primary.left
        } ?: surfaceW).coerceAtLeast(320).toFloat()
        primaryRatio = (primaryRatio + deltaX / availW).coerceIn(0.30f, 0.75f)
        isDraggingSeam = true
        showRatioMenu = false
        handler?.post { compositeNowOnGlThread() }
        return primaryRatio
    }

    @Synchronized
    fun hitTestRatioMenu(x: Float, y: Float): StageLayoutPreset? {
        if (!showRatioMenu) return null
        return ratioMenuHitBoxes.firstOrNull { (rect, _) -> rect.contains(x, y) }?.second
    }

    @Synchronized
    fun setLeftPicker(show: Boolean, title: String = "", apps: List<StagePickerApp> = leftPickerApps) {
        showLeftPicker = show
        if (title.isNotBlank()) leftPickerHeaderTitle = title
        leftPickerApps = apps
        if (show) leftPickerScrollY = 0f
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun scrollLeftPicker(distanceY: Float) {
        if (!showLeftPicker) return
        leftPickerScrollY = (leftPickerScrollY + distanceY).coerceIn(0f, leftPickerMaxScrollY)
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun hitTestLeftPickerApp(x: Float, y: Float): String? {
        if (!showLeftPicker) return null
        return leftTileHitBoxes.firstOrNull { (rect, _) -> rect.contains(x, y) }?.second
    }

    @Synchronized
    fun setRightPicker(show: Boolean, title: String = "", apps: List<StagePickerApp> = pickerApps) {
        showRightPicker = show
        if (title.isNotBlank()) pickerHeaderTitle = title
        pickerApps = apps
        if (show) rightPickerScrollY = 0f
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun scrollRightPicker(distanceY: Float) {
        if (!showRightPicker) return
        rightPickerScrollY = (rightPickerScrollY + distanceY).coerceIn(0f, rightPickerMaxScrollY)
        handler?.post { compositeNowOnGlThread() }
    }

    @Synchronized
    fun hitTestSeamHandle(x: Float, y: Float): Boolean {
        if (!isSplitMode) return false
        val expanded = RectF(
            seamHandleRect.left - 28f,
            seamHandleRect.top - 28f,
            seamHandleRect.right + 28f,
            seamHandleRect.bottom + 28f
        )
        return expanded.contains(x, y)
    }

    @Synchronized
    fun hitTestRightPickerApp(x: Float, y: Float): String? {
        if (!showRightPicker) return null
        return tileHitBoxes.firstOrNull { (rect, _) -> rect.contains(x, y) }?.second
    }

    @Synchronized
    fun mapTouchToPrimary(x: Float, y: Float): Pair<Float, Float> {
        val drawRect = if (!primaryDrawRect.isEmpty) RectF(primaryDrawRect) else primaryCardRect
        if (drawRect.width() <= 1f || drawRect.height() <= 1f || primaryBufW <= 0 || primaryBufH <= 0) {
            val b = bounds?.primary
            return Pair(if (b != null) x - b.left else x, if (b != null) y - b.top else y)
        }
        val nx = ((x - drawRect.left) / drawRect.width()).coerceIn(0f, 1f) * primaryBufW
        val ny = ((y - drawRect.top) / drawRect.height()).coerceIn(0f, 1f) * primaryBufH
        return Pair(nx, ny)
    }

    @Synchronized
    fun mapTouchToSecondary(x: Float, y: Float): Pair<Float, Float> {
        val drawRect = if (!secondaryDrawRect.isEmpty) RectF(secondaryDrawRect) else secondaryCardRect
        if (drawRect.width() <= 1f || drawRect.height() <= 1f || secondaryBufW <= 0 || secondaryBufH <= 0) {
            val b = bounds?.secondary
            return Pair(if (b != null) x - b.left else x, if (b != null) y - b.top else y)
        }
        val nx = ((x - drawRect.left) / drawRect.width()).coerceIn(0f, 1f) * secondaryBufW
        val ny = ((y - drawRect.top) / drawRect.height()).coerceIn(0f, 1f) * secondaryBufH
        return Pair(nx, ny)
    }

    /**
     * Computes an aspect-ratio-locked sub-rect (letterbox / pillarbox) inside `targetCard` matching
     * `bufW : bufH`, guaranteeing that even during a live seam drag or transient resize frame,
     * the OpenGL viewport NEVER stretches or squishes any app UI horizontally or vertically!
     */
    private fun fitAspectPreservingRect(targetCard: Rect, bufW: Int, bufH: Int, outRect: Rect) {
        val cw = targetCard.width()
        val ch = targetCard.height()
        if (cw <= 0 || ch <= 0 || bufW <= 0 || bufH <= 0) {
            outRect.set(targetCard)
            return
        }
        // Within 1.5% aspect difference (1-2px rounding), fill the exact card seamlessly
        val cardAspect = cw.toFloat() / ch.toFloat()
        val bufAspect = bufW.toFloat() / bufH.toFloat()
        if (kotlin.math.abs(cardAspect - bufAspect) <= 0.015f) {
            outRect.set(targetCard)
            return
        }
        if (bufAspect > cardAspect) {
            // Buffer is wider than card -> fit full width, letterbox top/bottom
            val fittedH = (cw / bufAspect).toInt().coerceIn(1, ch)
            val top = targetCard.top + (ch - fittedH) / 2
            outRect.set(targetCard.left, top, targetCard.right, top + fittedH)
        } else {
            // Buffer is taller/narrower than card -> fit full height, pillarbox left/right
            val fittedW = (ch * bufAspect).toInt().coerceIn(1, cw)
            val left = targetCard.left + (cw - fittedW) / 2
            outRect.set(left, targetCard.top, left + fittedW, targetCard.bottom)
        }
    }

    @Synchronized
    fun stop() {
        stopInternal(preservePickers = false)
    }

    @Synchronized
    private fun stopInternal(preservePickers: Boolean) {
        val h = handler
        val ht = thread
        if (h != null && ht != null) {
            runSync(h) {
                releaseGlOnThread()
            }
            runCatching { ht.quitSafely() }
        }
        thread = null
        handler = null
        outputSurface = null
        bounds = null
        hasPrimaryFrame = false
        hasSecondaryFrame = false
        primaryDrawRect.setEmpty()
        secondaryDrawRect.setEmpty()
        if (!preservePickers) {
            visibleArea = null
            stableArea = null
            showLeftPicker = false
            showRightPicker = false
            showRatioMenu = false
            isDraggingSeam = false
            controlsExpanded = false
            statusMessageTitle = ""
            statusMessageSubtitle = ""
            leftTileHitBoxes.clear()
            tileHitBoxes.clear()
            ratioMenuHitBoxes.clear()
            topLeftActionHitBoxes.clear()
        }
    }

    private fun initGlOnThread(carSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        eglConfig = chooseEglConfig(true) ?: chooseEglConfig(false)
        val cfg = eglConfig ?: return

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            cfg,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        eglPbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            cfg,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        makeCurrent(eglPbuffer)

        try {
            eglWindow = if (carSurface.isValid) {
                EGL14.eglCreateWindowSurface(eglDisplay, cfg, carSurface, intArrayOf(EGL14.EGL_NONE), 0)
            } else {
                EGL14.EGL_NO_SURFACE
            }
        } catch (t: Throwable) {
            Log.w(TAG, "eglCreateWindowSurface error: ${t.message}")
            eglWindow = EGL14.EGL_NO_SURFACE
        }

        if (eglWindow == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "Failed to create EGL window surface (err=0x${Integer.toHexString(EGL14.eglGetError())})")
        }

        progOes = linkProgram(
            compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER),
            compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_OES)
        )
        aPosOes = GLES20.glGetAttribLocation(progOes, "aPos")
        aTexOes = GLES20.glGetAttribLocation(progOes, "aTex")
        uTexMOes = GLES20.glGetUniformLocation(progOes, "uTexM")

        prog2D = linkProgram(
            compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER),
            compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_2D)
        )
        aPos2D = GLES20.glGetAttribLocation(prog2D, "aPos")
        aTex2D = GLES20.glGetAttribLocation(prog2D, "aTex")
        uTexM2D = GLES20.glGetUniformLocation(prog2D, "uTexM")

        quadBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_COORDS)
                position(0)
            }

        primaryOesTex = createOesTexture()
        secondaryOesTex = createOesTexture()
        uiOverlayTex = create2DTexture()

        val h = handler
        primarySurfaceTexture = SurfaceTexture(primaryOesTex).apply {
            setDefaultBufferSize(primaryBufW, primaryBufH)
            setOnFrameAvailableListener({ st ->
                makeCurrent(if (eglWindow != EGL14.EGL_NO_SURFACE) eglWindow else eglPbuffer)
                runCatching {
                    st.updateTexImage()
                    st.getTransformMatrix(primaryTexMatrix)
                    hasPrimaryFrame = true
                }
                compositeNowOnGlThread()
            }, h)
        }
        primaryInputSurface = Surface(primarySurfaceTexture)

        secondarySurfaceTexture = SurfaceTexture(secondaryOesTex).apply {
            setDefaultBufferSize(secondaryBufW, secondaryBufH)
            setOnFrameAvailableListener({ st ->
                makeCurrent(if (eglWindow != EGL14.EGL_NO_SURFACE) eglWindow else eglPbuffer)
                runCatching {
                    st.updateTexImage()
                    st.getTransformMatrix(secondaryTexMatrix)
                    hasSecondaryFrame = true
                }
                if (!showRightPicker) {
                    compositeNowOnGlThread()
                }
            }, h)
        }
        secondaryInputSurface = Surface(secondarySurfaceTexture)
    }

    private fun releaseGlOnThread() {
        runCatching { primaryInputSurface?.release() }
        runCatching { secondaryInputSurface?.release() }
        runCatching { primarySurfaceTexture?.release() }
        runCatching { secondarySurfaceTexture?.release() }
        primaryInputSurface = null
        secondaryInputSurface = null
        primarySurfaceTexture = null
        secondarySurfaceTexture = null

        uiBitmap = null
        uiCanvas = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglWindow != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglWindow) }
                eglWindow = EGL14.EGL_NO_SURFACE
            }
            if (eglPbuffer != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglPbuffer) }
                eglPbuffer = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            runCatching { EGL14.eglTerminate(eglDisplay) }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun compositeNowOnGlThread() {
        val surface = outputSurface ?: return
        if (!surface.isValid) return

        // Recreate EGLWindowSurface if needed
        if (eglWindow == EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY && eglConfig != null) {
            runCatching {
                eglWindow = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            }
        }
        if (eglWindow == EGL14.EGL_NO_SURFACE) return

        makeCurrent(eglWindow)

        val cw = surfaceW.coerceAtLeast(320)
        val ch = surfaceH.coerceAtLeast(240)
        val area = CarStageLayoutEngine.resolveContentArea(cw, ch, visibleArea, stableArea)

        val effectiveSlots = CarStageLayoutEngine.computeSlotsInArea(area, isSplitMode, primaryRatio)
        val dstPrimary = effectiveSlots.primary
        val dstSecondary = effectiveSlots.secondary
        bounds = effectiveSlots

        val pCard = CarStageLayoutEngine.computePrimaryViewport(effectiveSlots)
        primaryCardRect = RectF(pCard)

        val sCard = CarStageLayoutEngine.computeSecondaryViewport(effectiveSlots)?.also {
            secondaryCardRect = RectF(it)
        }
        if (sCard == null) {
            secondaryCardRect.setEmpty()
        }

        // If the content area changed (e.g., switching between Coolwalk dashboard slice and full-screen),
        // update SurfaceTexture buffer sizes and notify CarStageBridge so VirtualDisplays resize to exact 1:1 pixels!
        if (!isDraggingSeam) {
            val wantPW = pCard.width().coerceAtLeast(160)
            val wantPH = pCard.height().coerceAtLeast(160)
            val wantSW = sCard?.width()?.coerceAtLeast(160) ?: 0
            val wantSH = sCard?.height()?.coerceAtLeast(160) ?: 0
            if (wantPW != lastReportedPrimaryW || wantPH != lastReportedPrimaryH ||
                wantSW != lastReportedSecondaryW || wantSH != lastReportedSecondaryH
            ) {
                lastReportedPrimaryW = wantPW
                lastReportedPrimaryH = wantPH
                lastReportedSecondaryW = wantSW
                lastReportedSecondaryH = wantSH
                primaryBufW = wantPW
                primaryBufH = wantPH
                runCatching { primarySurfaceTexture?.setDefaultBufferSize(wantPW, wantPH) }
                if (wantSW > 0 && wantSH > 0) {
                    secondaryBufW = wantSW
                    secondaryBufH = wantSH
                    runCatching { secondarySurfaceTexture?.setDefaultBufferSize(wantSW, wantSH) }
                }
                val listener = onViewportSizeChanged
                if (listener != null) {
                    val pCopy = Rect(pCard)
                    val sCopy = sCard?.let { Rect(it) }
                    listener.invoke(pCopy, sCopy)
                }
            }
        }

        // 1. Clear full car screen to pure OLED black
        GLES20.glViewport(0, 0, cw, ch)
        GLES20.glClearColor(0.03f, 0.04f, 0.06f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 2. Draw Primary VirtualDisplay OES texture with strict aspect-ratio preservation (zero distortion!)
        if (!showLeftPicker && hasPrimaryFrame && pCard.width() > 0 && pCard.height() > 0) {
            fitAspectPreservingRect(pCard, primaryBufW, primaryBufH, primaryDrawRect)
            GLES20.glViewport(
                primaryDrawRect.left,
                ch - primaryDrawRect.bottom,
                primaryDrawRect.width(),
                primaryDrawRect.height()
            )
            drawQuad(progOes, aPosOes, aTexOes, uTexMOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, primaryOesTex, primaryTexMatrix)
        } else {
            primaryDrawRect.set(pCard)
        }

        // 3. Draw Secondary VirtualDisplay OES texture with strict aspect-ratio preservation (zero distortion!)
        if (dstSecondary != null && !showRightPicker && hasSecondaryFrame && sCard != null && sCard.width() > 0 && sCard.height() > 0) {
            fitAspectPreservingRect(sCard, secondaryBufW, secondaryBufH, secondaryDrawRect)
            GLES20.glViewport(
                secondaryDrawRect.left,
                ch - secondaryDrawRect.bottom,
                secondaryDrawRect.width(),
                secondaryDrawRect.height()
            )
            drawQuad(progOes, aPosOes, aTexOes, uTexMOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, secondaryOesTex, secondaryTexMatrix)
        } else if (sCard != null) {
            secondaryDrawRect.set(sCard)
        } else {
            secondaryDrawRect.setEmpty()
        }

        // 4. Render 2D UI layer (pickers, waiting placeholders, center seam, ratio badge, dropdown menu)
        val bmp = ensureUiBitmap(cw, ch)
        val canvas = uiCanvas
        if (bmp != null && canvas != null) {
            bmp.eraseColor(Color.TRANSPARENT)
            var hasUiElements = false

            // Left Picker or Waiting State
            if (showLeftPicker) {
                drawAppPickerInSlot(
                    canvas = canvas,
                    slotRect = dstPrimary,
                    title = leftPickerHeaderTitle,
                    apps = leftPickerApps,
                    hitBoxes = leftTileHitBoxes,
                    scrollY = leftPickerScrollY,
                    onMaxScroll = { leftPickerMaxScrollY = it },
                    avoidTopLeftToggle = true
                )
                hasUiElements = true
            } else if (!hasPrimaryFrame) {
                drawWaitingPlaceholder(canvas, dstPrimary)
                hasUiElements = true
            }

            // Center Seam + Pill Handle + Right Picker / Waiting State
            if (isSplitMode && dstSecondary != null) {
                val seamLeft = dstPrimary.right.toFloat()
                val seamRight = dstSecondary.left.toFloat()
                canvas.drawRect(seamLeft, area.top.toFloat(), seamRight, area.bottom.toFloat(), seamPaint)

                val cx = (seamLeft + seamRight) * 0.5f
                val cy = (area.top + area.bottom) * 0.5f
                seamHandleRect = RectF(cx - 4f, cy - 36f, cx + 4f, cy + 36f)
                canvas.drawRoundRect(seamHandleRect, 4f, 4f, if (isDraggingSeam) handleActivePaint else handlePaint)
                hasUiElements = true

                if (showRightPicker) {
                    drawRightAppPicker(canvas, dstSecondary)
                } else if (!hasSecondaryFrame) {
                    drawWaitingPlaceholder(canvas, dstSecondary)
                }

                if (isDraggingSeam) {
                    val leftPct = (primaryRatio * 100).toInt()
                    val rightPct = 100 - leftPct
                    val badgeRect = RectF(cx - 56f, cy - 22f, cx + 56f, cy + 22f)
                    canvas.drawRoundRect(badgeRect, 14f, 14f, panelPaint)
                    canvas.drawRoundRect(badgeRect, 14f, 14f, panelStrokePaint)
                    canvas.drawText("$leftPct : $rightPct", badgeRect.centerX(), badgeRect.centerY() + 7f, labelPaint)
                }

                if (showRatioMenu) {
                    drawRatioDropdownMenu(canvas, cx, area.top + 28f)
                } else {
                    ratioMenuHitBoxes.clear()
                }
            } else {
                ratioMenuHitBoxes.clear()
            }

            // Always draw minimalist Top-Left control pill/bar fixed at the top-left corner
            drawTopLeftControls(canvas, area)
            hasUiElements = true

            if (hasUiElements) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uiOverlayTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GLES20.glViewport(0, 0, cw, ch)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                drawQuad(prog2D, aPos2D, aTex2D, uTexM2D, GLES20.GL_TEXTURE_2D, uiOverlayTex, FLIP_Y)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
        }

        EGL14.eglSwapBuffers(eglDisplay, eglWindow)
    }

    private fun ensureUiBitmap(w: Int, h: Int): Bitmap? {
        val cur = uiBitmap
        if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) {
            return cur
        }
        return runCatching {
            val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            uiBitmap = created
            uiCanvas = Canvas(created)
            created
        }.getOrNull()
    }

    private fun drawTopLeftControls(canvas: Canvas, area: Rect) {
        topLeftActionHitBoxes.clear()
        val margin = 12f
        val startX = area.left + margin
        val startY = area.top + margin

        if (!controlsExpanded) {
            val btnSize = 40f
            topLeftToggleRect = RectF(startX, startY, startX + btnSize, startY + btnSize)
            canvas.drawRoundRect(topLeftToggleRect, 20f, 20f, topLeftFabIdlePaint)
            canvas.drawRoundRect(topLeftToggleRect, 20f, 20f, topLeftStrokePaint)

            val cx = topLeftToggleRect.centerX()
            val cy = topLeftToggleRect.centerY()
            val halfLine = 8f
            canvas.drawLine(cx - halfLine, cy - 6f, cx + halfLine, cy - 6f, topLeftLinePaint)
            canvas.drawLine(cx - halfLine, cy, cx + halfLine, cy, topLeftLinePaint)
            canvas.drawLine(cx - halfLine, cy + 6f, cx + halfLine, cy + 6f, topLeftLinePaint)
            return
        }

        val items = listOf(
            "collapse" to "✕",
            "app1" to "A1",
            "app2" to "A2",
            "restart" to "↻",
            "back" to "◀"
        )
        val pad = 6f
        val gap = 6f
        val chipW = 46f
        val chipH = 36f
        val totalW = pad * 2f + items.size * chipW + (items.size - 1) * gap
        val totalH = pad * 2f + chipH
        val barRect = RectF(startX, startY, startX + totalW, startY + totalH)

        canvas.drawRoundRect(barRect, 20f, 20f, topLeftBarActivePaint)
        canvas.drawRoundRect(barRect, 20f, 20f, topLeftStrokePaint)

        items.forEachIndexed { idx, (actionId, symbol) ->
            val left = barRect.left + pad + idx * (chipW + gap)
            val top = barRect.top + pad
            val rect = RectF(left, top, left + chipW, top + chipH)
            topLeftActionHitBoxes.add(rect to actionId)
            canvas.drawRoundRect(rect, 13f, 13f, topLeftChipPaint)
            canvas.drawText(symbol, rect.centerX(), rect.centerY() + 6f, topLeftTextPaint)
        }
    }

    private fun drawWaitingPlaceholder(canvas: Canvas, slotRect: Rect) {
        val outerMargin = 12f
        val panelRect = RectF(
            slotRect.left + outerMargin,
            slotRect.top + outerMargin,
            slotRect.right - outerMargin,
            slotRect.bottom - outerMargin
        )
        canvas.drawRoundRect(panelRect, 24f, 24f, panelPaint)
        canvas.drawRoundRect(panelRect, 24f, 24f, panelStrokePaint)

        val title = statusMessageTitle.ifBlank { "Car Stage" }
        val sub = statusMessageSubtitle
        val cx = panelRect.centerX()
        val cy = panelRect.centerY()
        canvas.drawText(title, cx, cy - if (sub.isNotBlank()) 10f else -6f, labelPaint)
        if (sub.isNotBlank()) {
            val ellipsized = TextUtils.ellipsize(sub, subtitlePaint, panelRect.width() - 32f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipsized, cx, cy + 20f, subtitlePaint)
        }
    }

    private fun drawRatioDropdownMenu(canvas: Canvas, centerX: Float, topY: Float) {
        ratioMenuHitBoxes.clear()
        val options = listOf(
            StageLayoutPreset.SINGLE to "100%",
            StageLayoutPreset.SPLIT_50_50 to "50 : 50",
            StageLayoutPreset.SPLIT_60_40 to "60 : 40",
            StageLayoutPreset.SPLIT_70_30 to "70 : 30"
        )
        val menuW = 148f
        val rowH = 52f
        val pad = 10f
        val menuH = pad * 2 + options.size * rowH
        val left = (centerX - menuW / 2f).coerceAtLeast(16f)
        val menuRect = RectF(left, topY, left + menuW, topY + menuH)

        canvas.drawRoundRect(menuRect, 20f, 20f, panelPaint)
        canvas.drawRoundRect(menuRect, 20f, 20f, panelStrokePaint)

        options.forEachIndexed { idx, (preset, label) ->
            val rTop = menuRect.top + pad + idx * rowH
            val rowRect = RectF(menuRect.left + pad, rTop, menuRect.right - pad, rTop + rowH - 6f)
            ratioMenuHitBoxes.add(rowRect to preset)
            val isSelected = kotlin.math.abs(preset.primaryRatio - primaryRatio) < 0.02f
            if (isSelected) {
                canvas.drawRoundRect(rowRect, 12f, 12f, headerPillPaint)
            }
            canvas.drawText(label, rowRect.centerX(), rowRect.centerY() + 7f, labelPaint)
        }
    }

    private fun drawRightAppPicker(canvas: Canvas, secRect: Rect) {
        drawAppPickerInSlot(
            canvas = canvas,
            slotRect = secRect,
            title = pickerHeaderTitle,
            apps = pickerApps,
            hitBoxes = tileHitBoxes,
            scrollY = rightPickerScrollY,
            onMaxScroll = { rightPickerMaxScrollY = it },
            avoidTopLeftToggle = false
        )
    }

    private fun drawAppPickerInSlot(
        canvas: Canvas,
        slotRect: Rect,
        title: String,
        apps: List<StagePickerApp>,
        hitBoxes: MutableList<Pair<RectF, String>>,
        scrollY: Float,
        onMaxScroll: (Float) -> Unit,
        avoidTopLeftToggle: Boolean = false
    ) {
        canvas.drawRect(slotRect, pickerBgPaint)
        hitBoxes.clear()

        val outerMargin = 12f
        val panelRect = RectF(
            slotRect.left + outerMargin,
            slotRect.top + outerMargin,
            slotRect.right - outerMargin,
            slotRect.bottom - outerMargin
        )
        canvas.drawRoundRect(panelRect, 26f, 26f, panelPaint)
        canvas.drawRoundRect(panelRect, 26f, 26f, panelStrokePaint)

        val innerPad = 18f
        val headerLeftOffset = if (avoidTopLeftToggle) 50f else innerPad
        val headerH = 44f
        val headerRect = RectF(
            panelRect.left + headerLeftOffset,
            panelRect.top + innerPad,
            panelRect.right - innerPad,
            panelRect.top + innerPad + headerH
        )
        canvas.drawRoundRect(headerRect, 14f, 14f, headerPillPaint)
        val displayTitle = title.ifBlank { "Car Stage" }
        val titleEllipsized = TextUtils.ellipsize(
            displayTitle,
            headerPaint,
            (headerRect.width() - 28f).coerceAtLeast(60f),
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(titleEllipsized, headerRect.left + 16f, headerRect.centerY() + 8f, headerPaint)

        if (apps.isEmpty()) {
            val msg = statusMessageSubtitle.ifBlank { statusMessageTitle }
            if (msg.isNotBlank()) {
                val ellipsized = TextUtils.ellipsize(msg, subtitlePaint, panelRect.width() - 32f, TextUtils.TruncateAt.END).toString()
                canvas.drawText(ellipsized, panelRect.centerX(), panelRect.centerY(), subtitlePaint)
            }
            return
        }

        val gridRect = RectF(
            panelRect.left + innerPad,
            headerRect.bottom + 16f,
            panelRect.right - innerPad,
            panelRect.bottom - innerPad
        )
        val availW = gridRect.width().coerceAtLeast(120f)
        val cols = when {
            availW >= 540f -> 4
            availW >= 340f -> 3
            else -> 2
        }
        val colGap = 12f
        val rowGap = 18f
        val cellW = (availW - colGap * (cols - 1)) / cols
        val iconSize = (cellW * 0.56f).coerceIn(56f, 84f)
        val cellH = iconSize + 34f

        val totalRows = (apps.size + cols - 1) / cols
        val contentH = totalRows * cellH + (totalRows - 1).coerceAtLeast(0) * rowGap
        val maxScroll = (contentH - gridRect.height()).coerceAtLeast(0f)
        onMaxScroll(maxScroll)
        val clampedScrollY = scrollY.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(gridRect)

        apps.forEachIndexed { index, app ->
            val r = index / cols
            val c = index % cols
            val left = gridRect.left + c * (cellW + colGap)
            val top = gridRect.top + r * (cellH + rowGap) - clampedScrollY
            val tileRect = RectF(left, top, left + cellW, top + cellH)

            if (tileRect.bottom < gridRect.top || tileRect.top > gridRect.bottom) return@forEachIndexed
            hitBoxes.add(tileRect to app.packageName)

            val iconLeft = tileRect.centerX() - iconSize / 2f
            val iconTop = tileRect.top
            val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

            val iconBmp = app.icon?.takeIf { !it.isRecycled }
            if (iconBmp != null) {
                val clipPath = Path().apply { addRoundRect(iconRect, 18f, 18f, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clipPath)
                canvas.drawBitmap(iconBmp, null, iconRect, bitmapPaint)
                canvas.restore()
            } else {
                canvas.drawRoundRect(iconRect, 18f, 18f, headerPillPaint)
            }

            val ellipsized = TextUtils.ellipsize(app.label, labelPaint, (cellW - 8f).coerceAtLeast(32f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipsized, tileRect.centerX(), iconRect.bottom + 24f, labelPaint)
        }

        canvas.restore()
    }

    private fun chooseEglConfig(recordable: Boolean): EGLConfig? {
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            if (recordable) EGL_RECORDABLE_ANDROID else EGL14.EGL_NONE, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        return if (EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            configs[0]
        } else {
            null
        }
    }

    private fun makeCurrent(surface: EGLSurface) {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
        }
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun create2DTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun drawQuad(
        prog: Int,
        posAttr: Int,
        texAttr: Int,
        texMatrixUniform: Int,
        target: Int,
        textureId: Int,
        matrix: FloatArray
    ) {
        val qb = quadBuffer ?: return
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, textureId)
        qb.position(0)
        GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 16, qb)
        GLES20.glEnableVertexAttribArray(posAttr)
        qb.position(2)
        GLES20.glVertexAttribPointer(texAttr, 2, GLES20.GL_FLOAT, false, 16, qb)
        GLES20.glEnableVertexAttribArray(texAttr)
        GLES20.glUniformMatrix4fv(texMatrixUniform, 1, false, matrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun linkProgram(vs: Int, fs: Int): Int {
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun runSync(h: Handler, block: () -> Unit) {
        if (Looper.myLooper() == h.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        h.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(1500, TimeUnit.MILLISECONDS) }
    }
}
