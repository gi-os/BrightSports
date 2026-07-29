package com.gios.lightsports.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import com.gios.lightsports.ui.theme.RuleGrey

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = RuleGrey, thickness = 1.dp)

/** Screen title in the LightOS idiom: all caps, letterspaced, no chrome around it. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
        )
        Rule()
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
@Composable
fun MenuRow(
    label: String,
    detail: String? = null,
    sub: String? = null,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
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

/**
 * Bottom tab bar in the LightOS action-bar idiom. The active tab is bracketed as
 * well as brightened, because the panel is greyscale and matte — a colour or a
 * faint tint would not read.
 */
@Composable
fun TabBar(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Column {
        Rule()
        Row(
            Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (active) "[ $label ]" else label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) Color.White else Faint,
                    )
                }
            }
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
