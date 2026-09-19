package com.gios.lightsports.anim

import com.gios.lightsports.model.Celebration
import kotlin.math.pow

/**
 * The arithmetic behind the celebrations, with no Android and no Compose in it.
 *
 * Same reason [com.gios.lightsports.notify.TickerPlan] is shaped this way: the part that
 * decides what a thing looks like and what it costs should be provable without a phone in
 * the room. [com.gios.lightsports.ui.CelebrationOverlay] does the drawing; this decides
 * what it draws.
 *
 * Two rules run through all of it, and both come from the panel rather than from taste.
 *
 * The Light Phone III is an AMOLED behind matte glass. **A white pixel is a lit pixel**,
 * so a full-screen flash is not a cheap effect, it is the most expensive one there is, and
 * nothing here ever lights the whole panel. **And the diffuser smears fine work**, so
 * everything is drawn as small solid blocks at full white rather than as soft edges that
 * would arrive as grey mush.
 *
 * Which is why brightness is a dither and not an alpha. A spark at four tenths is not a
 * grey spark; it is a white pixel that is on four times in ten, in a fixed pattern the eye
 * reads as dimmer. See [lit].
 */
object Fireworks {

    /**
     * A 4x4 ordered (Bayer) threshold matrix.
     *
     * Ordered rather than random because the pattern has to be stable between frames. A
     * spark holding steady at half brightness must stipple in the same place each frame,
     * or it reads as noise rather than as a dim spark, and on a slow panel noise is all
     * you see.
     */
    private val BAYER = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    /**
     * Whether the pixel at [x], [y] is on, for something drawn at brightness [b] (0..1).
     *
     * Takes the coordinates, not just the brightness, because that is the whole trick: the
     * threshold comes from where the thing is on the screen, so neighbouring pixels of one
     * dim shape switch on in a pattern rather than all together.
     */
    fun lit(x: Int, y: Int, b: Float): Boolean {
        if (b >= 1f) return true
        if (b <= 0f) return false
        val ix = ((x % 4) + 4) % 4
        val iy = ((y % 4) + 4) % 4
        return b > (BAYER[iy * 4 + ix] + 0.5f) / 16f
    }

    /** How long one run of each style lasts, end to end. */
    fun durationMillis(style: Celebration): Long = when (style) {
        Celebration.MORTAR -> 2300L
        Celebration.GRID -> 900L
        Celebration.BURST -> 1400L
        Celebration.HALFTONE -> 1500L
    }

    /**
     * The longest any of them runs.
     *
     * The screen holds a celebration off while one is already up, and this is the window
     * it holds it for. A basketball game can score twice inside two seconds and the second
     * one must not restart the first from the top.
     */
    val LONGEST: Long = Celebration.entries.maxOf { durationMillis(it) }
}

/**
 * Three shells up from the bottom edge, bursting in turn.
 *
 * Speeds are in screen heights per second rather than pixels, so the same numbers give the
 * same picture on the phone's 1240 px panel and in a preview a third that size. Positions
 * are fractions of the drawing for the same reason.
 */
object Mortar {

    /** How long a shell takes to climb before it opens. */
    const val RISE_MILLIS = 260L

    /** Downward pull on a spark, in screen heights per second squared. */
    const val GRAVITY = 0.31f

    /** How far apart the five pixels of a climbing shell's tail sit, as a fraction of height. */
    const val TAIL_GAP = 0.011f

    const val TAIL_LENGTH = 5

    /**
     * One shell: when it goes up, where from, how high, and how many sparks it opens into.
     *
     * Three of them, and the middle one is the highest: two shells read as a pair and a
     * mistake, three read as a display. They are staggered rather than simultaneous for
     * the same reason — fireworks that all open at once look like one firework.
     */
    data class Shell(
        val fireAtMillis: Long,
        /** Across the drawing, 0..1. */
        val x: Float,
        /** Down the drawing, 0..1. Where the shell stops climbing and opens. */
        val peak: Float,
        val sparks: Int,
    )

    val SHELLS = listOf(
        Shell(fireAtMillis = 0L, x = 0.26f, peak = 0.34f, sparks = 48),
        Shell(fireAtMillis = 340L, x = 0.72f, peak = 0.46f, sparks = 40),
        Shell(fireAtMillis = 640L, x = 0.48f, peak = 0.22f, sparks = 52),
    )

    /** Every spark of every shell, so the whole display is one flat array to walk. */
    val TOTAL_SPARKS = SHELLS.sumOf { it.sparks }

    /** How fast a spark leaves the burst, in screen heights per second. A spread, not a value. */
    const val SPEED_MIN = 0.19f
    const val SPEED_SPAN = 0.18f

    /** How long a spark lives, in milliseconds. Also a spread: a ring that dies evenly is a ring. */
    const val LIFE_MIN = 900f
    const val LIFE_SPAN = 500f

