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
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.LaunchedEffect
import com.gios.lightsports.data.Prefs
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.notify.AlertOwner
import com.gios.lightsports.notify.Health
import com.gios.lightsports.ui.theme.Dim

private val DELAY_CHOICES = listOf(0, 2, 5, 10, 15, 30)

@Composable
fun SettingsScreen(
    prefs: Prefs,
    vm: SportsViewModel,
    version: String,
    followCount: Int,
    mutedCount: Int,
    onOpenTeams: () -> Unit,
) {
    var notify by remember { mutableStateOf(prefs.notificationsEnabled) }
    var starts by remember { mutableStateOf(prefs.notifyStarts) }
    var alertBox by remember { mutableStateOf(prefs.alertBoxEnabled) }
    val alertsOwned = AlertOwner.ownedElsewhere(LocalContext.current)
    var live by remember { mutableStateOf(prefs.liveUpdatesEnabled) }
    var delayOn by remember { mutableStateOf(prefs.delayEnabled) }
    var delay by remember { mutableIntStateOf(prefs.delayMinutes) }
    val context = LocalContext.current
    // Re-read on every visit rather than remembered for the life of the screen: the
    // battery-optimisation row sends the user out to a system dialog and back, and a
    // cached answer would still say "not exempt" after they had just granted it.
    var health by remember { mutableStateOf(Health.summary(context)) }
    var holdback by remember { mutableStateOf(Health.advice(context)) }
    var dozeExempt by remember { mutableStateOf(Health.dozeExempt(context)) }
    LaunchedEffect(Unit) {
        health = Health.summary(context)
        holdback = Health.advice(context)
        dozeExempt = Health.dozeExempt(context)
    }
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        SectionHeader("TEAMS")
        MenuRow(
            label = "My teams",
            detail = if (followCount > 0) "$followCount" else "none",
            sub = if (mutedCount > 0) "$mutedCount silenced — in the feed, no alerts"
            else "Add or drop the teams in your feed",
            onClick = onOpenTeams,
        )
        Rule()

        SectionHeader("NOTIFICATIONS")
        MenuRow(
            label = "Score alerts",
            detail = if (notify) "[ ON ]" else "OFF",
            sub = "Goals, runs and touchdowns, plus halftime, quarters and periods. " +
                "Basketball only at the quarter, baseball never by the inning",
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
        MenuRow(
            label = "On-screen alert",
            // A third state, and it is not this app's to set. Saying ON while nothing appeared
            // would be a toggle that lies — and the setting really is still on, which is why this
            // is said out loud rather than quietly flipped.
            detail = when {
                alertsOwned -> "CONTROL"
                alertBox -> "[ ON ]"
                else -> "OFF"
            },
            sub = if (alertsOwned) {
                "BrightControl puts the box up for every app now, so this one stands aside. " +
                    "Turn banners off there to bring this one back. The buzz and the " +
                    "notification are unchanged either way"
            } else {
                "The box over the screen when a score lands. Off keeps the buzz " +
                    "and the notification, but nothing appears over what you're doing"
            },
            onClick = {
                alertBox = !alertBox
                vm.setAlertBoxEnabled(alertBox)
            },
        )
        Rule()
        MenuRow(
            label = "Live updates",
            detail = if (live) "[ ON ]" else "OFF",
            sub = "While a followed team is playing, check every 30–60 seconds instead " +
                "of every nine minutes. A quiet card sits in the shade for as long as " +
                "the game does, and goes when it ends",
            onClick = {
                live = !live
                vm.setLiveUpdatesEnabled(live)
            },
        )
        Rule()

        SectionHeader("DELIVERY")
        MenuRow(
            label = "How scores arrive",
            sub = "$health\n$holdback",
            onClick = {
                health = Health.summary(context)
                holdback = Health.advice(context)
                dozeExempt = Health.dozeExempt(context)
            },
        )
        Rule()
        MenuRow(
            label = "Battery optimisation",
            detail = if (dozeExempt) "EXEMPT" else "ON",
            sub = if (dozeExempt) {
                "The phone is not holding this app in Doze, so checks land on time " +
                    "whether or not the live card is up"
            } else {
                "Tap to ask the phone to stop putting this app to sleep. If nothing " +
                    "opens, LightOS has no screen for it — grant it over adb with " +
                    "dumpsys deviceidle whitelist +com.gios.lightsports"
            },
            onClick = {
                // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS first: it is the one that
                // grants in a single tap. LightOS ships no Settings app on some builds,
                // so neither may resolve, and the row already says what to do then.
                val direct = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(direct) }
                    .recoverCatching { context.startActivity(list) }
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
            "Between games the app runs on an alarm, the only thing that fires while " +
                "the phone is asleep, and the system holds those to roughly a nine " +
                "minute floor. Live updates step around that for the couple of hours a " +
                "game lasts; with it off, the nine minutes apply all the time. Taking " +
                "the app out of battery optimisation removes the floor itself.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(16.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}
