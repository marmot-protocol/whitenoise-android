package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactPickerScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectedMembersReviewScreen
import dev.ipf.whitenoise.android.ui.common.AnimatedProfileAvatarOverlay
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu

/** Actual picker chrome uses a generated native account identity and verifies selection dispatch. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroPickerPresentation(fixture: MaestroPresentationFixture) {
    val account = fixture.appState.accounts[1]
    val candidate =
        RecipientSearch.Candidate(
            account.accountIdHex,
            "Maestro Bob",
            fixture.appState.npubForDisplay(account.accountIdHex),
        )
    when {
        fixture.scenario.startsWith("surface-contact-") -> ContactPresentation(fixture, candidate)
        fixture.scenario.startsWith("surface-review-") -> ReviewPresentation(fixture, candidate)
        fixture.scenario == "surface-animated-avatar" ->
            AnimatedProfileAvatarOverlay(
                url = "https://fixture.example.invalid/animated",
                firstFrame = fixture.image,
                modifier = Modifier.size(96.dp),
            )
        else ->
            Box(Modifier.size(48.dp)) {
                KeyboardPreservingDropdownMenu(
                    expanded = true,
                    onDismissRequest = { fixture.finish("dismiss") },
                ) {
                    DropdownMenuItem(
                        text = { Text("Fixture menu action") },
                        onClick = { fixture.finish("menu-selected") },
                    )
                }
            }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ContactPresentation(
    fixture: MaestroPresentationFixture,
    candidate: RecipientSearch.Candidate,
) {
    val selected =
        remember(fixture) {
            mutableStateListOf<RecipientSearch.Candidate>().apply {
                if (fixture.scenario.endsWith("selected")) add(candidate)
            }
        }
    ContactPickerScreen(
        appState = fixture.appState,
        title = "Fixture contacts",
        selected = selected,
        onBack = { fixture.finish("dismiss") },
        onConfirm = {
            check(selected.toList() == listOf(candidate))
            fixture.finish("contacts-confirmed")
        },
        confirmLabel = "Fixture next",
    )
}

@Composable
@Suppress("FunctionNaming")
private fun ReviewPresentation(
    fixture: MaestroPresentationFixture,
    candidate: RecipientSearch.Candidate,
) {
    SelectedMembersReviewScreen(
        members = listOf(candidate),
        appState = fixture.appState,
        busy = false,
        onBack = { fixture.finish("dismiss") },
        onRemove = {
            check(it == candidate)
            fixture.finish("member-removed")
        },
        onConfirm = { fixture.finish("review-confirmed") },
        confirmIcon = Icons.AutoMirrored.Filled.ArrowForward,
        confirmLabel = "Fixture confirm",
    )
}
