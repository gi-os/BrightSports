package com.gios.lightsports.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.RuleGrey

/** How far up you have to drag to throw the box away, in pixels of accumulated travel. */
private const val DISMISS_TRAVEL_PX = 40f

/**
 * The score box: the same shape LightChat uses for an incoming message, carrying a
 * score instead.
 *
 * Solid black with a hairline outline, because it lands on top of pixels we don't own —
 * a borderless black panel over another black app would have no edge at all, and the
 * panel is greyscale so a tint can't supply one.
 */
@Composable
fun ScoreBox(
    title: String,
    text: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Clear of the status-bar strip: the window is FLAG_LAYOUT_NO_LIMITS so it
            // can draw up there, and this padding is what keeps the text out of it.
            .padding(start = 12.dp, end = 12.dp, top = 36.dp, bottom = 12.dp)
            .background(Color.Black)
            .border(1.dp, RuleGrey)
            // Swipe up to dismiss early. Accumulated rather than tested per frame: a
            // single frame's drag is a few pixels, so a per-frame test fires on one
            // jittery frame of a downward drag and never on a slow deliberate one.
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragCancel = { travelled = 0f },
                ) { change, drag ->
                    change.consume()
                    travelled += drag
                    if (travelled < -DISMISS_TRAVEL_PX) onDismiss()
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // The score is the headline, so it gets the larger of the two styles — the
        // opposite weighting to a message, where the sender leads.
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
