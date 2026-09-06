package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.ui.common.ErrorContent

private val SeededTailAlignmentRecoveryError =
    ErrorPresentation(
        message = AppText.Resource(R.string.error_loaded_content_kept),
        report =
            "Operation: CONVERSATION_SEEDED_TAIL_ALIGNMENT\n" +
                "The bounded initial tail alignment did not reach a safe reveal state.",
    )

/** Shows a retryable, identity-free recovery surface while a misaligned transcript remains hidden. */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationSeededTailAlignmentRecovery(
    visible: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(modifier = modifier.fillMaxSize().testTag(CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG)) {
        ErrorContent(
            title = stringResource(R.string.couldnt_load_conversation),
            error = SeededTailAlignmentRecoveryError,
            onRetry = onRetry,
            copyActionColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal const val CONVERSATION_SEEDED_TAIL_RECOVERY_TEST_TAG = "conversation.seeded_tail_recovery"
