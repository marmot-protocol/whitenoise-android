package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.account.StartingAccount
import dev.ipf.whitenoise.android.ui.account.StartingAccountIndicator
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.TextEntryEmojiAction
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseLazyColumn
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.emojiSheetFieldAnchor
import dev.ipf.whitenoise.android.ui.common.rememberEmojiSheetOpener
import dev.ipf.whitenoise.android.ui.settings.SettingsBottomAction
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLeadingIcon
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.rememberSettingsRouteClick
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Current group form rendering; native work and draft ownership remain in NewGroupSetupScreen. */
internal data class NewGroupSetupPresentation(
    val members: List<GroupCreationPerson>,
    val imagePreview: ImageBitmap?,
    val imagePreparing: Boolean,
    val imageError: Boolean,
    val detailsEditable: Boolean,
    val submitEnabled: Boolean,
    val busy: Boolean,
    val stage: NewGroupCreateStage?,
    val error: String?,
    val retentionLabel: String,
    val emojiOpen: Boolean,
    val recoveryRecipient: String? = null,
    val hasUnacceptedFailure: Boolean = false,
)

/** Existing owner callbacks for editing and submitting the native group draft. */
internal data class NewGroupSetupActions(
    val back: () -> Unit,
    val create: () -> Unit,
    val photo: () -> Unit,
    val retention: () -> Unit,
    val emoji: () -> Unit,
    val removeFailedRecipient: () -> Unit = {},
)

/** Start with the essential fields and reveal any existing draft customization on entry. */
@Composable
@Suppress("FunctionNaming", "LongMethod") // Compose naming follows the framework convention.
internal fun NewGroupSetupContent(
    draft: NewGroupDraft,
    state: NewGroupSetupPresentation,
    actions: NewGroupSetupActions,
    photoMenu: @Composable () -> Unit = {},
    startingAs: StartingAccount? = null,
) {
    var customize by rememberSaveable {
        mutableStateOf(draft.description.text.isNotBlank() || draft.imageDraft != null || draft.imageNeedsReselection)
    }
    var showMembers by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(draft.imageNeedsReselection) {
        if (draft.imageNeedsReselection) customize = true
    }
    SettingsScaffold(
        topBarActions = { StartingAccountIndicator(startingAs) },
        title = stringResource(R.string.group_setup_title),
        onBack = { if (!state.busy) actions.back() },
        modifier = Modifier.imePadding().testTag("group_setup.screen"),
        bottomBar = {
            GroupCreationBottomAction(
                label = stringResource(groupSetupActionLabel(draft, state)),
                enabled = state.submitEnabled,
                busy = state.busy,
                onClick = actions.create,
                tag = "group_setup.create",
            )
        },
    ) {
        WhiteNoiseLazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = 520.dp)
                    .testTag("group_setup.content"),
            contentPadding = PaddingValues(vertical = WhiteNoiseSpacing.Section),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Explain an accepted create or retry failure before the locked form, including on short screens.
            item { GroupSetupStatus(draft.retryGroupIdHex, state.stage, state.error, state.busy) }
            if (state.hasUnacceptedFailure && draft.retryGroupIdHex == null) {
                item { GroupSetupRecovery(state, actions) }
            }
            item { GroupSetupNameField(draft, state, customize, actions.emoji) }
            item { GroupSetupPhotoStatus(draft, state) }
            item(key = "group_setup.customize") {
                GroupSetupDisclosure(
                    label =
                        stringResource(
                            if (customize) R.string.group_setup_hide_customization else R.string.group_setup_customize,
                        ),
                    expanded = customize,
                    tag = "group_setup.customize",
                    onClick = { customize = !customize },
                )
            }
            if (customize) {
                item { GroupSetupPhoto(draft, state, actions.photo, photoMenu) }
                item(key = "group_setup.description") {
                    WhiteNoiseTextField(
                        state = draft.description,
                        emojiAction = true,
                        enabled = state.detailsEditable,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("group_setup.description"),
                        label = { Text(stringResource(R.string.description)) },
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 6),
                    )
                }
                item(key = "group_setup.retention") {
                    SettingsGroup(Modifier.padding(top = WhiteNoiseSpacing.Related)) {
                        row("retention") { context ->
                            SettingsLink(
                                context,
                                stringResource(R.string.disappearing_messages),
                                actions.retention,
                                value = state.retentionLabel,
                                enabled = state.detailsEditable,
                                leading = { SettingsLeadingIcon(R.drawable.ic_timer) },
                            )
                        }
                    }
                }
            }
            if (state.members.isNotEmpty()) {
                item(key = "group_setup.members") {
                    val count = state.members.size
                    GroupSetupDisclosure(
                        label = pluralStringResource(R.plurals.group_setup_selected_members, count, count),
                        expanded = showMembers,
                        tag = "group_setup.members",
                        onClick = { showMembers = !showMembers },
                    )
                }
                if (showMembers) {
                    itemsIndexed(state.members, key = { _, person -> person.candidate.accountIdHex }) { index, person ->
                        GroupCreationPersonRow(person, index, state.members.size, selected = null)
                    }
                }
            }
            if (state.members.isEmpty()) {
                item {
                    SettingsExplainer(stringResource(R.string.group_add_members_after_create))
                }
            }
        }
    }
}

private fun groupSetupActionLabel(
    draft: NewGroupDraft,
    state: NewGroupSetupPresentation,
): Int =
    when {
        draft.retryGroupIdHex != null -> R.string.new_message_open_chat
        state.hasUnacceptedFailure -> R.string.retry
        state.members.isEmpty() -> R.string.group_create_solo
        else -> R.string.group_create_action
    }

