package dev.ipf.whitenoise.android.ui.group

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.contactNicknameAccountRefForAccess
import dev.ipf.whitenoise.android.ui.chats.newchat.SettingsActionRow
import dev.ipf.whitenoise.android.ui.profile.ContactPrivateDetailsDialog
import dev.ipf.whitenoise.android.ui.profile.profileSheetContactPrivateDetailsRowValue

internal fun directDetailsContactEditorTarget(
    isDm: Boolean,
    readOnlyInvite: Boolean,
    dmPeerAccountIdHex: String?,
    dmPeerNpub: String?,
    activeAccountRef: String?,
    accounts: List<AccountSummaryFfi>,
    editorAccountRef: String? = activeAccountRef,
): String? {
    if (!isDm || readOnlyInvite || editorAccountRef != activeAccountRef) return null
    val peer = dmPeerAccountIdHex?.trim()?.takeIf { it.isNotEmpty() }
    return peer?.takeIf {
        !dmPeerNpub.isNullOrBlank() &&
            contactNicknameAccountRefForAccess(activeAccountRef, accounts, it) != null
    }
}

@Composable
internal fun DirectDetailsContactEditorRow(
    appState: WhiteNoiseAppState,
    groupIdHex: String,
    peerAccountIdHex: String?,
    isDm: Boolean,
    readOnlyInvite: Boolean,
    dmPeerNpub: String?,
    activeAccountRef: String?,
    accounts: List<AccountSummaryFfi>,
) {
    val peer =
        peerAccountIdHex?.let {
            directDetailsContactEditorTarget(
                isDm = isDm,
                readOnlyInvite = readOnlyInvite,
                dmPeerAccountIdHex = it,
                dmPeerNpub = dmPeerNpub,
                activeAccountRef = activeAccountRef,
                accounts = accounts,
            )
        } ?: return
    val contactNickname = appState.contactNickname(peer)
    val contactNotes = appState.contactNotes(peer)
    var showContactEditorDialog by remember(activeAccountRef, groupIdHex, peer) { mutableStateOf(false) }
    SettingsActionRow(
        icon = Icons.Default.Edit,
        title =
            stringResource(
                if (contactNickname == null) {
                    R.string.profile_add_nickname_and_notes
                } else {
                    R.string.profile_nickname_and_notes
                },
            ),
        value =
            profileSheetContactPrivateDetailsRowValue(
                contactNickname = contactNickname,
                contactNotes = contactNotes,
                addNicknameAndNotesLabel = stringResource(R.string.profile_add_nickname_and_notes),
                notesLabel = stringResource(R.string.profile_contact_notes_hint),
            ),
        onClick = { showContactEditorDialog = true },
    )
    if (showContactEditorDialog) {
        ContactPrivateDetailsDialog(
            profileName = appState.networkDisplayName(peer),
            initialNickname = contactNickname.orEmpty(),
            initialNotes = contactNotes.orEmpty(),
            onDismiss = { showContactEditorDialog = false },
            onSave = { nickname, notes ->
                val savePeer =
                    directDetailsContactEditorTarget(
                        isDm = isDm,
                        readOnlyInvite = readOnlyInvite,
                        dmPeerAccountIdHex = peer,
                        dmPeerNpub = dmPeerNpub,
                        activeAccountRef = appState.activeAccountRef,
                        accounts = appState.accounts,
                        editorAccountRef = activeAccountRef,
                    ) ?: return@ContactPrivateDetailsDialog
                if (!savePeer.equals(peer, ignoreCase = true)) return@ContactPrivateDetailsDialog
                appState.setContactNickname(savePeer, nickname)
                appState.setContactNotes(savePeer, notes)
                showContactEditorDialog = false
            },
        )
    }
}
