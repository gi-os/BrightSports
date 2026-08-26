package com.gios.lightsports.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gios.lightsports.data.Prefs

/**
 * Whether BrightControl is drawing the on-screen box for every app, so this one should not.
 *
 * BrightControl v3.65 grew a banner of its own: it reads the shade through a notification listener
 * and puts the same box over the screen for whatever posted. It is drawn off the very notification
 * [Notifier] raises a moment earlier — so with both apps switched on, a goal was one buzz and
 * **two boxes**, one landing on top of the other.
 *
 * One of them has to stand down and it should be this one. BrightControl knows about every app on
 * the phone; this app knows about scores, and the box it draws is the box BrightControl would have
 * drawn anyway.
 *
 * ### What does not change
 *
 * The buzz and the shade notification. Both happen before the gate in [ScoreAlert.show], and both
 * must keep happening: the notification is the record BrightControl reads and the janitor later
 * clears, and if this app went quiet as well as boxless, a phone where BrightControl's own listener
 * grant had lapsed would say nothing at all about a goal.
 */
object AlertOwner {

    /** The package that may claim the box. Only BrightControl; nothing else is asked. */
    private const val CONTROL = "com.gios.lightcontrol"

    /**
     * Whether to leave the box to BrightControl.
     *
     * Two tests, and the second is not paranoia. A remembered yes from an app that has since been
     * uninstalled would silence this app's box permanently, with nothing on the phone to explain
     * why — so the claim is only honoured while the claimant is still installed. Removing
     * BrightControl gives this app its box back on the next score, with no setting to find.
     */
    fun ownedElsewhere(context: Context): Boolean {
        if (!Prefs(context).alertsOwnedElsewhere) return false
        return runCatching {
            context.packageManager.getPackageInfo(CONTROL, 0)
            true
        }.getOrDefault(false)
    }
}

/**
 * Hears BrightControl say who is drawing the box.
 *
 * A broadcast rather than this app asking, because asking would be a binder call on the path a
 * score arrives on. The cost of the other direction is staleness, which BrightControl pays for by
 * sending it often and unprompted: on its every launch, the moment its listener grant lands, and at
 * boot. So a missed broadcast corrects itself rather than sticking.
 *
 * Nothing verifies the sender, and nothing needs to. The worst a forged one can do is stop this app
 * drawing its own box — the buzz and the notification are never gated on it — and a signature
 * permission would prove nothing anyway, since these apps' signing key is public.
 */
class AlertOwnerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Prefs(context).alertsOwnedElsewhere = intent.getBooleanExtra(EXTRA_OWNED, false)
    }

    companion object {
        const val ACTION = "com.gios.lightcontrol.action.ALERTS_OWNED"
        const val EXTRA_OWNED = "owned"
    }
}
