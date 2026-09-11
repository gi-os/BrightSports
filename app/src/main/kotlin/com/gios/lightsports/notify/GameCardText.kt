package com.gios.lightsports.notify

/**
 * One game card, taken apart into the pieces the design draws.
 *
 * The shade gets [title] and [body] — a plain notification, the way every launcher and every
 * other phone expects one. BrightControl's lock face and banner get the rest, and lay it out
 * the way the app's own box does: what happened on the left in the scoreboard face, the score
 * on the right, the play under it, the clock at the foot.
 *
 *     TD  (SEA)  SEA                       NE 7 · SEA 14
 *     K. Walker 12 yd run · Myers kick good
 *     Q2 3:24
 *
 * Everything but [title] is optional. A card with no [kind] is a live game rather than an
 * event, and reads `NE @ SEA` on the left with the score still on the right.
 */
data class GameCardText(
    /** The shade's one-line title. Unchanged from what the app has always posted. */
    val title: String,
    /** The shade's second line. */
    val body: String? = null,
    /**
     * The left-hand headline, in the scoreboard face. For an event it is what happened —
     * `TD`, `FG`, `RED ZONE`, `ONE-SCORE GAME`, `FINAL`, `HALFTIME`. For a game simply in
     * progress it is the matchup, `NE @ SEA`, which is the same shape and reads the same way.
     */
    val kind: String? = null,
    /** Who it happened to, beside the kind: `SEA`. Null when the card is not about one side. */
    val team: String? = null,
    /** The right-hand figure: the score, or the team in the red zone. */
    val value: String? = null,
    /** The line under the headline: the play, the down and distance, who leads. */
    val detail: String? = null,
    /** The small line at the foot: the period and the clock. */
    val foot: String? = null,
    /** The team whose crest belongs beside [team], when there is one to draw. */
    val crestTeamId: String? = null,
)
