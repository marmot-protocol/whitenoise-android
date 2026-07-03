package dev.ipf.whitenoise.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.Dimens

/**
 * Final step of the New Group flow: name the group, preview the invited
 * members, and create. Disappearing messages picked here are applied right
 * after the create commit, before anyone can post.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewGroupSetupScreen(
    appState: WhiteNoiseAppState,
    members: List<RecipientSearch.Candidate>,
    onBack: () -> Unit,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
) {
    var groupName by rememberSaveable { mutableStateOf("") }
    var retentionSecs by rememberSaveable { mutableLongStateOf(0L) }
    var showRetentionPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val missingKeyPackageError = stringResource(R.string.error_missing_key_package)
    val missingKeyPackageForFormat = stringResource(R.string.error_missing_key_package_for)
    val invalidIdentityReferenceError = stringResource(R.string.error_invalid_identity_reference)
    val groupPublishFailedFormat = stringResource(R.string.error_group_publish_failed)

    fun createGroupErrorMessage(throwable: Throwable): String =
        when (throwable) {
            is MarmotKitException.MissingKeyPackage ->
                if (throwable.account.isNotBlank()) {
                    String.format(missingKeyPackageForFormat, appState.chatMemberTitle(throwable.account))
                } else {
                    missingKeyPackageError
                }
            is MarmotKitException.InvalidIdentity -> invalidIdentityReferenceError
            is MarmotKitException.Publish -> String.format(groupPublishFailedFormat, throwable.details)
            else -> throwable.message ?: throwable.javaClass.simpleName
        }

    val canCreate = canSubmitNewChatSheet(directMessage = false, busy = busy, pendingRecipient = "", groupName = groupName)

    fun create() {
        if (!canCreate) return
        val account = appState.activeAccountRef ?: return
        val recipients =
            newChatMemberRefs(
                directMessage = false,
                normalizedPendingRecipients = emptyList(),
                initialMemberRefs = members.map { it.accountIdHex },
            )
        busy = true
        error = null
        appState.launchMutation {
            runCatching {
                appState.marmotIo {
                    createGroup(account, groupName.trim(), recipients, null)
                }
            }.onSuccess { groupIdHex ->
                if (retentionSecs > 0L) {
                    // Applied post-create because the create commit has no
                    // retention parameter; a failure here leaves the group
                    // usable with the default (off) window.
                    runCatching {
                        appState.marmotIo { updateMessageRetention(account, groupIdHex, retentionSecs.toULong()) }
                    }
                }
                appState.present(R.string.toast_chat_created)
                appState.awaitChatListItem(groupIdHex)?.let { item ->
                    onOpenConversation(item, false)
                }
            }.onFailure {
                error = createGroupErrorMessage(it)
            }
            busy = false
        }
    }

    BackHandler(enabled = !busy) { onBack() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.name_this_group)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { create() },
                containerColor =
                    if (canCreate) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (canCreate) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
                Spacer(Modifier.size(Dimens.spaceSm))
                Text(stringResource(R.string.create))
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
            ) {
                val photoHint = stringResource(R.string.group_photo_after_create)
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(role = Role.Button, onClickLabel = photoHint) {
                                appState.present(R.string.group_photo_after_create)
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                    enabled = !busy,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                        ),
                    modifier = Modifier.weight(1f),
                )
            }
            error?.let { message ->
                SelectionContainer {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
                    )
                }
            }
            SettingsActionRow(
                icon = Icons.Default.Schedule,
                title = stringResource(R.string.disappearing_messages),
                value = disappearingMessagesLabel(retentionSecs),
                enabled = !busy,
                onClick = { showRetentionPicker = true },
            )
            SettingsActionRow(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.group_permissions),
                enabled = false,
                comingSoon = true,
            )
            SectionHeader("${stringResource(R.string.members)} · ${members.size}")
            members.forEach { member ->
                key(member.accountIdHex) {
                    ContactRow(
                        title = appState.displayName(member.accountIdHex),
                        subtitle = IdentityFormatter.short(member.npub),
                        avatarSeed = member.accountIdHex,
                        avatarUrl = appState.avatarUrl(member.accountIdHex),
                    )
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }

    if (showRetentionPicker) {
        DisappearingMessagesPickerDialog(
            currentSecs = retentionSecs,
            onDismiss = { showRetentionPicker = false },
            onPick = { secs ->
                showRetentionPicker = false
                retentionSecs = secs
            },
        )
    }
}
