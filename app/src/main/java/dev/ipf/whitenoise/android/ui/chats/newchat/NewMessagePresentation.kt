package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

/** Display-only projection; the candidate retains native identity and direct-chat provenance. */
internal data class NewMessagePerson(
    val candidate: RecipientSearch.Candidate,
    val subtitle: String?,
    val avatarUrl: String?,
)

/** Groups only established native provenance; radius never implies a following relationship. */
internal fun newMessageSource(candidate: RecipientSearch.Candidate): Int =
    when {
        candidate.source != null -> R.string.new_message_source_chats
        candidate.isFollowing -> R.string.new_message_source_following
        candidate.searchProfile != null || candidate.searchRadius != null -> R.string.new_message_source_network
        else -> R.string.new_message_source_local
    }

/** Screen callbacks stay with the existing flow owner, including QR and platform sharing. */
internal data class NewMessageActions(
    val back: () -> Unit,
    val newGroup: () -> Unit,
    val scanQr: () -> Unit,
    val showMyQr: () -> Unit,
    val invite: () -> Unit,
    val retrySearch: () -> Unit,
    val retryChat: () -> Unit,
    val pasteRejected: () -> Unit,
    val person: (RecipientSearch.Candidate) -> Unit,
    val profile: (RecipientSearch.Candidate) -> Unit,
    val copyError: (String) -> Unit,
)

/** Prototype discovery hierarchy driven exclusively by the caller's current native result state. */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod") // Declarative discovery sections and rows.
internal fun NewMessageContent(
    queryState: TextFieldState,
    people: List<NewMessagePerson>,
    search: RecipientUserSearchState,
    identifierQuery: Boolean,
    resolvingIdentifier: Boolean,
    showMyQrEnabled: Boolean,
    creatingHex: String?,
    error: StartChatErrorUiState?,
    actions: NewMessageActions,
    retryableIdentifier: Boolean = false,
) {
    val query = queryState.text.toString()
    val busy = creatingHex != null
    SettingsScaffold(
        title = stringResource(R.string.new_message),
        onBack = { if (!busy) actions.back() },
        modifier = Modifier.imePadding().testTag("new_message.screen"),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("new_message.list"),
            contentPadding = PaddingValues(bottom = WhiteNoiseSpacing.Section),
        ) {
            item {
                RecipientSearchField(
                    state = queryState,
                    placeholder = stringResource(R.string.new_message_name_or_npub),
                    onPasteRejected = actions.pasteRejected,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("new_message.searchField"),
                    shape = MaterialTheme.shapes.extraLarge,
                    enabled = !busy,
                )
            }
            item { NewMessageActionGroup(actions, showMyQrEnabled, busy) }
            error?.let { item { StartChatErrorCard(it, actions.retryChat, actions.invite, actions.copyError) } }
            item {
                NewMessageSearchFeedback(
                    searching = resolvingIdentifier || (!identifierQuery && search.isSearching),
                    failed = !identifierQuery && search.failed,
                    incomplete = !identifierQuery && search.isIncomplete,
                    empty = query.isNotBlank() && people.isEmpty(),
                    busy = busy,
                    onRetry = actions.retrySearch,
                    onInvite = actions.invite,
                    retryableIdentifier = retryableIdentifier,
                )
            }
            val groups =
                if (query.isBlank() || identifierQuery) {
                    mapOf(R.string.new_message_people to people)
                } else {
                    people.groupBy { newMessageSource(it.candidate) }
                }
            groups.forEach { (heading, rows) ->
                if (rows.isNotEmpty()) item { SettingsSection(stringResource(heading)) }
                itemsIndexed(rows, key = { _, row -> row.candidate.accountIdHex }) { index, row ->
                    NewMessagePersonRow(
                        person = row,
                        index = index,
                        count = rows.size,
                        enabled = !busy,
                        creating = creatingHex == row.candidate.accountIdHex,
                        onClick = { actions.person(row.candidate) },
                        onLongClick = { actions.profile(row.candidate) },
                    )
                }
            }
        }
    }
}

