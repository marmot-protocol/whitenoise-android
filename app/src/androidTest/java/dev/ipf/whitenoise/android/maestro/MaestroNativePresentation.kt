package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.ui.conversation.AgentOperationDeleteDialog
import dev.ipf.whitenoise.android.ui.conversation.share.LocationPickerScreen

/** Native-owned group/message identity is retained; the UI ports cannot delete, share or publish anything. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroNativePresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("extra-native-viewer-") -> MaestroNativeViewerPresentation(fixture)
        fixture.scenario == "extra-native-location-cancel" ->
            LocationPickerScreen(
                hasFineGrant = false,
                onDismiss = { fixture.finish("dismiss") },
                onPick = { error("Cancellation must not hand off a location") },
                useDataConnection = false,
                locationLookup = { _, _ -> null },
            )
        else -> {
            val chat = checkNotNull(fixture.nativeChat)
            AgentOperationDeleteDialog(
                record = checkNotNull(chat.latest),
                controller = checkNotNull(fixture.nativeController),
                appState = fixture.appState,
                capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
                mine = true,
                senderDisplayName = "Maestro Alice",
                onDismiss = { fixture.finish("dismiss") },
            )
        }
    }
}
