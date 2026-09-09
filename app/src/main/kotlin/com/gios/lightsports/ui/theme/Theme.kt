package com.gios.lightsports.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gios.lightsports.R

/** LightOS renders greyscale on a matte panel, so the palette is luminance only. */
private val MonoDark = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A), onSurfaceVariant = Color(0xFFBBBBBB),
)

val Dim = Color(0xFF9A9A9A)
val Faint = Color(0xFF5E5E5E)
val RuleGrey = Color(0xFF262626)
/** A step below white: body text that is not the headline. */
val Soft = Color(0xFFBBBBBB)
/** The filled part of the field graphic. */
val Field = Color(0xFF1A1A1A)
val FieldLine = Color(0xFF3A3A3A)

/**
 * Barlow Condensed, bundled (SIL Open Font License). The one place the app leaves the
 * system face: team marks and scores are set in a condensed display face so "SEA 24"
 * reads across the room, the way a scoreboard does. Everything else stays Akkurat.
 */
val Condensed: FontFamily = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
)

/**
 * The scoreboard styles. Sizes come from the 2.0 mock, drawn at 540 px for a 1080 px
 * panel; the panel is ~420 dpi, so a mock pixel is 0.76 sp.
 */
object Marks {
    /** "SEA" beside the crest in a feed row. */
    val team = TextStyle(
        fontFamily = Condensed, fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp, lineHeight = 28.sp, letterSpacing = 0.8.sp,
    )
    /** The score at the end of a feed row. */
    val score = TextStyle(
        fontFamily = Condensed, fontWeight = FontWeight.Bold,
        fontSize = 29.sp, lineHeight = 30.sp,
    )
    /** The two marks on a game screen. */
    val teamLarge = TextStyle(
        fontFamily = Condensed, fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = 0.8.sp,
    )
    /** The game screen's score. */
    val big = TextStyle(
        fontFamily = Condensed, fontWeight = FontWeight.Bold,
        fontSize = 72.sp, lineHeight = 68.sp,
    )
    /** The dash between the two big scores. */
    val bigDash = TextStyle(
        fontFamily = Condensed, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 68.sp,
    )
    /** The field graphic's end-zone labels and other tiny caps. */
    val tiny = TextStyle(
        fontFamily = Condensed, fontWeight = FontWeight.ExtraBold,
        fontSize = 10.sp, lineHeight = 10.sp, letterSpacing = 0.5.sp,
    )
    /** "TD", "RED ZONE" — the kind label on an alert box. */
    val kind = TextStyle(
        fontFamily = Condensed, fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = 1.sp,
    )
}

@Composable
fun LightSportsTheme(content: @Composable () -> Unit) {
    val fam = remember { akkuratFamilyOrDefault() }
    val type = Typography(
        displaySmall = TextStyle(fontFamily = fam, fontSize = 40.sp, fontWeight = FontWeight.Light),
        titleLarge = TextStyle(fontFamily = fam, fontSize = 26.sp, fontWeight = FontWeight.Light),
        titleMedium = TextStyle(fontFamily = fam, fontSize = 21.sp, fontWeight = FontWeight.Normal),
        bodyLarge = TextStyle(fontFamily = fam, fontSize = 18.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontFamily = fam, fontSize = 15.sp, fontWeight = FontWeight.Normal),
        // Scores are the one place a tabular figure matters: a two-digit score must
        // not shove the team name sideways as the game goes on.
        headlineMedium = TextStyle(fontFamily = fam, fontSize = 30.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(
            fontFamily = fam, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 2.4.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = fam, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
        ),
    )
    MaterialTheme(colorScheme = MonoDark, typography = type, content = content)
}
