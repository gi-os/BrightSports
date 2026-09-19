package com.gios.lightsports.anim

import com.gios.lightsports.model.Celebration

/**
 * What each celebration feels like, as a waveform. Pure arithmetic, no Android.
 *
 * The rule is that the buzz is the *same event* as the picture, not an accompaniment to
 * it. A shell that opens at 260 ms cracks at 260 ms. The grid's wave swells as the field
 * fills and snaps as it lets go. A hand in a pocket should be able to tell which style is
 * running without looking, which is a higher bar than "it vibrates" and the only one worth
 * aiming at — on a phone whose whole argument is that you are not looking at it.
 *
 * Two things shaped every pattern below.
 *
 * **The sparks are silent.** Mortar buzzes three times, for the three cracks, and then
 * says nothing for a second and a half while the sparks fall. A motor running under a
 * picture is a phone malfunctioning; a motor that stops is a firework.
 *
 * **Nothing here runs long.** The longest is Mortar at 1.1 s of a 2.3 s animation, and
 * most of that is silence between beats. Sustained vibration is the thing people go into
 * settings to turn off.
 */
object Buzz {

    /**
     * One beat: when it starts, how long the motor runs, and how hard.
     *
     * Absolute times against the animation's own clock, because that is how they were
     * chosen — beside the frame they belong to. [pattern] folds them into the alternating
     * off/on array the platform actually wants.
     */
    class Beat(val atMillis: Long, val durationMillis: Long, val amplitude: Int) {
        val endsAtMillis: Long get() = atMillis + durationMillis
    }

    /**
     * A waveform as `Vibrator` takes it: strictly alternating silence and motor, starting
     * with silence, so it works whether or not the phone can vary amplitude.
     *
     * With amplitude control, [timings] and [amplitudes] go in together. Without it, the
     * same [timings] alone are already correct — `createWaveform(timings, -1)` alternates
     * off and on starting with off, which is exactly what the alternation guarantees. The
     * pattern degrades to the right rhythm at one strength rather than to nothing.
     */
    class Pattern(val timings: LongArray, val amplitudes: IntArray) {
        val totalMillis: Long get() = timings.sum()
        val beats: Int get() = amplitudes.count { it > 0 }
    }

    /** The strongest any celebration pulls. Not 255: this is good news, not an alarm. */
    const val PEAK = 235

    fun beats(style: Celebration): List<Beat> = when (style) {
        // Three cracks. The launch is a small thump under the thumb, the burst is the
        // crack, and only the last one gets a tail -- the other two have another shell
        // going up on top of them.
        Celebration.MORTAR -> listOf(
            Beat(0L, 10L, 70),
            Beat(260L, 35L, PEAK),
            Beat(340L, 10L, 70),
            Beat(600L, 35L, PEAK),
            Beat(640L, 10L, 70),
            Beat(900L, 45L, PEAK),
            Beat(945L, 160L, 80),
        )

        // A swell, a short hold, a release. Stepped rather than ramped because a waveform
        // holds one amplitude per segment, and four steps across 260 ms reads as a ramp.
        Celebration.GRID -> listOf(
            // Four steps with gaps between them rather than four back to back: the gaps
            // are what keep the total motor time down, and at this length the hand reads
            // the run as one rising thing anyway.
            Beat(0L, 45L, 50),
            Beat(50L, 45L, 110),
            Beat(105L, 55L, 175),
            Beat(170L, 70L, PEAK),
            // Under the field while it stands. Low, and short: this is the one beat that
            // could turn into a motor droning under a picture.
            Beat(280L, 80L, 85),
            // The field letting go, at the frame it starts to fall.
            Beat(520L, 55L, 200),
            Beat(575L, 45L, 70),
        )

        // The figure letting go, then landing. Two events, and the gap between them is the
        // point: it is the same beat the animation has.
        Celebration.BURST -> listOf(
            Beat(90L, 45L, PEAK),
            Beat(135L, 80L, 70),
            Beat(500L, 35L, 180),
            Beat(535L, 40L, 55),
        )

        // One tap per ring, fading. The quietest picture gets the quietest hand.
        Celebration.HALFTONE -> listOf(
            Beat(0L, 18L, 150),
            Beat(200L, 18L, 105),
            Beat(400L, 18L, 70),
        )
    }

    /**
     * The beats folded into an alternating waveform.
     *
     * Beats are assumed sorted and non-overlapping; [beats] keeps them that way and a test
     * holds it there, because an overlap here would silently swallow a beat rather than
     * throw, and a missing crack is not something a compiler can see.
     */
    fun pattern(style: Celebration): Pattern {
        val list = beats(style)
        // A leading silence of zero is still a segment: the platform's array has to start
        // with an off period whether or not there is one.
        val timings = ArrayList<Long>(list.size * 2 + 1)
        val amplitudes = ArrayList<Int>(list.size * 2 + 1)
        var at = 0L
        for (beat in list) {
            timings.add(beat.atMillis - at)
            amplitudes.add(0)
            timings.add(beat.durationMillis)
            amplitudes.add(beat.amplitude.coerceIn(1, 255))
            at = beat.endsAtMillis
        }
        return Pattern(timings.toLongArray(), amplitudes.toIntArray())
    }
}
