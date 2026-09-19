package com.gios.lightsports.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.gios.lightsports.R
import com.gios.lightsports.anim.Buzz
import com.gios.lightsports.anim.Fireworks
import com.gios.lightsports.anim.Grid
import com.gios.lightsports.anim.Halftone
import com.gios.lightsports.anim.Mortar
import com.gios.lightsports.model.Celebration
import com.gios.lightsports.notify.Buzzer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the screen does when a team you follow scores.
 *
 * One full-size layer that draws nothing until it is fired, so it costs a composition and
 * no frames while it sits there. It takes no pointer input: a celebration is something you
 * watch out of the corner of your eye while reading the score under it, never something
 * you have to dismiss.
 *
 * Everything is drawn in white blocks, dithered rather than faded, for the reasons set out
 * in [Fireworks]. The arithmetic lives there; this file is the drawing and the clock.
 *
 * @param style which of the four to run.
 * @param trigger the moment it was fired. Any change to a non-zero value starts a run, so
 *   a second score during the first celebration restarts it rather than stacking a second
 *   layer on top. Zero is idle.
 * @param anchor where on this layer the figure that changed sits, in pixels. [Celebration.HALFTONE]
 *   opens its rings there and [Celebration.BURST] throws the old number apart from there.
 *   The other two ignore it. Null means nothing has told us, and each effect falls back to
 *   the middle of the layer — which is what a preview in settings gets.
 * @param figure the number that has just been replaced, for [Celebration.BURST]. Null skips
 *   the particles and keeps the rest.
 * @param buzz whether to run the matching waveform ([Buzz]) alongside the picture. It is
 *   the same event told twice, so it starts on the same frame, not before it.
 */
@Composable
fun CelebrationOverlay(
    style: Celebration,
    trigger: Long,
    anchor: Offset?,
    figure: String?,
    buzz: Boolean = true,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    // Held as a state the draw phase reads, never as a value the composable body reads.
    // Sixty recompositions a second for a number that only moves a rectangle is the
    // difference between this being free and this being the reason the screen stutters.
    val elapsed = remember { mutableLongStateOf(IDLE) }
    val done by rememberUpdatedState(onDone)

    val context = LocalContext.current
    val typeface = remember {
        runCatching { ResourcesCompat.getFont(context, R.font.barlow_condensed_bold) }
            .getOrNull() ?: Typeface.DEFAULT_BOLD
    }

    // Re-drawn per run rather than held for the life of the screen: a display that opens
    // in exactly the same places twice stops looking like a display.
    val sparks = remember(trigger) { Sparks(Random(trigger)) }
    val jitter = remember(trigger) {
        val r = Random(trigger + 1)
        FloatArray(Grid.COLS * Grid.ROWS) { r.nextFloat() }
    }
    val particles = remember(trigger, figure) { figure?.let { Digits.sample(it, typeface) } }

    LaunchedEffect(trigger, style) {
        if (trigger == 0L) return@LaunchedEffect
        val span = Fireworks.durationMillis(style)
        val start = withFrameMillis { it }
        // Started on the first frame rather than before it, so the motor and the picture
        // begin together. A buzz that leads the animation by a frame reads as a phone
        // event with a picture after it, which is the wrong way round.
        if (buzz) runCatching { Buzzer.play(context, Buzz.pattern(style), span) }
        var now = start
        while (now - start <= span) {
            elapsed.longValue = now - start
            now = withFrameMillis { it }
        }
        elapsed.longValue = IDLE
        done()
    }

    Spacer(
        modifier.fillMaxSize().drawBehind {
            val t = elapsed.longValue
            if (t < 0L) return@drawBehind
            when (style) {
                Celebration.MORTAR -> drawMortar(t, sparks)
                Celebration.GRID -> drawGrid(t, jitter)
                Celebration.BURST -> drawBurst(t, centre(anchor), particles, figure, typeface)
                Celebration.HALFTONE -> drawHalftone(t, centre(anchor))
            }
        },
    )
}

private const val IDLE = -1L

// ---------------------------------------------------------------------------- the sparks

/**
 * One display's worth of sparks, as four flat arrays.
 *
 * Flat arrays rather than a list of objects because this is walked a hundred and forty
 * times a frame for two and a third seconds, and a per-frame allocation in that loop is a
 * garbage collection in the middle of the celebration.
 */
private class Sparks(random: Random) {
    val shell = IntArray(Mortar.TOTAL_SPARKS)
    val vx = FloatArray(Mortar.TOTAL_SPARKS)
    val vy = FloatArray(Mortar.TOTAL_SPARKS)
    val life = FloatArray(Mortar.TOTAL_SPARKS)

