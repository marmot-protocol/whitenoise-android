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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.TextEntryEmojiAction
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.settings.SettingsBottomAction
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
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
)

/** Existing owner callbacks for editing and submitting the native group draft. */
internal data class NewGroupSetupActions(
    val back: () -> Unit,
    val create: () -> Unit,
    val photo: () -> Unit,
    val retention: () -> Unit,
    val emoji: () -> Unit,
)

/** Target centered avatar, two authored fields, timer/members and pinned full-width creation action. */
@Composable
@Suppress("FunctionNaming", "LongMethod") // Compose naming follows the framework convention.
internal fun NewGroupSetupContent(
    draft: NewGroupDraft,
    state: NewGroupSetupPresentation,
    actions: NewGroupSetupActions,
    photoMenu: @Composable () -> Unit = {},
) {
    SettingsScaffold(
        title = stringResource(R.string.group_setup_title),
        onBack = { if (!state.busy) actions.back() },
        modifier = Modifier.imePadding().testTag("group_setup.screen"),
        bottomBar = {
            GroupCreationBottomAction(
                label =
                    stringResource(
                        if (draft.retryGroupIdHex == null) {
                            R.string.group_create_action
                        } else {
                            R.string.new_message_open_chat
                        },
                    ),
                enabled = state.submitEnabled,
                busy = state.busy,
                onClick = actions.create,
                tag = "group_setup.create",
            )
        },
    ) {
        LazyColumn(
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
            item {
                GroupSetupPhoto(draft, state, actions.photo, photoMenu)
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    WhiteNoiseTextField(
                        state = draft.name,
                        enabled = state.detailsEditable,
                        modifier = Modifier.fillMaxWidth().testTag("group_setup.name"),
                        label = { Text(stringResource(R.string.group_name)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        leadingIcon = {
                            TextEntryEmojiAction(state.emojiOpen, state.detailsEditable, actions.emoji)
                        },
                    )
                    WhiteNoiseTextField(
                        state = draft.description,
                        enabled = state.detailsEditable,
                        modifier = Modifier.fillMaxWidth().testTag("group_setup.description"),
                        label = { Text(stringResource(R.string.description)) },
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
                    )
                }
            }
            item {
                SettingsGroup(Modifier.padding(top = WhiteNoiseSpacing.Section)) {
                    row("retention") { context ->
                        SettingsLink(
                            context,
                            stringResource(R.string.disappearing_messages),
                            actions.retention,
                            value = state.retentionLabel,
                            enabled = state.detailsEditable,
                        )
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.members)) }
            if (state.members.isEmpty()) {
                item {
                    SettingsExplainer(stringResource(R.string.group_add_members_after_create))
                }
            }
            itemsIndexed(state.members, key = { _, person -> person.candidate.accountIdHex }) { index, person ->
                GroupCreationPersonRow(person, index, state.members.size, selected = null)
            }
        }
    }
}

/** A distinct photo action retains a 48dp target while the prototype avatar stays 120dp. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
private fun GroupSetupPhoto(
    draft: NewGroupDraft,
    state: NewGroupSetupPresentation,
    onPhoto: () -> Unit,
    photoMenu: @Composable () -> Unit,
) {
    val photoDescription = stringResource(R.string.group_photo_label)
    Column(
        Modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (draft.name.text.isBlank() && state.imagePreview == null) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    Modifier
                        .size(120.dp)
                        .testTag("group_setup.avatar")
                        .semantics { contentDescription = photoDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Group, null, Modifier.size(48.dp))
                }
            }
        } else {
            Box(Modifier.testTag("group_setup.avatar").semantics { contentDescription = photoDescription }) {
                Avatar(draft.name.text.toString(), draft.name.text.toString(), 120.dp, picture = state.imagePreview)
            }
        }
        Box {
            TextButton(
                onPhoto,
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
                        NewGroupCreateStage.ApplyingRetention -> R.string.group_create_stage_applying_retention
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
    SettingsBottomAction(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        WhiteNoiseButton(
            onClick = onClick,
            enabled = enabled,
            modifier =
                Modifier.widthIn(max = 520.dp).fillMaxWidth().testTag(tag).let {
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
