package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.whitenoise.android.ui.DictationDisclosureDialog
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFab

/** Root disclosures and the actual missing-relay FAB route, without starting recognition or editing relays. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroRootModalPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("modal-chats-relays-")) {
        MissingRelayRoutePresentation(fixture)
        return
    }
    var showing by remember { mutableStateOf(true) }
    if (showing) {
        DictationDisclosureDialog(
            offlineSpeechToText = fixture.scenario.endsWith("offline"),
            onConfirm = { fixture.finish("disclosure-confirm") },
            onDismiss = {
                fixture.record("disclosure-dismiss")
                showing = false
            },
        )
    } else {
        Button(onClick = { showing = true }) { Text("Reopen dictation disclosure") }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun MissingRelayRoutePresentation(fixture: MaestroPresentationFixture) {
    val account = fixture.appState.activeAccountRef
    val missing =
        remember {
            AccountRelayListsFfi(
                complete = false,
                missing = listOf(MissingRelayListKindFfi.INBOX),
                defaultRelays = emptyList(),
                bootstrapRelays = emptyList(),
                nip65 = RelayListFfi(10_002uL, emptyList()),
                inbox = RelayListFfi(10_050uL, emptyList()),
            )
        }
    Box(Modifier.fillMaxSize()) {
        ChatsNewMessageFab(
            appState = fixture.appState,
            onNewMessage = { error("Known missing relays must open the production recovery dialog") },
            loadRelays = {
                check(it == account)
                missing
            },
        )
        Button(onClick = { fixture.finish("done") }, modifier = Modifier.align(Alignment.TopStart)) {
            Text("Finish relay route check")
        }
    }
}
