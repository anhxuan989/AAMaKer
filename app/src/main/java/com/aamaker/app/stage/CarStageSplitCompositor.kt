package com.aamaker.app.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface

data class StagePickerApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?
)

/**
 * Dual-Slot Surface Compositor for Car Stage:
 * - Left slot: live Primary VirtualDisplay (App 1) at chosen split ratio (50:50, 60:40, 70:30).
 * - Center seam: subtle divider with a minimalist translucent handle pill.
 * - Right slot: either live Secondary VirtualDisplay (App 2) OR an interactive dark-OLED
 *   in-surface App Picker grid so the user can pick App 2 right next to live App 1.
 */
class CarStageSplitCompositor {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var primaryReader: ImageReader? = null
    private var secondaryReader: ImageReader? = null

    private var primaryBitmap: Bitmap? = null
    private var secondaryBitmap: Bitmap? = null
    private var primaryImgW: Int = 0
    private var primaryImgH: Int = 0
    private var secondaryImgW: Int = 0
    private var secondaryImgH: Int = 0

    private var outputSurface: Surface? = null
    private var bounds: StageSlotBounds? = null
    private var visibleArea: Rect? = null
    private var primaryRatio: Float = 0.5f

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
        color = Color.argb(120, 220, 225, 235)
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
        color = Color.argb(235, 28, 34, 46)
        style = Paint.Style.FILL
    }
    private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val headerPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(228, 234, 245)
        textSize = 22f
        isFakeBoldText = false
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 240, 248)
        textSize = 19f
        textAlign = Paint.Align.CENTER
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    val primarySurface: Surface? get() = primaryReader?.surface
    val secondarySurface: Surface? get() = secondaryReader?.surface
    val currentBounds: StageSlotBounds? get() = bounds

    @Synchronized
    fun start(carSurface: Surface, slotBounds: StageSlotBounds, ratio: Float = 0.5f) {
        stop()
        val secRect = slotBounds.secondary ?: return
        outputSurface = carSurface
        bounds = slotBounds
        primaryRatio = ratio.coerceIn(0.3f, 0.75f)

        val ht = HandlerThread("CarStageCompositor").also { it.start() }
        val h = Handler(ht.looper)
        thread = ht
        handler = h

        val pW = slotBounds.primary.width().coerceAtLeast(240)
        val pH = slotBounds.primary.height().coerceAtLeast(240)
        val sW = secRect.width().coerceAtLeast(240)
        val sH = secRect.height().coerceAtLeast(240)

        primaryReader = ImageReader.newInstance(pW, pH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    primaryImgW = img.width
                    primaryImgH = img.height
                    primaryBitmap = copyImageToBitmap(img, primaryBitmap)
                    compositeNow()
                } finally {
                    img.close()
                }
            }, h)
        }

        secondaryReader = ImageReader.newInstance(sW, sH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    secondaryImgW = img.width
                    secondaryImgH = img.height
                    secondaryBitmap = copyImageToBitmap(img, secondaryBitmap)
                    if (!showRightPicker) {
                        compositeNow()
                    }
                } finally {
                    img.close()
                }
            }, h)
        }
    }

    @Synchronized
    fun updateVisibleArea(area: Rect?) {
        visibleArea = area?.takeIf { !it.isEmpty }?.let { Rect(it) }
        handler?.post { compositeNow() }
    }

    @Synchronized
    fun setRatioMenu(show: Boolean) {
        showRatioMenu = show
        handler?.post { compositeNow() }
    }

    @Synchronized
    fun setDraggingSeam(dragging: Boolean) {
        isDraggingSeam = dragging
        if (dragging) showRatioMenu = false
        handler?.post { compositeNow() }
    }

    /**
     * Smoothly adjusts the live split ratio while dragging the center seam horizontally.
     * Clamped between 0.30 (30:70) and 0.75 (75:25) so neither app becomes unusable.
     */
    @Synchronized
    fun adjustRatioByDelta(deltaX: Float): Float {
        val availW = (visibleArea?.width() ?: bounds?.let {
            (it.secondary?.right ?: it.primary.right) - it.primary.left
        } ?: 1280).coerceAtLeast(320).toFloat()
        primaryRatio = (primaryRatio + deltaX / availW).coerceIn(0.30f, 0.75f)
        isDraggingSeam = true
        showRatioMenu = false
        handler?.post { compositeNow() }
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
        handler?.post { compositeNow() }
    }

    @Synchronized
    fun scrollLeftPicker(distanceY: Float) {
        if (!showLeftPicker) return
        leftPickerScrollY = (leftPickerScrollY + distanceY).coerceIn(0f, leftPickerMaxScrollY)
        handler?.post { compositeNow() }
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
        handler?.post { compositeNow() }
    }

    @Synchronized
    fun scrollRightPicker(distanceY: Float) {
        if (!showRightPicker) return
        rightPickerScrollY = (rightPickerScrollY + distanceY).coerceIn(0f, rightPickerMaxScrollY)
        handler?.post { compositeNow() }
    }

    @Synchronized
    fun hitTestSeamHandle(x: Float, y: Float): Boolean {
        val expanded = RectF(
            seamHandleRect.left - 24f,
            seamHandleRect.top - 24f,
            seamHandleRect.right + 24f,
            seamHandleRect.bottom + 24f
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
        val rect = primaryCardRect
        if (rect.width() <= 1f || rect.height() <= 1f || primaryImgW <= 0 || primaryImgH <= 0) {
            val b = bounds?.primary
            return Pair(if (b != null) x - b.left else x, if (b != null) y - b.top else y)
        }
        val nx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f) * primaryImgW
        val ny = ((y - rect.top) / rect.height()).coerceIn(0f, 1f) * primaryImgH
        return Pair(nx, ny)
    }

    @Synchronized
    fun mapTouchToSecondary(x: Float, y: Float): Pair<Float, Float> {
        val rect = secondaryCardRect
        if (rect.width() <= 1f || rect.height() <= 1f || secondaryImgW <= 0 || secondaryImgH <= 0) {
            val b = bounds?.secondary
            return Pair(if (b != null) x - b.left else x, if (b != null) y - b.top else y)
        }
        val nx = ((x - rect.left) / rect.width()).coerceIn(0f, 1f) * secondaryImgW
        val ny = ((y - rect.top) / rect.height()).coerceIn(0f, 1f) * secondaryImgH
        return Pair(nx, ny)
    }

    @Synchronized
    fun stop() {
        runCatching { primaryReader?.close() }
        runCatching { secondaryReader?.close() }
        primaryReader = null
        secondaryReader = null
        // Let GC collect bitmaps safely to avoid recycled-bitmap race with Canvas.drawBitmap
        primaryBitmap = null
        secondaryBitmap = null
        outputSurface = null
        bounds = null
        showLeftPicker = false
        showRightPicker = false
        showRatioMenu = false
        isDraggingSeam = false
        leftTileHitBoxes.clear()
        tileHitBoxes.clear()
        ratioMenuHitBoxes.clear()
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
    }

    private fun copyImageToBitmap(image: Image, reuse: Bitmap?): Bitmap? {
        return runCatching {
            val plane = image.planes.firstOrNull() ?: return reuse
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val fullW = width + rowPadding / pixelStride
            if (fullW <= 0 || height <= 0) return reuse

            val bmp = if (reuse != null && !reuse.isRecycled && reuse.width == fullW && reuse.height == height) {
                reuse
            } else {
                Bitmap.createBitmap(fullW, height, Bitmap.Config.ARGB_8888)
            }
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            bmp
        }.getOrDefault(reuse)
    }

    @Synchronized
    private fun compositeNow() {
        val surface = outputSurface ?: return
        val slotBounds = bounds ?: return
        if (slotBounds.secondary == null) return
        if (!surface.isValid) return

        // Use software lockCanvas instead of lockHardwareCanvas:
        // On Android 14-16 (e.g. HyperOS 3), Surface.lockHardwareCanvas() creates a HwuiContext on
        // RenderThread whose EGLSurface can be null/lost on Android Auto's remote SurfaceContainer
        // (especially after VirtualDisplay detach or during surface teardown), triggering an
        // uncatchable native SIGABRT: "drawRenderNode called on a context with no surface!".
        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return

        try {
            canvas.drawColor(Color.BLACK)

            // Always compute exact destination rects inside Android Auto's unobstructed visibleArea
            val cw = canvas.width.coerceAtLeast(320)
            val ch = canvas.height.coerceAtLeast(240)
            val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, cw, ch)
            val availW = area.width().coerceAtLeast(320)
            val seamPx = 4
            val leftW = ((availW - seamPx) * primaryRatio).toInt().coerceIn(200, availW - 200)
            val dstPrimary = Rect(area.left, area.top, area.left + leftW, area.bottom)
            val dstSecondary = Rect(area.left + leftW + seamPx, area.top, area.right, area.bottom)
            bounds = StageSlotBounds(dstPrimary, dstSecondary)

            // 1. Left slot: either in-surface App Picker Grid OR live App 1 inside a rounded card
            if (showLeftPicker) {
                drawAppPickerInSlot(
                    canvas = canvas,
                    slotRect = dstPrimary,
                    title = leftPickerHeaderTitle,
                    apps = leftPickerApps,
                    hitBoxes = leftTileHitBoxes,
                    scrollY = leftPickerScrollY,
                    onMaxScroll = { leftPickerMaxScrollY = it }
                )
            } else {
                primaryBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
                    val srcW = primaryImgW.takeIf { it > 0 }?.coerceAtMost(bmp.width) ?: bmp.width
                    val srcH = primaryImgH.takeIf { it > 0 }?.coerceAtMost(bmp.height) ?: bmp.height
                    val pad = 10f
                    val cardRect = RectF(
                        dstPrimary.left + pad,
                        dstPrimary.top + pad,
                        dstPrimary.right - pad * 0.5f,
                        dstPrimary.bottom - pad
                    )
                    primaryCardRect = cardRect
                    val src = computeAspectPreservedSrc(srcW, srcH, cardRect.width(), cardRect.height(), isDraggingSeam)
                    val clipPath = Path().apply { addRoundRect(cardRect, 24f, 24f, Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(bmp, src, cardRect, bitmapPaint)
                    canvas.restore()
                }
            }

            // 2. Center seam + minimalist translucent pill handle
            val seamLeft = dstPrimary.right.toFloat()
            val seamRight = dstSecondary.left.toFloat()
            canvas.drawRect(seamLeft, area.top.toFloat(), seamRight, area.bottom.toFloat(), seamPaint)

            val cx = (seamLeft + seamRight) * 0.5f
            val cy = (area.top + area.bottom) * 0.5f
            seamHandleRect = RectF(cx - 4f, cy - 36f, cx + 4f, cy + 36f)
            canvas.drawRoundRect(seamHandleRect, 4f, 4f, if (isDraggingSeam) handleActivePaint else handlePaint)

            // 3. Right slot: either in-surface App Picker Grid OR live App 2 inside a matching rounded card
            if (showRightPicker) {
                drawRightAppPicker(canvas, dstSecondary)
            } else {
                secondaryBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
                    val srcW = secondaryImgW.takeIf { it > 0 }?.coerceAtMost(bmp.width) ?: bmp.width
                    val srcH = secondaryImgH.takeIf { it > 0 }?.coerceAtMost(bmp.height) ?: bmp.height
                    val pad = 10f
                    val cardRect = RectF(
                        dstSecondary.left + pad * 0.5f,
                        dstSecondary.top + pad,
                        dstSecondary.right - pad,
                        dstSecondary.bottom - pad
                    )
                    secondaryCardRect = cardRect
                    val src = computeAspectPreservedSrc(srcW, srcH, cardRect.width(), cardRect.height(), isDraggingSeam)
                    val clipPath = Path().apply { addRoundRect(cardRect, 24f, 24f, Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(bmp, src, cardRect, bitmapPaint)
                    canvas.restore()
                }
            }

            // Live ratio badge while dragging the seam
            if (isDraggingSeam) {
                val leftPct = (primaryRatio * 100).toInt()
                val rightPct = 100 - leftPct
                val badgeRect = RectF(cx - 56f, cy - 22f, cx + 56f, cy + 22f)
                canvas.drawRoundRect(badgeRect, 14f, 14f, panelPaint)
                canvas.drawRoundRect(badgeRect, 14f, 14f, panelStrokePaint)
                canvas.drawText("$leftPct : $rightPct", badgeRect.centerX(), badgeRect.centerY() + 7f, labelPaint)
            }

            // 4. Floating dropdown menu on the center seam for 1-tap ratio selection
            if (showRatioMenu) {
                drawRatioDropdownMenu(canvas, cx, area.top + 28f)
            } else {
                ratioMenuHitBoxes.clear()
            }
        } catch (_: Throwable) {
            // Swallow transient Canvas/Surface errors so Car Stage never crashes Android Auto
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    /**
     * Prevents horizontal/vertical stretching while dragging by center-cropping the source
     * to match the destination card's aspect ratio until the VirtualDisplays finish resizing.
     */
    private fun computeAspectPreservedSrc(
        srcW: Int,
        srcH: Int,
        dstW: Float,
        dstH: Float,
        preserveAspect: Boolean
    ): Rect {
        if (!preserveAspect || srcW <= 0 || srcH <= 0 || dstW <= 1f || dstH <= 1f) {
            return Rect(0, 0, srcW, srcH)
        }
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val dstAspect = dstW / dstH
        return if (srcAspect > dstAspect) {
            val cropW = (srcH * dstAspect).toInt().coerceIn(1, srcW)
            val left = (srcW - cropW) / 2
            Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / dstAspect).toInt().coerceIn(1, srcH)
            val top = (srcH - cropH) / 2
            Rect(0, top, srcW, top + cropH)
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
            onMaxScroll = { rightPickerMaxScrollY = it }
        )
    }

    private fun drawAppPickerInSlot(
        canvas: Canvas,
        slotRect: Rect,
        title: String,
        apps: List<StagePickerApp>,
        hitBoxes: MutableList<Pair<RectF, String>>,
        scrollY: Float,
        onMaxScroll: (Float) -> Unit
    ) {
        canvas.drawRect(slotRect, pickerBgPaint)
        hitBoxes.clear()

        // Outer frosted dark glass panel
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
        val headerH = 44f
        val headerRect = RectF(
            panelRect.left + innerPad,
            panelRect.top + innerPad,
            panelRect.right - innerPad,
            panelRect.top + innerPad + headerH
        )
        canvas.drawRoundRect(headerRect, 14f, 14f, headerPillPaint)
        if (title.isNotBlank()) {
            val titleEllipsized = TextUtils.ellipsize(
                title,
                headerPaint,
                headerRect.width() - 28f,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(titleEllipsized, headerRect.left + 16f, headerRect.centerY() + 8f, headerPaint)
        }

        if (apps.isEmpty()) return

        val gridRect = RectF(
            panelRect.left + innerPad,
            headerRect.bottom + 16f,
            panelRect.right - innerPad,
            panelRect.bottom - innerPad
        )
        val availW = gridRect.width().coerceAtLeast(120f)
        val cols = if (availW >= 340f) 3 else 2
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

            // Crisp rounded-square app icon
            app.icon?.takeIf { !it.isRecycled }?.let { bmp ->
                val clipPath = Path().apply { addRoundRect(iconRect, 18f, 18f, Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(clipPath)
                canvas.drawBitmap(bmp, null, iconRect, bitmapPaint)
                canvas.restore()
            }

            val ellipsized = TextUtils.ellipsize(app.label, labelPaint, cellW - 8f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipsized, tileRect.centerX(), iconRect.bottom + 24f, labelPaint)
        }

        canvas.restore()
    }
}
