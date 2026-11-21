package com.aamaker.app.stage

import android.graphics.Rect

enum class StageLayoutPreset(val key: String, val primaryRatio: Float) {
    SINGLE("single", 1.0f),
    SPLIT_50_50("split_50_50", 0.50f),
    SPLIT_60_40("split_60_40", 0.60f),
    SPLIT_70_30("split_70_30", 0.70f);

    val isSplit: Boolean get() = this != SINGLE

    companion object {
        fun fromKey(key: String?): StageLayoutPreset =
            entries.firstOrNull { it.key == key } ?: SINGLE
    }
}

enum class StageDockEdge(val key: String) {
    LEFT("left"),
    RIGHT("right"),
    BOTTOM("bottom");

    companion object {
        // Always lock Car Screen control buttons to Top-Left across all modes
        fun fromKey(@Suppress("UNUSED_PARAMETER") key: String?): StageDockEdge = LEFT
    }
}

data class StageSlotBounds(
    val primary: Rect,
    val secondary: Rect?
)

/**
 * Computes non-overlapping pixel bounds and exact inner card viewports for Primary and Secondary
 * StageDisplaySlots on the Android Auto head unit screen, guaranteeing 1:1 pixel aspect ratio
 * between VirtualDisplay buffers and OpenGL ES compositor viewports (zero stretch / distortion).
 */
object CarStageLayoutEngine {
    const val SEAM_PX = 4
    private const val SPLIT_CARD_PAD_PX = 6

    private fun makeRect(l: Int, t: Int, r: Int, b: Int): Rect = Rect().apply {
        left = l
        top = t
        right = r
        bottom = b
    }
    private fun rectW(r: Rect): Int = r.right - r.left
    private fun rectH(r: Rect): Int = r.bottom - r.top
    private fun isValidSubRect(r: Rect?, cw: Int, ch: Int): Boolean =
        r != null && r.right > r.left && r.bottom > r.top &&
            r.left >= 0 && r.top >= 0 && r.right <= cw && r.bottom <= ch

    /**
     * Resolves the usable content rect on the Android Auto surface:
     * - When in Coolwalk dashboard slice (narrower than 80% of full surface width, or inset vertically),
     *   uses the exact visible/stable slice so the app renders into its true aspect ratio without stretching!
     * - When in full-screen Car Stage view (>= 80% of surface width), uses the full surface edge-to-edge
     *   so transient Android Auto overlays showing/hiding never cause layout jumps or aspect squeezing.
     */
    fun resolveContentArea(
        surfaceWidth: Int,
        surfaceHeight: Int,
        visibleArea: Rect?,
        stableArea: Rect?
    ): Rect {
        val cw = surfaceWidth.coerceAtLeast(320)
        val ch = surfaceHeight.coerceAtLeast(240)
        val full = makeRect(0, 0, cw, ch)

        val candidate = when {
            isValidSubRect(stableArea, cw, ch) -> stableArea!!
            isValidSubRect(visibleArea, cw, ch) -> visibleArea!!
            else -> return full
        }

        // Dashboard card / Coolwalk split tile: strictly honor the host's card rect so nothing is squished!
        if (rectW(candidate) < cw * 0.80f || rectH(candidate) < ch * 0.72f) {
            return makeRect(candidate.left, candidate.top, candidate.right, candidate.bottom)
        }
        return full
    }

    fun computeSlots(
        surfaceWidth: Int,
        surfaceHeight: Int,
        preset: StageLayoutPreset
    ): StageSlotBounds {
        val w = surfaceWidth.coerceAtLeast(320)
        val h = surfaceHeight.coerceAtLeast(240)
        return computeSlotsInArea(makeRect(0, 0, w, h), preset.isSplit, preset.primaryRatio)
    }

