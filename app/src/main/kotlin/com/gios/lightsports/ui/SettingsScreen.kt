package com.gios.lightsports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gios.lightsports.data.Prefs
import com.gios.lightsports.ui.theme.Dim

private val DELAY_CHOICES = listOf(0, 2, 5, 10, 15, 30)

@Composable
fun SettingsScreen(prefs: Prefs, vm: SportsViewModel, version: String) {
    var notify by remember { mutableStateOf(prefs.notificationsEnabled) }
    var starts by remember { mutableStateOf(prefs.notifyStarts) }
    var delayOn by remember { mutableStateOf(prefs.delayEnabled) }
    var delay by remember { mutableIntStateOf(prefs.delayMinutes) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("NOTIFICATIONS")
        MenuRow(
            label = "Score alerts",
            detail = if (notify) "[ ON ]" else "OFF",
            sub = "Every goal, run and touchdown; basketball at the end of each quarter",
            onClick = {
                notify = !notify
                vm.setNotificationsEnabled(notify)
            },
        )
        Rule()
        MenuRow(
            label = "Game starting",
            detail = if (starts) "[ ON ]" else "OFF",
            sub = "A nudge when a followed team takes the field",
            onClick = {
                starts = !starts
                vm.setNotifyStarts(starts)
            },
        )
        Rule()

        SectionHeader("SPOILER DELAY")
        MenuRow(
            label = "Hold score alerts",
            detail = if (delayOn) "[ ON ]" else "OFF",
            sub = "Streams run a minute or two behind live",
            onClick = {
                delayOn = !delayOn
                vm.setDelayEnabled(delayOn)
            },
        )
        if (delayOn) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (minutes in DELAY_CHOICES) {
                    Chip(
                        label = if (minutes == 0) "NONE" else "${minutes}M",
                        selected = minutes == delay,
                    ) {
                        delay = minutes
                        vm.setDelayMinutes(minutes)
                    }
                }
            }
            Text(
                "Alerts are held $delay min. Several scores in one window collapse " +
                    "into a single notification carrying the current score.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Rule()

        SectionHeader("ABOUT")
        MenuRow("Version", detail = version)
        MenuRow(
            label = "Data",
            sub = "ESPN for the majors and F1, MLB StatsAPI for the minors, " +
                "HockeyTech for the PWHL",
        )
        Text(
            "Background polling runs on an inexact alarm, the only kind that fires " +
                "while the phone is asleep. Expect roughly a nine minute floor between " +
                "checks when the screen has been off a while.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(16.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}