    init {
        var i = 0
        for ((s, sh) in Mortar.SHELLS.withIndex()) {
            for (k in 0 until sh.sparks) {
                // Evenly around the circle, then nudged, so the ring is a ring and not a
                // clock face. A burst of exactly regular spokes reads as a diagram.
                val a = (k.toFloat() / sh.sparks) * TWO_PI + s + random.nextFloat() * 0.12f
                val speed = Mortar.SPEED_MIN + random.nextFloat() * Mortar.SPEED_SPAN
                shell[i] = s
                vx[i] = cos(a) * speed
                vy[i] = sin(a) * speed
                life[i] = Mortar.LIFE_MIN + random.nextFloat() * Mortar.LIFE_SPAN
                i++
            }
        }
    }
}

private const val TWO_PI = 6.2831855f

// ------------------------------------------------------------------------------ drawing

/** One dithered block. The only thing any of these four ever puts on the screen. */
private fun DrawScope.block(x: Float, y: Float, radius: Int, brightness: Float) {
    if (brightness <= 0f) return
    val px = x.toInt()
    val py = y.toInt()
    // Cheaper than clipping, and it keeps a spark that has left the screen from costing a
    // draw call for the rest of its life.
    if (px < -8 || py < -8 || px > size.width + 8 || py > size.height + 8) return
    if (!Fireworks.lit(px, py, brightness)) return
    val d = (radius * 2).toFloat()
    drawRect(
        color = Color.White,
        topLeft = Offset(px - radius.toFloat(), py - radius.toFloat()),
        size = Size(d, d),
    )
}

private fun DrawScope.drawMortar(t: Long, sparks: Sparks) {
    val h = size.height
    val w = size.width
    for ((index, shell) in Mortar.SHELLS.withIndex()) {
        val since = t - shell.fireAtMillis
        if (since < 0L) continue
        val x = w * shell.x
        val peak = h * shell.peak
        if (since < Mortar.RISE_MILLIS) {
            // The climb: one block and a short tail. Anything more and the matte turns it
            // into a smear going up the screen.
            val y = h - (h - peak) * Mortar.riseProgress(since)
            for (tail in 0 until Mortar.TAIL_LENGTH) {
                block(x, y + tail * h * Mortar.TAIL_GAP, 2, 1f - tail * 0.22f)
            }
            continue
        }
        val seconds = (since - Mortar.RISE_MILLIS) / 1000f
        for (i in sparks.shell.indices) {
            if (sparks.shell[i] != index) continue
            val age = (since - Mortar.RISE_MILLIS) / sparks.life[i]
            if (age > 1f) continue
            block(
                x + sparks.vx[i] * seconds * h,
                peak + sparks.vy[i] * seconds * h + Mortar.GRAVITY * seconds * seconds * h,
                Mortar.sparkRadius(age),
                Mortar.sparkBrightness(age),
            )
        }
    }
}

private fun DrawScope.drawGrid(t: Long, jitter: FloatArray) {
    val cw = size.width / Grid.COLS
    val ch = size.height / Grid.ROWS
    val ms = t.toFloat()
    for (col in 0 until Grid.COLS) {
        // The wave is a function of the column alone, so a whole column that has not been
        // reached yet is one test rather than thirty-one.
        val b = Grid.brightness(col, ms, 0f)
        if (b <= 0f && ms < Grid.HOLD_UNTIL_MILLIS) continue
        for (row in 0 until Grid.ROWS) {
            val cell = Grid.brightness(col, ms, jitter[row * Grid.COLS + col])
            if (cell <= 0f || !Fireworks.lit(col, row, cell)) continue
            drawRect(
                color = Color.White,
                topLeft = Offset(col * cw, row * ch),
                size = Size(cw, ch),
            )
        }
    }
}

/** Where an effect opens when nothing has told it: the middle of the layer, a little high. */
private fun DrawScope.centre(anchor: Offset?): Offset =
    anchor ?: Offset(size.width / 2f, size.height * 0.42f)

private fun DrawScope.drawHalftone(t: Long, centre: Offset) {
    val h = size.height
    val ms = t.toFloat()
    for (ring in 0 until Halftone.RINGS) {
        val progress = Halftone.progress(ring, ms) ?: continue
        val radius = Halftone.radius(progress) * h
        val dots = Halftone.dots(radius)
        val fade = Halftone.fade(progress)
        val radiusStep = if (progress < 0.4f) 2 else 1
        for (i in 0 until dots) {
            val a = (i.toFloat() / dots) * TWO_PI + ring * 0.4f + progress * 0.5f
            block(
                centre.x + cos(a) * radius,
                centre.y + sin(a) * radius * Halftone.FLATTEN,
                radiusStep,
                fade,
            )
        }
    }
}

