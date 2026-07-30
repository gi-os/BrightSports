package com.gios.lightsports.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gios.lightsports.model.League
import com.gios.lightsports.model.StandingsRow
import com.gios.lightsports.ui.theme.Dim

/**
 * Everything the standings feed knows about one team, reached by holding its row.
 *
 * The table can only show four or five columns at this width, but the providers send
 * fifteen to twenty stats per team — run differential, streaks, home and away splits,
 * a driver's points at every round. This is where the rest of it goes, and it costs no
 * extra request.
 */
@Composable
fun TeamStatsScreen(row: StandingsRow, league: League?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            row.name,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Text(
            listOfNotNull(league?.short, row.abbrev.takeIf { it.isNotEmpty() })
                .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        )
        Rule()

        if (row.allStats.isEmpty()) {
            EmptyState("No extra stats published for ${row.name}.")
            return@Column
        }

        SectionHeader("SEASON")
        for ((label, value) in row.allStats) {
            MenuRow(label = label, detail = value)
            Rule()
        }
        Spacer(Modifier.height(32.dp))
    }
}
