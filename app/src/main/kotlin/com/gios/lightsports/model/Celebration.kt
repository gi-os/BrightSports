package com.gios.lightsports.model

/**
 * What the screen does when a team you follow scores.
 *
 * Stored by name in [com.gios.lightsports.data.Prefs], read back defensively: a renamed
 * constant should land on the default, not crash the screen that draws it.
 */
enum class Celebration(
    /** The settings row's label. */
    val label: String,
    /** The line under it. One sentence, what it actually looks like. */
    val blurb: String,
) {
    /** Three shells up from the bottom edge, bursting in turn. */
    MORTAR(
        "Mortar",
        "Three shells up from the bottom, one after another",
    ),

    /** The layout grid lights up and drops out cell by cell. */
    GRID(
        "Grid",
        "The app's own grid lights up and falls away",
    ),

    /** The old figure blows apart and the new one lands in its place. */
    BURST(
        "Score burst",
        "The old number blows apart, the new one lands",
    ),

    /** Rings of dots out from the score that just changed. */
    HALFTONE(
        "Halftone",
        "Rings of dots out from the number that moved",
    ),
    ;

    companion object {
        val DEFAULT = GRID

        /** By name, falling back to [DEFAULT] rather than throwing on a stored value we lost. */
        fun byName(name: String?): Celebration =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
