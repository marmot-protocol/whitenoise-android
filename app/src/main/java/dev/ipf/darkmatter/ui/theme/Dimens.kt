package dev.ipf.darkmatter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Spacing scale on a 4dp grid. Layout gaps and paddings should pull from here
 * rather than inlining `.dp` literals, so vertical/horizontal rhythm stays
 * consistent across screens.
 *
 * Off-grid literals still scattered through the chat/media surfaces
 * (10/14/18/22.dp) should migrate to the nearest token as those screens are
 * polished — done per-screen so each can be eyeballed on-device.
 */
object Dimens {
    val spaceXxs = 2.dp
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    val spaceXl = 24.dp
    val spaceXxl = 32.dp
}

/**
 * Corner-radius tokens. Consolidates the nine ad-hoc radii previously in use
 * down to four steps plus a pill. `md` (12dp) is the default for cards/inputs;
 * `pill` is for fully-rounded chips and badges.
 */
object Radii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

val PillShape = RoundedCornerShape(percent = 50)
