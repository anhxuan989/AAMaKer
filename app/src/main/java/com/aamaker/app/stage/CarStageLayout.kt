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
        fun fromKey(key: String?): StageDockEdge =
            entries.firstOrNull { it.key == key } ?: LEFT
    }
}

data class StageSlotBounds(
    val primary: Rect,
    val secondary: Rect?
)

/**
 * Computes non-overlapping pixel bounds for Primary and Secondary StageDisplaySlots
 * on the Android Auto head unit screen.
 */
object CarStageLayoutEngine {
    private const val SEAM_PX = 4

    fun computeSlots(
        surfaceWidth: Int,
        surfaceHeight: Int,
        preset: StageLayoutPreset
    ): StageSlotBounds {
        val w = surfaceWidth.coerceAtLeast(320)
        val h = surfaceHeight.coerceAtLeast(240)
        if (!preset.isSplit) {
            return StageSlotBounds(
                primary = Rect(0, 0, w, h),
                secondary = null
            )
        }
        val primaryW = ((w - SEAM_PX) * preset.primaryRatio).toInt().coerceIn(240, w - 240)
        val secondaryLeft = primaryW + SEAM_PX
        return StageSlotBounds(
            primary = Rect(0, 0, primaryW, h),
            secondary = Rect(secondaryLeft, 0, w, h)
        )
    }

    /**
     * Computes an adaptive DPI for a VirtualDisplay slot so standard Android apps
     * always receive at least ~360dp smallest width in split-screen (preventing UI clipping/overflow).
     */
    fun computeSlotDpi(
        slotWidth: Int,
        slotHeight: Int,
        baseDpi: Int,
        isSplit: Boolean
    ): Int {
        val minSidePx = minOf(slotWidth, slotHeight).coerceAtLeast(240)
        val targetMinDp = if (isSplit) 360f else 420f
        val idealDpi = ((minSidePx * 160f) / targetMinDp).toInt()
        val maxDpi = if (isSplit) baseDpi.coerceIn(140, 210) else baseDpi.coerceIn(160, 240)
        return idealDpi.coerceIn(120, maxDpi)
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