/** Anchor the emoji sheet to the name and advance to the description when it is visible. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun GroupSetupNameField(
    draft: NewGroupDraft,
    state: NewGroupSetupPresentation,
    customize: Boolean,
    onEmoji: () -> Unit,
) {
    val openBelowName = rememberEmojiSheetOpener(draft.nameEmojiAnchor)
    WhiteNoiseTextField(
        state = draft.name,
        enabled = state.detailsEditable,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .emojiSheetFieldAnchor(draft.nameEmojiAnchor)
                .testTag("group_setup.name"),
        label = { Text(stringResource(R.string.group_name)) },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = if (customize) ImeAction.Next else ImeAction.Done),
        leadingIcon = {
            TextEntryEmojiAction(
                pickerOpen = state.emojiOpen,
                enabled = state.detailsEditable,
                onClick = { openBelowName(onEmoji) },
            )
        },
    )
}

/** Expose optional setup content and announce each disclosure's state. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun GroupSetupDisclosure(
    label: String,
    expanded: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val disclosureState =
        stringResource(if (expanded) R.string.onboarding_details_expanded else R.string.onboarding_details_collapsed)
    val arrowRotation = if (expanded) 180f else 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag(tag).semantics { stateDescription = disclosureState },
        ) {
            Text(label, modifier = Modifier.weight(1f, fill = false))
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.rotate(arrowRotation),
            )
        }
    }
}

/** Optional photo editing uses a compact preview and a labeled photo action. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun GroupSetupPhoto(
    draft: NewGroupDraft,
    state: NewGroupSetupPresentation,
    onPhoto: () -> Unit,
    photoMenu: @Composable () -> Unit,
) {
    val photoDescription = stringResource(R.string.group_photo_label)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (draft.name.text.isBlank() && state.imagePreview == null) {
            GroupSetupEmptyAvatar(photoDescription)
        } else {
            Box(Modifier.testTag("group_setup.avatar").semantics { contentDescription = photoDescription }) {
                Avatar(draft.name.text.toString(), draft.name.text.toString(), 64.dp, picture = state.imagePreview)
            }
        }
        Box {
            WhiteNoiseFilledTonalButton(
                onClick = onPhoto,
                enabled = state.detailsEditable,
                modifier = Modifier.testTag("group_setup.photoAction"),
            ) {
                Text(
                    stringResource(
                        if (draft.imageDraft == null) R.string.group_add_photo else R.string.group_change_photo,
                    ),
                )
            }
            photoMenu()
        }
    }
}

/** Photo errors remain visible when customization is collapsed. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun GroupSetupPhotoStatus(
    draft: NewGroupDraft,
    state: NewGroupSetupPresentation,
) {
    if (!state.imagePreparing && !state.imageError && !draft.imageNeedsReselection) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.imagePreparing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.group_preparing_photo))
            }
        }
        if (state.imageError || draft.imageNeedsReselection) {
            Text(
                stringResource(
                    if (draft.imageNeedsReselection) R.string.group_photo_reselect else R.string.group_photo_error,
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Placeholder for an unnamed group with no chosen photo. */
@Composable
@Suppress("FunctionNaming")
private fun GroupSetupEmptyAvatar(photoDescription: String) {
    Surface(
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .testTag("group_setup.avatar")
                .semantics { contentDescription = photoDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_group),
                null,
                Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Creation status is driven by native stages; canonical recovery remains distinct from an unaccepted failure. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun GroupSetupStatus(
    retryGroupId: String?,
    stage: NewGroupCreateStage?,
    error: String?,
    busy: Boolean,
) {
    if (retryGroupId == null && stage == null && error == null) return
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (retryGroupId != null) {
            Text(stringResource(R.string.new_message_chat_created), style = MaterialTheme.typography.titleMedium)
            if (!busy) Text(stringResource(R.string.error_chat_created_not_loaded))
        }
        if (stage != null) {
            Text(
                stringResource(
                    when (stage) {
                        NewGroupCreateStage.Creating -> R.string.group_create_stage_creating
                    },
                ),
            )
        }
        if (error != null) {
            SelectionContainer {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Pinned creation/continue action uses the shared inset, width and native keyboard-safe container. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun GroupCreationBottomAction(
    label: String,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
    tag: String,
) {
    // Only the picker Continue action navigates to another full-screen page.
    val (origin, navigate) =
        if (tag == "new_group.continue") rememberSettingsRouteClick(onClick) else Modifier to onClick
    SettingsBottomAction(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        WhiteNoiseButton(
            onClick = navigate,
            enabled = enabled,
            modifier =
                Modifier.then(origin).widthIn(max = 520.dp).fillMaxWidth().testTag(tag).let {
                    if (tag == "new_group.continue") {
                        it.performanceTestTag(PerformanceTestTags.CONTACT_PICKER_NEXT)
                    } else {
                        it
                    }
                },
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(label)
        }
    }
}

/** Pre-creation recovery never offers founding-member edits once a canonical group exists. */
@Composable
@Suppress("FunctionNaming")
private fun GroupSetupRecovery(
    state: NewGroupSetupPresentation,
    actions: NewGroupSetupActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            state.recoveryRecipient ?: stringResource(R.string.group_create_unattributed_failure),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.recoveryRecipient != null) {
            WhiteNoiseFilledTonalButton(
                onClick = actions.removeFailedRecipient,
                enabled = state.detailsEditable,
                modifier = Modifier.fillMaxWidth().testTag("group_setup.removeFailedRecipient"),
            ) {
                Text(stringResource(R.string.group_create_remove_and_continue))
            }
        }
        WhiteNoiseFilledTonalButton(
            onClick = actions.back,
            enabled = state.detailsEditable,
            modifier = Modifier.fillMaxWidth().testTag("group_setup.editMembers"),
        ) {
            Text(stringResource(R.string.group_create_edit_members))
        }
    }
}
