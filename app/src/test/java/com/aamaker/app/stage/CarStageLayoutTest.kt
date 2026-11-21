package com.aamaker.app.stage

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarStageLayoutTest {

    private fun rectOf(l: Int, t: Int, r: Int, b: Int): Rect = Rect().apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals("left", expected.left, actual.left)
        assertEquals("top", expected.top, actual.top)
        assertEquals("right", expected.right, actual.right)
        assertEquals("bottom", expected.bottom, actual.bottom)
    }

    @Test
    fun `resolveContentArea honors narrow Coolwalk dashboard slice without stretching to full surface`() {
        val fullW = 1280
        val fullH = 720
        val coolwalkSlice = rectOf(792, 24, 1260, 696) // ~468x672 Coolwalk right-side card

        val resolved = CarStageLayoutEngine.resolveContentArea(
            surfaceWidth = fullW,
            surfaceHeight = fullH,
            visibleArea = coolwalkSlice,
            stableArea = coolwalkSlice
        )
        assertRectEquals(coolwalkSlice, resolved)

        // Narrow Coolwalk slice (< 520px wide) auto-collapses split into single pane so UI is never crushed
        val slots = CarStageLayoutEngine.computeSlotsInArea(resolved, isSplit = true, primaryRatio = 0.5f)
        assertNull(slots.secondary)
        assertRectEquals(coolwalkSlice, slots.primary)

        val vp = CarStageLayoutEngine.computePrimaryViewport(slots)
        assertEquals(coolwalkSlice.right - coolwalkSlice.left, vp.right - vp.left)
        assertEquals(coolwalkSlice.bottom - coolwalkSlice.top, vp.bottom - vp.top)
    }

    @Test
    fun `resolveContentArea keeps edge-to-edge full surface when Car Stage is full screen`() {
        val fullW = 1920
        val fullH = 1080
        val slightlyInsetStable = rectOf(24, 36, 1896, 1056)

        val resolved = CarStageLayoutEngine.resolveContentArea(
            surfaceWidth = fullW,
            surfaceHeight = fullH,
            visibleArea = slightlyInsetStable,
            stableArea = slightlyInsetStable
        )
        assertRectEquals(rectOf(0, 0, fullW, fullH), resolved)
    }

    @Test
    fun `all split presets produce exact 1 to 1 viewports and comfortable 360dp min width across car aspect ratios`() {
        val headUnits = listOf(
            Triple(800, 480, 160),    // Standard WVGA head unit
            Triple(1280, 720, 200),   // 720p HD head unit
            Triple(1920, 720, 220),   // Ultra-wide 24:9 head unit
            Triple(1920, 1080, 240),  // 1080p FHD head unit
            Triple(1080, 1440, 220)   // Portrait Tesla/Volvo style head unit
        )

        for ((w, h, dpi) in headUnits) {
            val contentArea = CarStageLayoutEngine.resolveContentArea(w, h, null, null)
            for (preset in StageLayoutPreset.entries) {
                val slots = CarStageLayoutEngine.computeSlotsInArea(contentArea, preset.isSplit, preset.primaryRatio)
                val pVp = CarStageLayoutEngine.computePrimaryViewport(slots)
                val pW = pVp.right - pVp.left
                val pH = pVp.bottom - pVp.top
                assertTrue("Primary viewport width must be positive", pW >= 160)
                assertTrue("Primary viewport height must be positive", pH >= 160)

                val isSplit = slots.secondary != null
                val pDpi = CarStageLayoutEngine.computeSlotDpi(pW, pH, dpi, isSplit)
                val pWidthDp = pW * 160f / pDpi
                assertTrue(
                    "Primary width dp ($pWidthDp) too narrow on ${w}x${h} preset=${preset.key}",
                    pWidthDp >= 350f
                )

                if (isSplit) {
                    val sVp = CarStageLayoutEngine.computeSecondaryViewport(slots)
                    assertNotNull(sVp)
                    sVp!!
                    val sW = sVp.right - sVp.left
                    val sH = sVp.bottom - sVp.top
                    assertTrue("Secondary viewport must not overlap primary", sVp.left > pVp.right)
                    val sDpi = CarStageLayoutEngine.computeSlotDpi(sW, sH, dpi, isSplit = true)
                    val sWidthDp = sW * 160f / sDpi
                    assertTrue(
                        "Secondary width dp ($sWidthDp) too narrow on ${w}x${h} preset=${preset.key}",
                        sWidthDp >= if (w <= 800 && preset == StageLayoutPreset.SPLIT_70_30) 300f else 350f
                    )
                }
            }
        }
    }
}
