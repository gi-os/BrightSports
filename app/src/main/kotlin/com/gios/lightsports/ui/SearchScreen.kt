package com.gios.lightsports.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.data.Feed
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.model.Game
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import java.time.ZoneId

/**
 * Look up any team or league, followed or not, and see its games in the same window
 * the feed uses: live first, then by day. Typing a league ("NFL", "Premier") gives its
 * whole slate; typing a club gives that club's games wherever it plays.
 */
@Composable
fun SearchScreen(
    state: SportsViewModel.SearchState,
    logos: Map<String, String>,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onGame: (Game) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "FIND",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                modifier = Modifier.padding(end = 12.dp),
            )
            BasicTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (state.query.isEmpty()) {
                        Text(
                            "Team, player or league",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
        }
        Rule()

        val typed = state.query.trim().length >= 2
        when {
            !typed -> EmptyState(
                "Type a team or a league.\n\nEvery league the app knows, not only the ones you follow.",
            )
            state.loading && state.resultsFor != state.query -> EmptyState("Looking…")
            state.sections.isEmpty() -> EmptyState(
                if (state.resultsFor == state.query) "Nothing in the next ten days for \"${state.query.trim()}\"."
                else "Looking…",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                for (section in state.sections) {
                    item(key = "h-${section.title}") { SectionHeader(section.title) }
                    for (item in section.items) {
                        if (item is Feed.Item.GameItem) {
                            item(key = "g-${item.game.leagueId}-${item.game.id}") {
                                GameRow(item.game, zone, logos) { onGame(item.game) }
                                Rule()
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}
