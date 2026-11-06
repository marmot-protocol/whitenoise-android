@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationCapsuleClearance

/**
 * The transcript's bottom-end navigation controls — Resume read-aloud follow, the mention chip and
 * Jump to newest. They sit 12dp above the composer and, when another chat's dictation capsule floats
 * over this composer, 12dp above that capsule too, so both sets stay readable and tappable (#3584).
 */
@Composable
internal fun BoxScope.ConversationNavigationStack(
    composerOverlap: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = composerOverlap)
                .padding(12.dp)
                .floatingDictationCapsuleClearance(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
