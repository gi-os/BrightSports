package com.gios.lightsports.hw

import android.webkit.WebView
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.sign

/**
 * Wheel notches on their way from the activity to whatever is on screen.
 *
 * One notch per event, positive for up. The activity is the only thing that can see the
 * key — a `dispatchKeyEvent` override is what lets it win against a focused WebView — but
 * only the current screen knows what scrolling means, so the two are joined by a flow
 * rather than by the activity reaching into the UI.
 *
 * A [SharedFlow] with no replay, deliberately: a notch that arrives while nothing is
 * listening is gone, which is what you want. Buffered generously because the sensor emits
 * bursts far faster than a frame.
 */
class WheelBus {
    private val _notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val notches: SharedFlow<Int> = _notches.asSharedFlow()

    fun send(notches: Int) {
        _notches.tryEmit(notches)
    }
}

val LocalWheelBus = staticCompositionLocalOf<WheelBus?> { null }

/**
 * Distance per notch. About six notches to a screenful on the LPIII panel — enough that a
 * flick of the wheel moves you somewhere, short enough that you can land on a paragraph.
 */
private val NOTCH = 64.dp

/**
 * Which way a notch moves the page.
 *
 * `1` means turning the wheel up moves you *down* the document — the wheel drags the page
 * the way a finger flick does, rather than moving a viewport over it. Flip to `-1` for the
 * mouse-wheel convention.
 */
private const val DIRECTION = 1

/**
 * Fraction of the remaining distance applied per frame.
 *
 * This is the whole reason scrolling feels like scrolling rather than like a slide
 * projector. The sensor fires a notch every ~35 ms, which is faster than a frame, so
 * applying each one on arrival produces a stack of instant jumps — nothing to follow with
 * your eye. Instead every notch adds to a debt, and each frame pays off a share of it, so
 * one notch glides and a fast spin becomes a single continuous sweep that keeps moving
 * slightly after your thumb stops.
 *
 * 0.28 settles ~90% inside seven frames: quick enough to feel direct, slow enough to read.
 */
private const val SMOOTHING = 0.28f

/**
 * Notches needed to start scrolling, and how long a turn stays live.
 *
 * The wheel sits under a thumb and catches stray brushes, and one stray notch used to be a
 * scroll. So the first notch after a pause buys nothing on its own: it is remembered, and
 * only a second notch releases both. Once turning, everything applies immediately until
 * [IDLE_MS] passes with the wheel still, at which point the guard comes back.
 *
 * 1.5 s is deliberately long. It has to cover deliberate-but-slow turning, and the cost of
 * it being too long is nil — you are turning the wheel, so the next notch re-arms it.
 */
private const val ARM_NOTCHES = 2
private const val IDLE_MS = 1_500L

/**
 * Point the wheel at a Compose scroller. Works for both `ScrollState` and `LazyListState`.
 */
@Composable
fun WheelScroll(state: ScrollableState, active: Boolean = true) {
    val step = with(LocalDensity.current) { NOTCH.toPx() }
    val debt = remember { Debt() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }

    ArmedNotches(active) { notches ->
        debt.px += notches * step * DIRECTION
        wake.trySend(Unit)
    }

    LaunchedEffect(state, wake) {
        while (true) {
            // Suspends while the wheel is still, so an idle screen costs nothing.
            wake.receive()
            // One scroll session for the whole glide. A finger on the screen takes priority
            // and cancels this, which is the right outcome.
            state.scroll {
                while (abs(debt.px) > 0.5f) {
                    withFrameNanos { }
                    val wanted = (debt.px * SMOOTHING).let {
                        // Never stall a notch out in sub-pixel increments.
                        if (abs(it) < 1f) debt.px else it
                    }
                    debt.px -= wanted
                    val consumed = scrollBy(wanted)
                    // At the top or bottom the rest of the debt is unpayable, and keeping
                    // it would mean the next turn back spends its first notches on nothing.
                    if (abs(consumed) < abs(wanted) - 0.5f) debt.px = 0f
                }
            }
        }
    }
}

/** The same, for the reader's WebView, which Compose knows nothing about. */
@Composable
fun WheelScroll(web: WebView?, active: Boolean = true) {
    val step = with(LocalDensity.current) { NOTCH.toPx() }
    val debt = remember { Debt() }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }

    ArmedNotches(active && web != null) { notches ->
        debt.px += notches * step * DIRECTION
        wake.trySend(Unit)
    }

    LaunchedEffect(web, wake) {
        val target = web ?: return@LaunchedEffect
        while (true) {
            wake.receive()
            while (abs(debt.px) > 0.5f) {
                withFrameNanos { }
                val wanted = (debt.px * SMOOTHING).let {
                    if (abs(it) < 1f) debt.px else it
                }
                debt.px -= wanted
                if (!target.wheelScrollBy(wanted.toInt())) debt.px = 0f
            }
        }
    }
}

/**
 * Notches, minus the stray ones. See [ARM_NOTCHES].
 *
 * Armed state lives in the effect rather than in composition state: it is a property of the
 * turn in progress, and a recomposition mid-turn should not disarm the wheel.
 */
@Composable
private fun ArmedNotches(active: Boolean, onNotch: (Int) -> Unit) {
    val handler by rememberUpdatedState(onNotch)
    val bus = LocalWheelBus.current ?: return
    LaunchedEffect(bus, active) {
        if (!active) return@LaunchedEffect
        var armed = false
        var held = 0
        var count = 0
        var last = 0L
        bus.notches.collect { notches ->
            val now = System.nanoTime() / 1_000_000
            if (now - last > IDLE_MS) {
                armed = false
                held = 0
                count = 0
            }
            last = now
            if (armed) {
                handler(notches)
                return@collect
            }
            held += notches
            count++
            if (count >= ARM_NOTCHES) {
                armed = true
                // Release what the guard was holding, so nothing deliberate is lost.
                if (held != 0) handler(held) else handler(notches.sign)
                held = 0
            }
        }
    }
}

/**
 * Scrolling the document, bounded at both ends. Returns false at an edge, so the caller can
 * drop the rest of the debt instead of pushing against it.
 *
 * `canScrollVertically` is the public way to ask — `computeVerticalScrollRange` is protected
 * on View, and the content height is only available in CSS pixels that would have to be
 * scaled back by hand.
 */
private fun WebView.wheelScrollBy(px: Int): Boolean {
    if (px == 0) return true
    if (!canScrollVertically(if (px > 0) 1 else -1)) return false
    scrollBy(0, px)
    return true
}

/**
 * Distance still owed to the scroller.
 *
 * Deliberately not Compose state: nothing in composition reads it, and making it observable
 * would restart the glide on every recomposition it caused.
 */
private class Debt {
    @Volatile
    var px: Float = 0f
}
