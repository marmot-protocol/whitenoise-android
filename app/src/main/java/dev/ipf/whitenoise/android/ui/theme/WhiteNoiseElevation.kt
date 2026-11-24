package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * One elevation scale for shadows and tonal tints (#4101), on Material 3's six levels. Feature code names
 * the kind of surface — [FloatingControl], [Menu], [Dialog] — rather than a number, so a surface lifts the
 * same distance wherever it appears. `ElevationTokenCoverageTest` rejects a literal outside this file.
 *
 * Shadow or tint: a surface that floats over moving content (floating controls, FABs, menus, the kept card)
 * casts a shadow, and a surface that sits in the layout (bottom bars, panels, dialogs) takes a tint.
 */
object WhiteNoiseElevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
    val Level4 = 8.dp
    val Level5 = 12.dp

    /** Flat: a surface that lies on its parent. */
    val None = Level0

    /** A raised surface inside content, such as the QR frame or a status card. */
    val Raised = Level1

    /** The profile editor's avatar, lifted off its banner by a shadow. */
    val ProfileAvatar = Level2

    /** Bottom bars, panels and docked action rows, as a tint. */
    val Docked = Level2

    /** Menus and popups, as a shadow (Material's menu level). */
    val Menu = Level2

    /** Floating controls and FABs: the same at rest, pressed, focused and hovered. */
    val FloatingControl = Level3

    /** Dialogs, as a tint (Material's dialog level). */
    val Dialog = Level3

    /** The kept-on-screen message card, which floats over the transcript like a floating control. */
    val KeptCard = Level3
}

/** A FAB that keeps [WhiteNoiseElevation.FloatingControl] in every state, so it does not jump when pressed. */
@Composable
internal fun floatingControlFabElevation(): FloatingActionButtonElevation =
    FloatingActionButtonDefaults.elevation(
        defaultElevation = WhiteNoiseElevation.FloatingControl,
        pressedElevation = WhiteNoiseElevation.FloatingControl,
        focusedElevation = WhiteNoiseElevation.FloatingControl,
        hoveredElevation = WhiteNoiseElevation.FloatingControl,
    )