private fun DrawScope.drawBurst(
    t: Long,
    anchor: Offset,
    particles: FloatArray?,
    figure: String?,
    typeface: Typeface,
) {
    if (t < BURST_HOLD) return
    val h = size.height
    val seconds = (t - BURST_HOLD) / 1000f
    if (particles != null && seconds <= 1f) {
        val scale = h * Digits.SCALE
        var i = 0
        while (i < particles.size) {
            val ox = particles[i] * scale
            val oy = particles[i + 1] * scale
            // Thrown outward from the middle of the figure rather than in one direction:
            // the number comes apart, it does not get pushed over.
            val len = maxOf(0.08f, kotlin.math.hypot(particles[i], particles[i + 1]))
            val vx = particles[i] / len * BURST_SPEED
            val vy = particles[i + 1] / len * BURST_SPEED - BURST_LIFT
            block(
                anchor.x + ox + vx * seconds * h,
                anchor.y + oy + vy * seconds * h + BURST_GRAVITY * seconds * seconds * h,
                2,
                1f - seconds,
            )
            i += 2
        }
    }
    // The new figure landing in the slot the old one left. Not the score itself -- the
    // screen underneath is already drawing that, correctly, and this is a copy on its way
    // down to meet it. It stops at 1.0 exactly where the real one sits, so the two line up
    // as this one goes away.
    if (figure != null && t < BURST_LAND) {
        val k = (t - BURST_HOLD).toFloat() / (BURST_LAND - BURST_HOLD)
        val inv = 1f - k
        val scale = 1f + 1.4f * inv * inv * inv
        val paint = Digits.paint(typeface, h * Digits.SCALE * Digits.GLYPH)
        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.save()
        canvas.translate(anchor.x, anchor.y)
        canvas.scale(scale, scale)
        // Centred on the anchor: the paint is centre-aligned across, and this lifts the
        // baseline by the same fraction of the cell the sample was drawn at.
        canvas.drawText(figure, 0f, paint.textSize * Digits.BASELINE, paint)
        canvas.restoreToCount(save)
    }
}

/** The beat between the score changing and the old figure letting go. */
private const val BURST_HOLD = 90L

/** When the incoming figure has finished landing. */
private const val BURST_LAND = 520L

private const val BURST_SPEED = 0.16f
private const val BURST_LIFT = 0.05f
private const val BURST_GRAVITY = 0.24f

// ------------------------------------------------------------------------- the old figure

/**
 * The old score, sampled into points so it can be thrown apart.
 *
 * Drawn once into a small bitmap with the app's own condensed face and read back, rather
 * than approximated with rectangles: the thing that blows up has to be the figure that was
 * on the screen a moment ago, in the same typeface, or the effect reads as a different
 * object appearing where the number was.
 */
private object Digits {

    /** The sampled figure's cell height, as a fraction of the drawing's. */
    const val SCALE = 0.11f

    /** The glyph inside that cell. Matches the 0.8 the sample is drawn at. */
    const val GLYPH = 0.8f

    /** Where the baseline sits inside the glyph's own box, so the two agree. */
    const val BASELINE = 0.35f

    /** How coarsely the figure is sampled, in bitmap pixels. */
    private const val STEP = 4

    /** The bitmap the figure is rendered into before it is read back. */
    private const val CELL = 96

    fun paint(typeface: Typeface, sizePx: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = sizePx
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    /**
     * Points on the figure, as x,y pairs in units of the figure's own height, centred on
     * zero. Empty when the platform will not give us a bitmap, which costs the particles
     * and leaves the rest of the effect intact.
     */
    fun sample(figure: String, typeface: Typeface): FloatArray = runCatching {
        val bitmap = Bitmap.createBitmap(CELL, CELL, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = paint(typeface, CELL * 0.8f)
        canvas.drawText(figure, CELL / 2f, CELL * 0.78f, paint)
        val pixels = IntArray(CELL * CELL)
        bitmap.getPixels(pixels, 0, CELL, 0, 0, CELL, CELL)
        bitmap.recycle()
        val out = ArrayList<Float>(256)
        var y = 0
        while (y < CELL) {
            var x = 0
            while (x < CELL) {
                // The alpha channel, not the colour: the glyph is white on transparent.
                if (pixels[y * CELL + x] ushr 24 > 120) {
                    out.add((x - CELL / 2f) / CELL)
                    out.add((y - CELL * 0.5f) / CELL)
                }
                x += STEP
            }
            y += STEP
        }
        out.toFloatArray()
    }.getOrDefault(FloatArray(0))
}
