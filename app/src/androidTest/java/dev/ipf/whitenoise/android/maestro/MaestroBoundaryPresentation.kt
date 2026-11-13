package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsTrustWarningDialog
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.ChatLinkAccountChoice
import dev.ipf.whitenoise.android.ui.ChatLinkAccountChooserDialog
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMembersReviewDialog
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.conversation.media.AudioTranscriptDialog
import dev.ipf.whitenoise.android.ui.conversation.media.AudioTranscriptPhase
import dev.ipf.whitenoise.android.ui.conversation.media.AudioTranscriptUi
import dev.ipf.whitenoise.android.ui.navigation.TtsReturnTransitionScreen
import dev.ipf.whitenoise.android.ui.settings.TtsCustomRateDialog

/** Boundary actions qualify visible dispatch/cancellation, not hardware or network completion. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroBoundaryPresentation(fixture: MaestroPresentationFixture) {
    when (fixture.scenario) {
        "audio-transcript-cancel" ->
            AudioTranscriptDialog(
                state = AudioTranscriptUi(AudioTranscriptPhase.CONFIRM),
                onStart = { error("Cancel fixture must not request transcription") },
                onDismiss = { fixture.finish("dismiss") },
                onGetProvider = { error("Confirmation must not request provider listing") },
            )
        "tts-trust-cancel", "tts-trust-proceed" ->
            TtsTrustWarningDialog(
                onProceed = { fixture.finish("proceed") },
                onDismiss = { fixture.finish("dismiss") },
            )
        "tts-custom-cancel", "tts-custom-apply" ->
            TtsCustomRateDialog(
                initialRate = 1.1f,
                onDismiss = { fixture.finish("dismiss") },
                onRateSelected = {
                    check(it == 1.1f)
                    fixture.finish("rate-applied")
                },
            )
        "tts-return" -> TtsReturnTransitionScreen(requestId = 1)
        "group-large-cancel", "group-large-continue" ->
            GroupMembersReviewDialog(
                appState = fixture.appState,
                groups = listOf(MemberReviewGroup("large-group", "Large group")),
                members = listOf(RecipientSearch.Candidate("b".repeat(64), "Alice", "")),
                busy = false,
                onDismiss = { fixture.finish("dismiss") },
                onConfirm = { fixture.finish("continue") },
                confirmLabel = "Add member",
                description = stringResource(R.string.large_group_invite_confirm_message),
            )
        "chat-link-choose-cancel", "chat-link-choose-select" ->
            ChatLinkAccountChooserDialog(
                choice = ChatLinkAccountChoice("0123456789abcdef0123456789abcdef", listOf("work", "personal")),
                accountLabel = { mapOf("work" to "Work", "personal" to "Personal").getValue(it) },
                onDismiss = { fixture.finish("dismiss") },
                onSelect = {
                    check(it == "personal")
                    fixture.finish("select")
                },
            )
        else -> error("Unknown boundary presentation")
    }
}
