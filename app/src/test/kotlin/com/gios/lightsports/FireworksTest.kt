package com.gios.lightsports

import com.gios.lightsports.anim.Fireworks
import com.gios.lightsports.anim.Grid
import com.gios.lightsports.anim.Halftone
import com.gios.lightsports.anim.Mortar
import com.gios.lightsports.model.Celebration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The celebrations, in the half that can be checked without a panel in the room.
 *
 * What this cannot test is whether any of them looks good, which is what the preview row
 * in settings is for. What it can test is that none of them lights the whole screen, that
 * they all end, and that the dither is a dither rather than a coin toss.
 */
class FireworksTest {

    // ------------------------------------------------------------------- the dither

    @Test
    fun `full brightness is on everywhere and nothing is off everywhere`() {
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertTrue(Fireworks.lit(x, y, 1f))
                assertFalse(Fireworks.lit(x, y, 0f))
                assertFalse(Fireworks.lit(x, y, -0.4f))
                assertTrue(Fireworks.lit(x, y, 2f))
            }
        }
    }

    @Test
    fun `half brightness lights half the cells`() {
        var on = 0
        for (y in 0 until 4) for (x in 0 until 4) if (Fireworks.lit(x, y, 0.5f)) on++
        assertEquals(8, on)
    }

    @Test
    fun `the pattern is fixed, not random`() {
        // The whole point. A spark holding steady at a fraction has to stipple in the same
        // places each frame, or a dim spark reads as noise on a slow panel.
        for (y in -8 until 8) {
            for (x in -8 until 8) {
                assertEquals(Fireworks.lit(x, y, 0.4f), Fireworks.lit(x, y, 0.4f))
                // And negative coordinates land on the same cell as their positive twin,
                // which is where a spark that has drifted off the left edge lives.
                assertEquals(Fireworks.lit(x, y, 0.4f), Fireworks.lit(x + 4, y + 4, 0.4f))
            }
        }
    }

    @Test
    fun `brightness is monotonic`() {
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                var seen = false
                var b = 0f
                while (b <= 1f) {
                    val on = Fireworks.lit(x, y, b)
                    // Once a cell has come on it never goes off again as it gets brighter.
                    if (seen) assertTrue("x=$x y=$y b=$b", on)
                    if (on) seen = true
                    b += 1f / 64f
                }
            }
        }
    }

    // ------------------------------------------------------------------ the clocks

    @Test
    fun `every style ends, and none outstays the longest`() {
        for (style in Celebration.entries) {
            val span = Fireworks.durationMillis(style)
            assertTrue("$style", span in 1L..3000L)
            assertTrue("$style", span <= Fireworks.LONGEST)
        }
        assertEquals(Fireworks.durationMillis(Celebration.MORTAR), Fireworks.LONGEST)
    }

    @Test
    fun `a stored name we lost reads as the default`() {
        assertEquals(Celebration.DEFAULT, Celebration.byName(null))
        assertEquals(Celebration.DEFAULT, Celebration.byName("FOUNTAIN"))
        assertEquals(Celebration.MORTAR, Celebration.byName("MORTAR"))
    }

    // --------------------------------------------------------------------- mortar

    @Test
    fun `three shells, staggered, and all of them open before the end`() {
        assertEquals(3, Mortar.SHELLS.size)
        val fired = Mortar.SHELLS.map { it.fireAtMillis }
        assertEquals(fired.sorted(), fired)
        assertEquals(fired.distinct(), fired)
        for (shell in Mortar.SHELLS) {
            // Every shell has to climb, open and have its sparks die inside the run.
            val ends = shell.fireAtMillis + Mortar.RISE_MILLIS + Mortar.LIFE_MIN + Mortar.LIFE_SPAN
            assertTrue("$shell", ends <= Fireworks.durationMillis(Celebration.MORTAR))
            assertTrue("$shell", shell.x > 0f && shell.x < 1f)
            assertTrue("$shell", shell.peak > 0f && shell.peak < 0.5f)
        }
    }

    @Test
    fun `a shell climbs the whole way and stops`() {
        assertEquals(0f, Mortar.riseProgress(0L), 0.001f)
        assertEquals(1f, Mortar.riseProgress(Mortar.RISE_MILLIS), 0.001f)
        assertEquals(1f, Mortar.riseProgress(Mortar.RISE_MILLIS * 4), 0.001f)
        assertEquals(0f, Mortar.riseProgress(-50L), 0.001f)
        // Eased: past halfway by the time half the climb has gone, so it arrives slowly.
        assertTrue(Mortar.riseProgress(Mortar.RISE_MILLIS / 2) > 0.5f)
        var previous = -1f
        var t = 0L
        while (t <= Mortar.RISE_MILLIS) {
            val p = Mortar.riseProgress(t)
            assertTrue("t=$t", p >= previous)
            previous = p
            t += 10L
        }
    }

    @Test
    fun `a spark fades out and thins as it goes`() {
        assertEquals(1f, Mortar.sparkBrightness(0f), 0.001f)
        assertEquals(0f, Mortar.sparkBrightness(1f), 0.001f)
        assertEquals(0f, Mortar.sparkBrightness(3f), 0.001f)
        assertEquals(2, Mortar.sparkRadius(0f))
        assertEquals(1, Mortar.sparkRadius(0.9f))
    }

    // ----------------------------------------------------------------------- grid

    @Test
    fun `the grid is the SDK's grid`() {
        assertEquals(27, Grid.COLS)
        assertEquals(31, Grid.ROWS)
    }

    @Test
    fun `the field never goes white`() {
        // The one that matters on an OLED. Lighting every pixel is the most expensive
        // thing this app can do, and no frame of this effect ever asks for it.
        var t = 0f
        while (t <= Fireworks.durationMillis(Celebration.GRID)) {
            for (col in 0 until Grid.COLS) {
                for (jitter in listOf(0f, 0.5f, 1f)) {
                    val b = Grid.brightness(col, t, jitter)
                    assertTrue("col=$col t=$t", b <= Grid.PEAK)
                    assertTrue("col=$col t=$t", b >= 0f)
                }
            }
            t += 10f
        }
        assertTrue(Grid.PEAK < 0.6f)
    }

    @Test
    fun `the wave reaches the right edge first and the left edge last`() {
        val early = 60f
        assertTrue(Grid.brightness(Grid.COLS - 1, early, 0f) > Grid.brightness(0, early, 0f))
        // And nothing is left lit when it is over.
        val over = Fireworks.durationMillis(Celebration.GRID).toFloat()
        for (col in 0 until Grid.COLS) {
            assertEquals("col=$col", 0f, Grid.brightness(col, over, 0f), 0.001f)
        }
    }

    // ------------------------------------------------------------------- halftone

    @Test
    fun `three rings leave in turn and all of them arrive`() {
        assertEquals(3, Halftone.RINGS)
        val span = Fireworks.durationMillis(Celebration.HALFTONE).toFloat()
        for (ring in 0 until Halftone.RINGS) {
            assertNull("ring $ring before its turn", Halftone.progress(ring, -1f))
            assertNotNull("ring $ring at its turn", Halftone.progress(ring, ring * Halftone.STAGGER_MILLIS))
            assertNull("ring $ring after the end", Halftone.progress(ring, span + 1f))
        }
        // The last ring has to finish inside the run, or the effect ends mid-flight.
        val last = (Halftone.RINGS - 1) * Halftone.STAGGER_MILLIS + Halftone.TRAVEL_MILLIS
        assertTrue(last <= span)
    }

    @Test
    fun `a ring widens, thins and fades`() {
        assertEquals(Halftone.RADIUS_START, Halftone.radius(0f), 0.0001f)
        assertEquals(Halftone.RADIUS_END, Halftone.radius(1f), 0.0001f)
        assertEquals(1f, Halftone.fade(0f), 0.001f)
        assertEquals(0f, Halftone.fade(1f), 0.001f)
        // More dots on a bigger ring, so the gap between them stays put. That is the
        // halftone: the ring thins because there is more of it, not because it fades.
        assertTrue(Halftone.dots(400f) > Halftone.dots(80f))
        // And never so few that a ring reads as a handful of dots in a circle.
        assertTrue(Halftone.dots(1f) >= 12)
    }
}
