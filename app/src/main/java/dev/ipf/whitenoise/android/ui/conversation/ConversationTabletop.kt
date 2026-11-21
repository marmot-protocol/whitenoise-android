@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalWindowFolds
import dev.ipf.whitenoise.android.ui.common.WindowFold
import dev.ipf.whitenoise.android.ui.settings.LocalSettingsCardMorph
import dev.ipf.whitenoise.android.ui.settings.SettingsCardMorph

/**
 * What the conversation's bottom bar reserves in the tabletop posture: everything from the top of
 * the hinge down, so the messages end at the hinge, with the hinge's own gap left empty above the
 * composer. Both are zero when the window has no tabletop fold.
 */
internal data class ConversationTabletop(
    val minHeight: Dp,
    val hingeGap: Dp,
)

internal fun conversationTabletop(
    tabletop: WindowFold?,
    windowHeight: Dp,
): ConversationTabletop =
    if (tabletop == null) {
        ConversationTabletop(0.dp, 0.dp)
    } else {
        ConversationTabletop(
            minHeight = (windowHeight - tabletop.start).coerceAtLeast(0.dp),
            hingeGap = (tabletop.end - tabletop.start).coerceAtLeast(0.dp),
        )
    }

/**
 * Holds the conversation's bottom bar.
 *
 * Held half open like a laptop, a flip phone or a foldable on its side is in the tabletop posture.
 * The bottom bar then fills the half below the fold with the composer at its top: the messages are
 * read on the upright half and typed on the flat half, where the keyboard opens. The same [Box]
 * holds the bottom bar in every posture, so folding keeps the composer's draft and focus.
 */
@Composable
internal fun ConversationBottomBarFrame(
    routeMorph: SettingsCardMorph?,
    content: @Composable () -> Unit,
) {
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val reserve = conversationTabletop(LocalWindowFolds.current.tabletop, windowHeight)
    CompositionLocalProvider(LocalSettingsCardMorph provides routeMorph) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = reserve.minHeight).padding(top = reserve.hingeGap),
            contentAlignment = Alignment.TopCenter,
        ) {
            content()
        }
    }
}
