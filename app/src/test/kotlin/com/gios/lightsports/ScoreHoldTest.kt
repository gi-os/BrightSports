package com.gios.lightsports

import com.gios.lightsports.notify.ScoreHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoiler delay, applied to the score rather than to the fact of it.
 *
 * Every number here is a minute against a five-minute hold. The one that matters most is the
 * first: a game the phone has only just seen shows its score at once, because nothing about it
 * is news yet — the same rule the alerts have always used.
 */
class ScoreHoldTest {

    private val delay = 5 * 60_000L
    private val t0 = 1_700_000_000_000L
    private fun min(n: Int) = t0 + n * 60_000L

    private fun samples(vararg s: Triple<Int, Int, Int>): List<ScoreHold.Sample> =
        s.map { (m, a, h) -> ScoreHold.Sample(min(m), a, h) }

    @Test
    fun `nothing seen shows nothing`() {
        assertNull(ScoreHold.visible(emptyList(), delay, min(10)))
    }

    @Test
    fun `the first score seen is shown at once`() {
        val shown = ScoreHold.visible(samples(Triple(10, 7, 0)), delay, min(10))
        assertEquals(7, shown?.away)
        assertEquals(0, shown?.home)
        assertTrue(shown!!.current)
    }

    @Test
    fun `a new score waits out the delay`() {
        val list = samples(Triple(0, 7, 0), Triple(10, 7, 7))
        val held = ScoreHold.visible(list, delay, min(12))
        assertEquals(7, held?.away)
        assertEquals(0, held?.home)
        assertFalse(held!!.current)

        val out = ScoreHold.visible(list, delay, min(15))
        assertEquals(7, out?.home)
        assertTrue(out!!.current)
    }

    @Test
    fun `a flurry is walked through in order`() {
        val list = samples(Triple(0, 0, 0), Triple(10, 7, 0), Triple(11, 7, 7), Triple(12, 14, 7))
        assertEquals(0, ScoreHold.visible(list, delay, min(14))?.away)
        assertEquals(7, ScoreHold.visible(list, delay, min(15))?.away)
        assertEquals(7, ScoreHold.visible(list, delay, min(16))?.home)
        assertEquals(14, ScoreHold.visible(list, delay, min(17))?.away)
    }

    @Test
    fun `no delay shows the newest`() {
        val list = samples(Triple(0, 0, 0), Triple(10, 7, 0))
        val shown = ScoreHold.visible(list, 0L, min(10))
        assertEquals(7, shown?.away)
        assertTrue(shown!!.current)
    }

    @Test
    fun `an unchanged score keeps the moment it arrived`() {
        var list = ScoreHold.record(emptyList(), ScoreHold.Sample(min(0), 7, 0), delay)
        list = ScoreHold.record(list, ScoreHold.Sample(min(3), 7, 0), delay)
        assertEquals(1, list.size)
        assertEquals(min(0), list.single().at)
    }

    @Test
    fun `samples behind the released one are dropped`() {
        var list = ScoreHold.record(emptyList(), ScoreHold.Sample(min(0), 0, 0), delay)
        list = ScoreHold.record(list, ScoreHold.Sample(min(1), 7, 0), delay)
        list = ScoreHold.record(list, ScoreHold.Sample(min(20), 14, 0), delay)
        // 0-0 can never be drawn again once 7-0 has been released, so it goes.
        assertEquals(listOf(7, 14), list.map { it.away })
    }

    @Test
    fun `the list never grows without bound`() {
        var list = emptyList<ScoreHold.Sample>()
        for (i in 0..40) {
            list = ScoreHold.record(list, ScoreHold.Sample(min(i), i, 0), delay)
        }
        assertTrue(list.size <= ScoreHold.MAX_SAMPLES)
    }
}