    fun computeSlotsInArea(
        contentArea: Rect,
        isSplit: Boolean,
        primaryRatio: Float
    ): StageSlotBounds {
        val areaW = rectW(contentArea).coerceAtLeast(240)
        val areaH = rectH(contentArea).coerceAtLeast(180)
        val safeArea = makeRect(
            contentArea.left,
            contentArea.top,
            contentArea.left + areaW,
            contentArea.top + areaH
        )
        // Auto-fallback to single pane if the Coolwalk dashboard slice is too narrow for 2 side-by-side apps
        if (!isSplit || areaW < 520) {
            return StageSlotBounds(
                primary = safeArea,
                secondary = null
            )
        }
        val minPaneW = minOf(220, (areaW - SEAM_PX) / 2).coerceAtLeast(120)
        val maxPrimaryW = (areaW - SEAM_PX - minPaneW).coerceAtLeast(minPaneW)
        val clampedRatio = primaryRatio.coerceIn(0.30f, 0.75f)
        val primaryW = ((areaW - SEAM_PX) * clampedRatio).toInt().coerceIn(minPaneW, maxPrimaryW)
        val primaryRect = makeRect(safeArea.left, safeArea.top, safeArea.left + primaryW, safeArea.bottom)
        val secondaryRect = makeRect(primaryRect.right + SEAM_PX, safeArea.top, safeArea.right, safeArea.bottom)
        return StageSlotBounds(
            primary = primaryRect,
            secondary = secondaryRect
        )
    }

    /**
     * Exact pixel rect where the Primary VirtualDisplay texture is drawn on screen,
     * AND the exact pixel resolution at which the Primary VirtualDisplay & SurfaceTexture must be created/resized.
     */
    fun computePrimaryViewport(slots: StageSlotBounds): Rect {
        val p = slots.primary
        val isSplit = slots.secondary != null
        if (!isSplit) return makeRect(p.left, p.top, p.right, p.bottom)
        val pad = SPLIT_CARD_PAD_PX
        return makeRect(
            p.left + pad,
            p.top + pad,
            (p.right - pad / 2).coerceAtLeast(p.left + pad + 64),
            (p.bottom - pad).coerceAtLeast(p.top + pad + 64)
        )
    }

    /**
     * Exact pixel rect where the Secondary VirtualDisplay texture is drawn on screen,
     * AND the exact pixel resolution at which the Secondary VirtualDisplay & SurfaceTexture must be created/resized.
     */
    fun computeSecondaryViewport(slots: StageSlotBounds): Rect? {
        val s = slots.secondary ?: return null
        val pad = SPLIT_CARD_PAD_PX
        return makeRect(
            s.left + pad / 2,
            s.top + pad,
            (s.right - pad).coerceAtLeast(s.left + pad / 2 + 64),
            (s.bottom - pad).coerceAtLeast(s.top + pad + 64)
        )
    }

    /**
     * Computes an isotropic adaptive DPI for a VirtualDisplay slot so standard Android apps
     * always receive comfortable dp width & height (>= 360dp smallest width in split-screen,
     * >= 420dp in full-screen) without oversized/bloated UI elements, clipped layouts, or font distortion.
     */
    fun computeSlotDpi(
        slotWidth: Int,
        slotHeight: Int,
        baseDpi: Int,
        isSplit: Boolean
    ): Int {
        val safeW = slotWidth.coerceAtLeast(160)
        val safeH = slotHeight.coerceAtLeast(160)
        val minSidePx = minOf(safeW, safeH)
        val targetMinDp = if (isSplit) 370f else 420f
        val idealByMinSide = (minSidePx * 160f) / targetMinDp
        // Also ensure horizontal width in narrow split panes (e.g. 30% or 40% pane) stays >= 360dp
        // so phone layouts never crush columns or overlap buttons horizontally!
        val minTargetWidthDp = if (isSplit) 360f else 520f
        val maxDpiByWidth = (safeW * 160f) / minTargetWidthDp
        val rawDpi = minOf(idealByMinSide, maxDpiByWidth).toInt()
        val maxAllowedDpi = if (isSplit) baseDpi.coerceIn(130, 200) else baseDpi.coerceIn(150, 240)
        return rawDpi.coerceIn(110, maxAllowedDpi)
    }
}

/**
 * Minimalist auto-hide overlay state for the Android Auto car screen:
 * - Collapsed at rest to a single translucent edge handle (alpha = 0.35f)
 * - Expands only essential actions (Back, Swap/Split, Collapse) on tap
 * - Automatically collapses after autoHideSeconds of inactivity
 */
data class MinimalOverlayState(
    val isExpanded: Boolean = false,
    val dockEdge: StageDockEdge = StageDockEdge.LEFT,
    val autoHideSeconds: Int = 4,
    val idleAlpha: Float = 0.35f,
    val activeAlpha: Float = 0.88f
) {
    val currentAlpha: Float get() = if (isExpanded) activeAlpha else idleAlpha
}
