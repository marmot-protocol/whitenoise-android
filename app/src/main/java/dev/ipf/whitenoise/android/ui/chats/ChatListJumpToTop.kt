package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseContentMaxWidth
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlGap
import dev.ipf.whitenoise.android.ui.navigation.FloatingControlVisibility
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationCapsuleClearance

/**
 * Jump-to-top control (#413): the top of the bottom-end floating stack. It rests just above the
 * navigation bar and lifts above the dictation capsule and the pen, centred on the pen's column, so
 * it never overlaps either of them.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListJumpToTopControl(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val paneInset = ((maxWidth - WhiteNoiseContentMaxWidth) / 2).coerceAtLeast(0.dp)
        FloatingControlVisibility(
            visible = visible,
            motionEnabled = rememberSystemAnimationsEnabled(),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = paneInset + JUMP_TO_TOP_END_INSET, bottom = FloatingControlGap)
                    .floatingDictationCapsuleClearance(clearColumnObstacles = true),
        ) {
            ChatListJumpToTopButton(onClick = onClick)
        }
    }
}

/** The chat list's jump-to-top control: a 34dp disc inside a 42dp touch box. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatListJumpToTopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollToTopLabel = stringResource(R.string.scroll_to_top)
    Box(
        modifier =
            modifier
                .size(42.dp)
                .semantics { contentDescription = scrollToTopLabel }
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 2.dp,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.ic_jump_to_edge),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// Centres the 42dp jump control on the 48dp pen, which the Scaffold insets 16dp from the end edge:
// 19 + 42 / 2 == 16 + 48 / 2.
internal val JUMP_TO_TOP_END_INSET = 19.dp
