package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.composerSendingAccount

/** Actual composer and popover with a different native signing account than the active owner. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroSendingIdentityPresentation(fixture: MaestroPresentationFixture) {
    val state = fixture.appState
    val active = remember { state.activeAccountRef }
    val peerId = fixture.peerAccountIdHex
    val owner = remember(peerId) { state.accounts.first { it.accountIdHex == peerId && it.isSignedInSigningAccount() } }
    check(owner.label != active && composerSendingAccount(state, owner.label) == owner)
    Column {
        ComposerBar(
            replyingTo = null,
            messageTextCopy = MessageTextCopy.Default,
            onCancelReply = {},
            onSend = { _, _ -> error("Identity disclosure must not send") },
            initialDraft = TextFieldValue("Retained identity draft"),
            draftAccountRef = owner.label,
            appState = state,
            onPickFromGallery = {},
        )
        Button(
            onClick = {
                check(state.activeAccountRef == active && composerSendingAccount(state, owner.label) == owner)
                fixture.finish("identity-checked")
            },
            modifier = Modifier.testTag("identity-fixture-done"),
        ) { Text("Finish identity check") }
    }
}
