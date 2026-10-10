package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupPhotoMenu
import dev.ipf.whitenoise.android.ui.conversation.ConversationInitialLoadingOverlay
import dev.ipf.whitenoise.android.ui.conversation.TtsTransportRatePicker
import dev.ipf.whitenoise.android.ui.conversation.share.ContactPreviewScreen
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.group.GroupDisbandConfirmDialog
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadPickerDialog
import dev.ipf.whitenoise.android.ui.navigation.QuickAccountSwitchMotion
import dev.ipf.whitenoise.android.ui.navigation.QuickAccountSwitchTransition
import dev.ipf.whitenoise.android.ui.navigation.QuickAccountSwitchTransitionOverlay
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingSavedAccountUi
import dev.ipf.whitenoise.android.ui.onboarding.RetainedProfilesSheet

/** Production pickers verify selected callback payloads; platform acquisition remains a separate boundary. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroSelectionPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("selection-contact-") -> ContactSelection(fixture)
        fixture.scenario.startsWith("selection-retained-") -> RetainedSelection(fixture)
        fixture.scenario.startsWith("selection-autoread-") ->
            TtsAutoReadPickerDialog(
                globalDefaultEnabled = true,
                selectedOverride = TtsAutoReadOverride.OFF,
                onDismiss = { fixture.finish("dismiss") },
                onSelect = {
                    val expected =
                        when (fixture.scenario) {
                            "selection-autoread-on" -> TtsAutoReadOverride.ON
                            "selection-autoread-off" -> TtsAutoReadOverride.OFF
                            else -> null
                        }
                    check(it == expected)
                    fixture.record("override-selected")
                },
            )
        fixture.scenario.startsWith("selection-disband-") ->
            GroupDisbandConfirmDialog(
                onConfirm = { fixture.finish("confirm") },
                onDismiss = { fixture.finish("dismiss") },
            )
        fixture.scenario.startsWith("selection-photo-") -> PhotoSelection(fixture)
        fixture.scenario == "selection-transport-rate" ->
            TtsTransportRatePicker(rateOverride = 1f, activeRate = 1f, onRateSelected = {
                check(it == null)
                fixture.finish("default-selected")
            })
        fixture.scenario == "selection-conversation-loading" -> ConversationInitialLoadingOverlay(visible = true)
        fixture.scenario == "selection-quick-cue" ->
            QuickAccountSwitchTransitionOverlay(
                transition =
                    QuickAccountSwitchTransition(
                        requestId = 1,
                        sourceAccountRef = "synthetic-source",
                        targetAccountRef = "synthetic-target",
                        targetTitle = "Fixture Target",
                        targetSeed = "synthetic-target",
                        targetPictureUrl = null,
                        motion = QuickAccountSwitchMotion.Animated,
                    ),
                visible = true,
                onFinished = { error("Awaiting-target cue cannot complete activation") },
            )
        else -> error("Unknown selection presentation")
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ContactSelection(fixture: MaestroPresentationFixture) {
    val empty = fixture.scenario == "selection-contact-empty"
    val contact =
        SharedContact(
            "Fixture Contact",
            if (empty) null else "+15550100",
            if (empty) null else "fixture@example.invalid",
        )
    ContactPreviewScreen(contact = contact, onDismiss = { fixture.finish("dismiss") }, onSend = {
        val expected =
            when (fixture.scenario) {
                "selection-contact-phone" -> contact.copy(email = null)
                "selection-contact-email" -> contact.copy(phone = null)
                else -> contact
            }
        check(it == expected && !empty)
        fixture.finish("contact-selected")
    })
}

@Composable
@Suppress("FunctionNaming")
private fun RetainedSelection(fixture: MaestroPresentationFixture) {
    val accounts =
        listOf(
            OnboardingSavedAccountUi("one", "11".repeat(32), "Fixture One", "fixture-one", null),
            OnboardingSavedAccountUi("two", "22".repeat(32), "Fixture Two", "fixture-two", null),
        )
    RetainedProfilesSheet(
        accounts = accounts,
        enabled = fixture.scenario != "selection-retained-disabled",
        onSelect = {
            check(it == accounts[1] && fixture.scenario == "selection-retained-pick")
            fixture.finish("profile-selected")
        },
        onDismiss = { fixture.finish("dismiss") },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun PhotoSelection(fixture: MaestroPresentationFixture) {
    // Match the production trigger's bounded anchor rather than an entire-screen menu anchor.
    Box(Modifier.size(48.dp)) {
        NewGroupPhotoMenu(
            expanded = true,
            hasImage = true,
            onDismiss = { fixture.record("menu-dismissed") },
            onPhotos = { fixture.finish("photos") },
            onFiles = { fixture.finish("files") },
            onWeb = { fixture.finish("web") },
            onEmoji = { fixture.finish("emoji") },
            onRemove = { fixture.finish("remove") },
        )
    }
}
