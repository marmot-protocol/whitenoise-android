package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.qr.QrScanOutcome
import dev.ipf.whitenoise.android.ui.qr.QrScanResult
import dev.ipf.whitenoise.android.ui.qr.QrScanUseCase
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.Dimens
import java.util.Locale

/**
 * Multi-select people picker shared by the New Group flow and the group
 * details Add Members flow. Selection lives in the caller's [selected] list so
 * flows can carry it across steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactPickerScreen(
    appState: WhiteNoiseAppState,
    title: String,
    selected: SnapshotStateList<RecipientSearch.Candidate>,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    confirmIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowForward,
    confirmLabel: String = stringResource(R.string.next),
    busy: Boolean = false,
    // When true the confirm FAB is enabled with no members selected, so the
    // group-creation flow can proceed to naming and create a name-first group
    // (members added later). Add-members and other pickers keep requiring a
    // selection (default false).
    allowEmptyConfirm: Boolean = false,
    // Add-member paste/scan is an unambiguous target selection; group creation
    // keeps the old manual selection behavior for identifier queries.
    autoSelectResolvedIdentifier: Boolean = false,
    excludeAccountIdHexes: Set<String> = emptySet(),
    footer: (@Composable () -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    // The selection itself is owned by the caller and is not saveable. Keep
    // this transient navigation state in the same lifetime so process
    // recreation cannot restore an empty review screen over a rebuilt picker.
    var reviewingSelection by remember { mutableStateOf(false) }
    val resolution = rememberRecipientResolution(query, appState)

    // Installed unconditionally: a disabled handler would let back fall
    // through to the Activity while a mutation is mid-flight.
    BackHandler {
        if (!busy) {
            if (reviewingSelection) reviewingSelection = false else onBack()
        }
    }

    if (reviewingSelection) {
        SelectedMembersReviewScreen(
            members = selected,
            appState = appState,
            busy = busy,
            onBack = { reviewingSelection = false },
            onRemove = { member ->
                selected.removeAll { it.accountIdHex.equals(member.accountIdHex, ignoreCase = true) }
                if (selected.isEmpty()) reviewingSelection = false
            },
            onConfirm = onConfirm,
            confirmIcon = confirmIcon,
            confirmLabel = confirmLabel,
        )
        return
    }

    val activeHex = appState.activeAccount?.accountIdHex
    val candidates =
        remember(appState.chatListItems, activeHex, appState.profileRevisionForCompose) {
            deriveRecipientCandidates(appState, activeHex)
        }
    val identifierQuery = query.isNotBlank() && !isPlainNameQuery(query)
    val userSearch by rememberRecipientUserSearchState(query, appState)
    val discovered = userSearch.candidates
    val followedIds = userSearch.followedAccountIds
    val matches =
        remember(query, candidates, discovered, followedIds, activeHex, excludeAccountIdHexes) {
            if (identifierQuery) {
                emptyList()
            } else {
                RecipientSearch.mergeAndBrowse(
                    query = query,
                    known = candidates,
                    discovered = discovered,
                    activeAccountIdHex = activeHex,
                    excludeAccountIdHexes = excludeAccountIdHexes,
                    followedAccountIds = followedIds,
                )
            }
        }
    val selectedHexes = selected.map { it.accountIdHex.lowercase(Locale.ROOT) }.toSet()
    val canConfirm = (allowEmptyConfirm || selected.isNotEmpty()) && !busy

    fun toggle(candidate: RecipientSearch.Candidate) {
        val hex = candidate.accountIdHex.lowercase(Locale.ROOT)
        if (hex in selectedHexes) {
            selected.removeAll { it.accountIdHex.lowercase(Locale.ROOT) == hex }
        } else {
            selected.add(candidate)
            query = ""
        }
    }

    Scaffold(
        modifier = Modifier.imePadding().exposePerformanceTestTags(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        if (selected.isNotEmpty()) {
                            Text(
                                pluralStringResource(
                                    R.plurals.selected_members_count,
                                    selected.size,
                                    selected.size,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (canConfirm) onConfirm() },
                modifier = Modifier.performanceTestTag(PerformanceTestTags.CONTACT_PICKER_NEXT),
                containerColor =
                    if (canConfirm) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (canConfirm) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        confirmIcon,
                        contentDescription = confirmLabel,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            FlowSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.search_people_hint),
                onScanQr = { showScanner = true },
                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
            )
            AnimatedVisibility(visible = selected.isNotEmpty()) {
                SelectedMemberSummary(
                    members = selected,
                    appState = appState,
                    onClick = { if (!busy) reviewingSelection = true },
                    enabled = !busy,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                // A pasted/scanned self identifier is dropped like the browse
                // list drops the active account, landing on "No matches".
                val resolvedHex =
                    resolution.resolvedHex?.takeUnless { it.equals(activeHex, ignoreCase = true) }
                if (identifierQuery && resolution.state == RecipientPreviewState.Resolving) {
                    item { ResolvingContactRow() }
                } else if (identifierQuery && resolvedHex != null) {
                    item {
                        val alreadyMember =
                            groupContainsResolvedMember(
                                memberHexes = excludeAccountIdHexes.toList(),
                                resolvedHex = resolvedHex,
                            )
                        val resolvedAccountIdHex = resolvedHex.lowercase(Locale.ROOT)
                        val isSelected = resolvedAccountIdHex in selectedHexes
                        val candidate =
                            RecipientSearch.Candidate(
                                accountIdHex = resolvedAccountIdHex,
                                displayName = appState.displayName(resolvedAccountIdHex),
                                npub = appState.npub(resolvedAccountIdHex),
                            )
                        if (
                            shouldAutoSelectResolvedIdentifier(
                                autoSelectResolvedIdentifier = autoSelectResolvedIdentifier,
                                busy = busy,
                                alreadyMember = alreadyMember,
                                isSelected = isSelected,
                            )
                        ) {
                            LaunchedEffect(resolvedAccountIdHex) {
                                toggle(candidate)
                            }
                        }
                        ContactRow(
                            title = candidate.displayName,
                            subtitle =
                                if (alreadyMember) {
                                    stringResource(R.string.add_member_already_in_group, candidate.displayName)
                                } else {
                                    appState.shortNpub(resolvedAccountIdHex).takeIf { it.isNotBlank() }
                                },
                            avatarSeed = resolvedAccountIdHex,
                            avatarUrl = appState.avatarUrl(resolvedAccountIdHex),
                            enabled = !busy && !alreadyMember,
                            onClick = { toggle(candidate) },
                            onLongClick = { appState.presentProfile(candidate.npub) },
                            trailing = {
                                SelectionIndicator(selected = isSelected, dimmed = alreadyMember)
                            },
                        )
                    }
                } else if (identifierQuery || matches.isEmpty()) {
                    item {
                        if (!identifierQuery && userSearch.isSearching) {
                            UserSearchStatusRow(R.string.user_search_searching, showProgress = true)
                        } else {
                            Text(
                                stringResource(
                                    when {
                                        query.isBlank() -> R.string.recipient_search_empty_hint
                                        userSearch.failed -> R.string.user_search_failed
                                        userSearch.isIncomplete -> R.string.user_search_incomplete
                                        else -> R.string.no_matches
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                            )
                        }
                    }
                } else {
                    item { SectionHeader(stringResource(R.string.contacts)) }
                    items(matches, key = { it.accountIdHex }) { candidate ->
                        val isSelected = candidate.accountIdHex.lowercase(Locale.ROOT) in selectedHexes
                        ContactRow(
                            title = candidate.displayName,
                            subtitle =
                                when {
                                    candidate.isFollowing -> stringResource(R.string.user_search_you_follow)
                                    candidate.searchProfile != null -> stringResource(R.string.user_search_result)
                                    else -> appState.shortNpub(candidate.accountIdHex).takeIf { it.isNotBlank() }
                                },
                            avatarSeed = candidate.accountIdHex,
                            avatarUrl =
                                appState.avatarUrl(candidate.accountIdHex)
                                    ?: ProfileSanitizer.protocolImageUrl(candidate.searchProfile?.picture),
                            isFollowed = candidate.isFollowing,
                            selectionState = isSelected,
                            enabled = !busy,
                            onClick = { toggle(candidate) },
                            onLongClick = {
                                if (candidate.searchProfile != null) {
                                    appState.presentDiscoveredProfile(candidate.npub, candidate.searchProfile)
                                } else {
                                    appState.presentProfile(candidate.npub)
                                }
                            },
                            trailing = { SelectionIndicator(selected = isSelected) },
                        )
                    }
                    if (userSearch.isSearching) {
                        item { UserSearchStatusRow(R.string.user_search_searching, showProgress = true) }
                    } else if (userSearch.failed || userSearch.isIncomplete) {
                        item {
                            UserSearchStatusRow(
                                if (userSearch.failed) R.string.user_search_failed else R.string.user_search_incomplete,
                            )
                        }
                    }
                }
            }
            footer?.invoke()
        }
    }

    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                when (val outcome = QrScanResult.resolve(raw, QrScanUseCase.PickRecipient)) {
                    is QrScanOutcome.FillRecipientQuery -> query = outcome.reference
                    QrScanOutcome.Invalid ->
                        appState.present(R.string.error_qr_not_valid_npub_or_public_key, copyable = true)
                    is QrScanOutcome.OpenProfileNpub, is QrScanOutcome.OpenProfileNprofile ->
                        appState.present(R.string.error_qr_not_valid_npub_or_public_key, copyable = true)
                }
            },
        )
    }
}

internal fun shouldAutoSelectResolvedIdentifier(
    autoSelectResolvedIdentifier: Boolean,
    busy: Boolean,
    alreadyMember: Boolean,
    isSelected: Boolean,
): Boolean = autoSelectResolvedIdentifier && !busy && !alreadyMember && !isSelected