    /**
     * How far up a climbing shell has got, 0 at the bottom edge and 1 at its peak.
     *
     * Eased so it leaves fast and arrives slow, which is both what a mortar does and what
     * makes the pause before the burst read as a pause rather than as a dropped frame.
     */
    fun riseProgress(sinceFireMillis: Long): Float {
        if (sinceFireMillis <= 0L) return 0f
        if (sinceFireMillis >= RISE_MILLIS) return 1f
        val p = sinceFireMillis.toFloat() / RISE_MILLIS
        return 1f - (1f - p).pow(1.7f)
    }

    /** A spark's brightness at [age], which runs 0 at the burst to 1 at the end of its life. */
    fun sparkBrightness(age: Float): Float = (1f - age).coerceIn(0f, 1f)

    /** A spark is drawn two pixels wide until it is most of the way gone, then one. */
    fun sparkRadius(age: Float): Int = if (age > 0.62f) 1 else 2
}

/**
 * The layout grid, lighting up and falling away.
 *
 * 27 x 31 is not a decorative choice: it is the Light SDK's own grid, the 40 px unit every
 * screen in the app is laid out on. Drawn as whole cells, one rectangle each, with the
 * dither deciding per cell rather than per pixel — 837 rectangles a frame at the very
 * most, no particle system, and nothing to allocate.
 */
object Grid {

    const val COLS = 27
    const val ROWS = 31

    /** How long the wave takes to cross the whole drawing. */
    const val SWEEP_MILLIS = 250f

    /** How long a single cell takes to come up once the wave reaches it. */
    const val RISE_MILLIS = 180f

    /** When the field stops filling and starts dropping out. */
    const val HOLD_UNTIL_MILLIS = 520f

    const val FALL_MILLIS = 320f

    /**
     * The brightness the field ever reaches, and the reason this one is cheap.
     *
     * At 0.55 a little over half the cells are on at the peak, so the panel never goes
     * white. That is a battery decision first and it turns out to be the better picture
     * too: a field of blocks with gaps in it reads as the grid, where a solid white screen
     * reads as a fault.
     */
    const val PEAK = 0.55f

    /**
     * How bright one cell is at time [t].
     *
     * @param col 0 at the left edge.
     * @param jitter that cell's own draw from a fixed shuffle, 0..1. Only used on the way
     *   out: the field fills in a clean wave and comes apart unevenly, which is what makes
     *   it look like it is falling rather than fading.
     */
    fun brightness(col: Int, t: Float, jitter: Float): Float {
        // The wave runs out from the right edge, which is the side the score sits on.
        val away = (COLS - 1 - col).toFloat() / COLS
        val front = (t - away * SWEEP_MILLIS) / RISE_MILLIS
        if (front <= 0f) return 0f
        val b = if (t < HOLD_UNTIL_MILLIS) {
            minOf(1f, front)
        } else {
            1f - (t - HOLD_UNTIL_MILLIS) / FALL_MILLIS - jitter * 0.55f
        }
        return (b * PEAK).coerceIn(0f, PEAK)
    }
}

/**
 * Rings of dots out from the score that just changed.
 *
 * The quietest of the four, and the only one whose position means something: it starts at
 * the figure that moved, so on a screen showing two numbers it says which of them is the
 * news.
 */
object Halftone {

    const val RINGS = 3

    /** How far apart the rings leave. */
    const val STAGGER_MILLIS = 200f

    /** How long one ring takes to travel its whole distance. */
    const val TRAVEL_MILLIS = 1100f

    /** Where a ring starts and ends, as a fraction of the drawing's height. */
    const val RADIUS_START = 0.03f
    const val RADIUS_END = 0.47f

    /** The rings are drawn slightly flat, which reads better under a score than a true circle. */
    const val FLATTEN = 0.88f

    /** How far through its travel ring [ring] is at [t], or null when it has not left or is done. */
    fun progress(ring: Int, t: Float): Float? {
        val rt = (t - ring * STAGGER_MILLIS) / TRAVEL_MILLIS
        return if (rt < 0f || rt > 1f) null else rt
    }

    /** A ring's radius as a fraction of the drawing's height. */
    fun radius(progress: Float): Float =
        RADIUS_START + progress * (RADIUS_END - RADIUS_START)

    /**
     * How many dots are on a ring of this radius, in pixels.
     *
     * Grows with the circumference so the gap between dots stays roughly constant. That is
     * the halftone part: the ring thins as it widens because each dot keeps its size while
     * there is more ring to go round, not because anything fades.
     */
    fun dots(radiusPx: Float): Int = maxOf(12, (radiusPx * 0.42f).toInt())

    /** Squared falloff, so a ring is bright for most of its travel and then goes quickly. */
    fun fade(progress: Float): Float = (1f - progress) * (1f - progress)
}
