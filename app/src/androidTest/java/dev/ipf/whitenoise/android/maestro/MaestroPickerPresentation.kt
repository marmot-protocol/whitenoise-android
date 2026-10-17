package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactPickerScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectedMembersReviewScreen
import dev.ipf.whitenoise.android.ui.common.AnimatedProfileAvatarOverlay
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.filled.ArrowForward
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer

/** Actual picker chrome uses a generated native account identity and verifies selection dispatch. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroPickerPresentation(fixture: MaestroPresentationFixture) {
    // Native summaries are ordered independently of fixture creation order.
    val account = fixture.appState.accounts.single { it.accountIdHex == fixture.peerAccountIdHex }
    val candidate =
        RecipientSearch.Candidate(
            account.accountIdHex,
            "Maestro Bob",
            fixture.appState.npubForDisplay(account.accountIdHex),
        )
    when {
        fixture.scenario.startsWith("surface-contact-") -> ContactPresentation(fixture, candidate)
        fixture.scenario.startsWith("surface-review-") -> ReviewPresentation(fixture, candidate)
        fixture.scenario == "surface-animated-avatar" -> AnimatedAvatarPresentation(fixture)
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

/** Row overlays remain static; animation needs actual viewer intent and its production decoder. */
@Composable
@Suppress("FunctionNaming")
private fun AnimatedAvatarPresentation(fixture: MaestroPresentationFixture) {
    val url = "https://fixture.example.invalid/animated"
    var viewing by remember(fixture) { mutableStateOf(false) }
    if (viewing) {
        AvatarFullScreenViewer(
            title = "Fixture animated avatar",
            seed = fixture.peerAccountIdHex,
            picture = fixture.image,
            onDismiss = { fixture.finish("dismiss") },
            readLocalBytes = { checkNotNull(AvatarImageLoader.peekAnimatedSource(url)) },
        )
    } else {
        Column {
            AnimatedProfileAvatarOverlay(
                url = url,
                firstFrame = fixture.image,
                modifier = Modifier.size(96.dp).testTag("avatar.static.overlay"),
            )
            Button(
                onClick = {
                    fixture.record("open-viewer")
                    viewing = true
                },
            ) { Text("Open animated avatar") }
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
    val reviewRoles = fixture.scenario == "surface-review-confirm"
    var initialAdmin by remember(fixture) { mutableStateOf(false) }
    SelectedMembersReviewScreen(
        members = listOf(candidate),
        appState = fixture.appState,
        busy = false,
        onBack = { fixture.finish("dismiss") },
        onRemove = {
            check(it == candidate)
            fixture.finish("member-removed")
        },
        onConfirm = {
            check(!reviewRoles || initialAdmin)
            fixture.finish("review-confirmed")
        },
        confirmIcon = Icons.AutoMirrored.Filled.ArrowForward,
        confirmLabel = "Fixture confirm",
        initialAdminIds = if (initialAdmin) setOf(candidate.accountIdHex) else emptySet(),
        onInitialAdminChange =
            if (reviewRoles) {
                { member, admin ->
                    check(member == candidate)
                    initialAdmin = admin
                }
            } else {
                null
            },
    )
}
