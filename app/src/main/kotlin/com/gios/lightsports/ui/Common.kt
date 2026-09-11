package com.gios.lightsports.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.RuleGrey

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = RuleGrey, thickness = 1.dp)

/**
 * Holds the panel awake while [active].
 *
 * A live game screen re-fetches every fifteen seconds, and the only version of that worth
 * having is one the screen does not sleep through. Scoped to the flag rather than to the
 * screen itself, so a final, settled game — open for reading, not updating — still lets
 * the panel sleep as normal.
 */
@Composable
fun KeepAwake(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Dim,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Dim,
            textAlign = TextAlign.Center,
        )
    }
}

/** Full-width tappable row: title on the left, optional figure on the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MenuRow(
    label: String,
    detail: String? = null,
    sub: String? = null,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                when {
                    // combinedClickable only when a long press is actually wanted: it
                    // adds a press-and-hold delay to the ordinary tap otherwise.
                    onLongClick != null -> it.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                    )
                    onClick != null -> it.clickable(onClick = onClick)
                    else -> it
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dim) Dim else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}

/** Selection inverts rather than tints; on a matte greyscale panel nothing else reads. */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) Color.White else Color.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/**
 * The rule under the top bar, with a white band running across it while something is loading.
 *
 * A refresh on this phone is a network round trip over a slow radio, and the only sign it had
 * started was `REFRESHING…` in the smallest, faintest type on the screen. A tap with no visible
 * consequence reads as a tap that missed, which is how a person ends up pressing it four times.
 *
 * A band rather than a spinner: the rule is already there, it is one pixel of the design, and a
 * line sweeping along it says "working" without adding furniture to a screen that has none. It
 * takes exactly the same room when nothing is happening.
 */
@Composable
fun ProgressRule(loading: Boolean, modifier: Modifier = Modifier) {
    // Two pixels tall either way, drawn rather than divided, so the bar does not step down a
    // pixel the moment a refresh starts. Idle is the ordinary hairline with a pixel of black
    // under it; working fills both with the band.
    if (!loading) {
        Canvas(modifier.fillMaxWidth().height(RULE_SPACE)) {
            drawRect(RuleGrey, size = Size(size.width, size.height / 2f))
        }
        return
    }
    val sweep = rememberInfiniteTransition(label = "sweep")
    val at by sweep.animateFloat(
        initialValue = -SWEEP_WIDTH,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "at",
    )
    Canvas(modifier.fillMaxWidth().height(RULE_SPACE)) {
        drawRect(RuleGrey, size = size)
        val start = (at * size.width).coerceAtLeast(0f)
        val end = ((at + SWEEP_WIDTH) * size.width).coerceAtMost(size.width)
        if (end > start) {
            drawRect(Color.White, topLeft = Offset(start, 0f), size = Size(end - start, size.height))
        }
    }
}

/** How much of the width the band covers. A third reads as motion; a sliver reads as a fault. */
private const val SWEEP_WIDTH = 0.33f

/** The room the rule takes, working or not. */
private val RULE_SPACE = 2.dp

/**
 * White for a moment, then back where it was.
 *
 * A screen that re-fetches itself every few seconds gives no sign of having done it — the score
 * is usually the same score, so the only evidence is a label that says it happens. One flash of
 * the label each time it lands is the difference between a screen you trust and a screen you
 * refresh by hand to be sure. Keyed on [stamp], which is the moment data arrived rather than the
 * data itself: a fetch that changed nothing still happened.
 */
@Composable
fun flashOnUpdate(stamp: Long, resting: Color): Color {
    // A number from one to zero rather than a colour: the flash is a *fade back to where the
    // label already was*, and interpolating the distance is the same animation with no opinion
    // about what the resting colour is.
    val glow = remember { Animatable(0f) }
    LaunchedEffect(stamp) {
        if (stamp == 0L) return@LaunchedEffect
        glow.snapTo(1f)
        glow.animateTo(0f, animationSpec = tween(durationMillis = 700, easing = LinearEasing))
    }
    return lerp(resting, Color.White, glow.value)
}
