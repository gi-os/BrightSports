package com.gios.lightsports.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gios.lightsports.ui.theme.Dim

/**
 * The LightOS bar idiom, rebuilt.
 *
 * These mirror `LightTopBar` and `LightBottomBar` from Light's own SDK UI library
 * (`sdk/ui`) — same grid, same bar heights, same slot rules, same LightOS icon
 * drawables. They are reimplemented rather than imported because the SDK artifacts
 * live on GitHub Packages behind a token, and this app ships as a plain APK. If the
 * SDK's distribution ever opens up, these are the components to delete.
 */

/** LP3 grid, from LightOS's own `src/ui/constants.ts`: 27 columns by 31 rows. */
object LightGrid {
    const val WIDTH = 27
    const val HEIGHT = 31
}

@Composable
fun Float.gridUnits(): Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return (screenWidthDp.toFloat() / LightGrid.WIDTH * this).dp
}

/**
 * LightOS type sizes are quoted against a 600px-tall design canvas, so every size is
 * scaled by the real screen height. On the LPIII's 1240px panel that lands close to
 * 2x the quoted number.
 */
private const val FONT_BASELINE_PX = 600f

@Composable
fun Float.designSp(): TextUnit {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return (this * screenHeightDp / FONT_BASELINE_PX).sp
}

private const val TOPBAR_HEIGHT_UNITS = 3f
private const val BOTTOMBAR_HEIGHT_UNITS = 4f
private const val BAR_ICON_UNITS = 2f

/** The SDK's `Fine` variant: 25px at the design scale, lightly letterspaced. */
@Composable
private fun fineSize() = 25f.designSp()

/** The SDK's `Button` variant: 30px, medium, wide tracking. */
@Composable
private fun buttonSize() = 30f.designSp()

sealed interface BarItem {
    val onClick: (() -> Unit)?

    data class Text(val text: String, override val onClick: (() -> Unit)?) : BarItem

    data class Icon(
        val resId: Int,
        override val onClick: (() -> Unit)?,
        val contentDescription: String? = null,
        /** Dimmed rather than hidden: it is still a target, just not the current one. */
        val selected: Boolean = true,
    ) : BarItem
}

/**
 * Top bar: 3 grid units tall, one button per side, title centred over both. The title
 * sits in its own full-width box so it stays optically centred no matter how wide the
 * side buttons are.
 */
@Composable
fun LightTopBar(
    left: BarItem? = null,
    title: String? = null,
    right: BarItem? = null,
) {
    val barHeight = TOPBAR_HEIGHT_UNITS.gridUnits()
    val pad = 1f.gridUnits()
    Box(Modifier.fillMaxWidth().height(barHeight).padding(horizontal = pad)) {
        Row(
            Modifier.fillMaxWidth().height(barHeight).zIndex(2f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.height(barHeight), Alignment.CenterStart) { BarItemView(left, barHeight) }
            Box(Modifier.weight(1f))
            Box(Modifier.height(barHeight), Alignment.CenterEnd) { BarItemView(right, barHeight) }
        }
        if (title != null) {
            Box(Modifier.fillMaxWidth().height(barHeight), Alignment.Center) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = fineSize(),
                        letterSpacing = fineSize() * 0.03f,
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Bottom action bar: 4 grid units tall, up to five icons — or at most three items once
 * any of them is text, which is the SDK's own limit and a sensible one at this width.
 * Two items pin to the edges, three take start/centre/end, four or more space evenly.
 */
@Composable
fun LightBottomBar(items: List<BarItem?>) {
    require(items.size <= 5) { "LightBottomBar supports at most 5 items" }
    require(items.none { it is BarItem.Text } || items.size <= 3) {
        "LightBottomBar with text supports at most 3 items"
    }

    val barHeight = BOTTOMBAR_HEIGHT_UNITS.gridUnits()
    val pad = if (items.size <= 1) 0.dp else 2f.gridUnits()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 1f.gridUnits())
            .height(barHeight)
            .padding(horizontal = pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (items.size) {
            0 -> Unit
            1 -> Box(Modifier.fillMaxWidth(), Alignment.Center) { BarItemView(items[0], barHeight) }
            2 -> {
                Slot(Alignment.CenterStart) { BarItemView(items[0], barHeight) }
                Slot(Alignment.CenterEnd) { BarItemView(items[1], barHeight) }
            }
            3 -> {
                Slot(Alignment.CenterStart) { BarItemView(items[0], barHeight) }
                Slot(Alignment.Center) { BarItemView(items[1], barHeight) }
                Slot(Alignment.CenterEnd) { BarItemView(items[2], barHeight) }
            }
            else -> Row(
                Modifier.fillMaxWidth(),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                items.forEach { BarItemView(it, barHeight) }
            }
        }
    }
}

@Composable
private fun RowScope.Slot(align: Alignment, content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), align) { content() }
}

@Composable
private fun BarItemView(item: BarItem?, barHeight: Dp) {
    val iconSize = BAR_ICON_UNITS.gridUnits()
    when (item) {
        null -> Box(Modifier.size(iconSize))
        is BarItem.Text -> Box(
            Modifier
                .height(barHeight)
                .let { m -> item.onClick?.let { m.clickable(onClick = it) } ?: m }
                .padding(horizontal = 4.dp),
            Alignment.Center,
        ) {
            Text(
                item.text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = buttonSize(),
                    letterSpacing = buttonSize() * 0.15f,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        is BarItem.Icon -> Image(
            painter = painterResource(item.resId),
            contentDescription = item.contentDescription,
            contentScale = ContentScale.Fit,
            // The SDK has no selected state for a bar icon. On a matte greyscale panel
            // the readable substitute is luminance, so the inactive ones go grey.
            colorFilter = ColorFilter.tint(if (item.selected) Color.White else Dim),
            modifier = Modifier
                .size(iconSize)
                .let { m -> item.onClick?.let { m.clickable(onClick = it) } ?: m },
        )
    }
}
