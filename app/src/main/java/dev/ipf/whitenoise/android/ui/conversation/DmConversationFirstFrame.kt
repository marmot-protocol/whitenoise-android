package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.diagnostics.DmCreationDiagnostics

/** A destination-owned frame effect consumes only its captured ticket after rendering, never a later owner's attempt. */
@Composable
internal fun RecordDmConversationFirstFrame(
    accountRef: String?,
    groupId: String,
    runtimeGeneration: Int,
    currentRuntimeGeneration: () -> Int,
) {
    val currentGeneration by rememberUpdatedState(currentRuntimeGeneration)
    val attempt =
        remember(accountRef, groupId, runtimeGeneration) {
            accountRef?.let { DmCreationDiagnostics.pendingFrame(it, groupId, runtimeGeneration) }
        }
    LaunchedEffect(accountRef, groupId, runtimeGeneration, attempt) {
        val capturedAttempt = attempt ?: return@LaunchedEffect
        val account = accountRef ?: return@LaunchedEffect
        withFrameNanos { }
        if (currentGeneration() == runtimeGeneration) {
            DmCreationDiagnostics.firstFrame(account, groupId, runtimeGeneration, capturedAttempt)
        }
    }
}