/** Keeps the production scanner and own-code entry distinct while adopting the target grouped links. */
@Composable
@Suppress("FunctionNaming")
private fun NewMessageActionGroup(
    actions: NewMessageActions,
    showMyQrEnabled: Boolean,
    busy: Boolean,
) {
    SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Related)) {
        row("new_group") { context ->
            SettingsLink(
                context,
                stringResource(R.string.new_group),
                actions.newGroup,
                modifier = Modifier.performanceTestTag(PerformanceTestTags.NEW_GROUP),
                enabled = !busy,
                leading = { Icon(painterResource(R.drawable.ic_group_add), null, Modifier.size(24.dp)) },
            )
        }
        row("scan_qr") { context ->
            SettingsLink(
                context,
                stringResource(R.string.new_message_connect_qr),
                actions.scanQr,
                enabled = !busy,
                leading = { Icon(painterResource(R.drawable.ic_qr_code_scanner), null, Modifier.size(24.dp)) },
            )
        }
        row("invite") { context ->
            SettingsLink(
                context,
                stringResource(R.string.new_message_invite_friend),
                actions.invite,
                enabled = !busy,
                leading = { Icon(painterResource(R.drawable.ic_share), null, Modifier.size(24.dp)) },
            )
        }
        row("my_qr") { context ->
            SettingsLink(
                context,
                stringResource(R.string.show_my_qr_code),
                actions.showMyQr,
                enabled = !busy && showMyQrEnabled,
                leading = { Icon(Icons.Default.QrCode, null, Modifier.size(24.dp)) },
            )
        }
    }
}

/** Native interactive row with prototype segmented surfaces and the existing followed state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongParameterList")
internal fun NewMessagePersonRow(
    person: NewMessagePerson,
    index: Int,
    count: Int,
    enabled: Boolean,
    creating: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val candidate = person.candidate
    val shapes = WhiteNoiseListItemDefaults.segmentedShapes(index, count)
    val followed = if (candidate.isFollowing) stringResource(R.string.user_search_you_follow) else null
    Column(Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)) {
        ListItem(
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            shapes = shapes,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .amoledSurfaceBorder(shapes.shape)
                    .testTag("creation.person.${candidate.accountIdHex}")
                    .semantics { role = Role.Button }
                    .recipientRelationshipSemantics(followed, null),
            leadingContent = {
                Box(Modifier.size(48.dp)) {
                    Avatar(candidate.displayName, candidate.accountIdHex, size = 48.dp, pictureUrl = person.avatarUrl)
                    if (candidate.isFollowing) FollowedPersonBadge(Modifier.align(Alignment.BottomEnd))
                }
            },
            supportingContent =
                person.subtitle?.let { subtitle ->
                    { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            trailingContent =
                if (creating) {
                    { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            content = { Text(candidate.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        if (index < count - 1) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
    }
}

/** Search status remains truthful when native discovery is partial; local rows stay available. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("FunctionNaming", "LongParameterList", "CyclomaticComplexMethod") // Declarative status/action combinations.
internal fun NewMessageSearchFeedback(
    searching: Boolean,
    failed: Boolean,
    incomplete: Boolean,
    empty: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onInvite: () -> Unit,
    retryableIdentifier: Boolean = false,
) {
    val hasStatus = searching || failed || incomplete
    if (!hasStatus && !empty) return
    val retryableEmpty = empty && retryableIdentifier
    val retryAvailable = failed || incomplete || retryableEmpty
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("people.search_status")
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            searching -> Text(stringResource(R.string.user_search_searching))
            failed -> Text(stringResource(R.string.user_search_failed))
            incomplete -> Text(stringResource(R.string.user_search_incomplete))
            empty ->
                WhiteNoiseEmptyState(
                    stringResource(R.string.no_matches),
                    stringResource(R.string.new_message_no_results_detail),
                    Modifier.fillMaxWidth(),
                )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!searching && retryAvailable) {
                TextButton(onClick = onRetry, enabled = !busy, modifier = Modifier.testTag("people.retry")) {
                    Text(stringResource(R.string.retry))
                }
            }
            if (!searching && empty) {
                TextButton(onClick = onInvite, enabled = !busy) {
                    Text(stringResource(R.string.new_message_invite_friend))
                }
            }
        }
    }
}
