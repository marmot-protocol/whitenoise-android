package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.FollowedPersonAvatarFrame
import dev.ipf.whitenoise.android.ui.common.IdentityHeader
import dev.ipf.whitenoise.android.ui.common.IdentityName
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCopier
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.settings.CopyablePublicKey
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.delay

/**
 * Adaptive conversation identity with native edit, full-picture and public-key copy actions. It keeps its optional
 * viewer action at the presentation boundary.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
internal fun GroupDetailsHeader(
    title: String,
    subtitle: String,
    description: String,
    seed: String,
    pictureUrl: String?,
    picture: ImageBitmap? = null,
    groupAnimation: dev.ipf.whitenoise.android.ui.common.GroupAvatarAnimation? = null,
    archived: Boolean,
    onEdit: (() -> Unit)? = null,
    editEnabled: Boolean = true,
    onAddDescription: (() -> Unit)? = null,
    descriptionCopyValue: String? = null,
    onNameBottom: (Float) -> Unit = {},
    readLocalBytes: (suspend () -> ByteArray?)? = null,
    pictureDomain: dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique,
    // A direct chat's peer, as public-key hex, for the followed-person badge; null for a group.
    followedAccount: String? = null,
) {
    val (editOrigin, editClick) =
        dev.ipf.whitenoise.android.ui.settings
            .rememberSettingsRouteClick { onEdit?.invoke() }
    val (descriptionOrigin, descriptionClick) =
        dev.ipf.whitenoise.android.ui.settings
            .rememberSettingsRouteClick { onAddDescription?.invoke() }
    val copier = rememberClipboardCopier()
    val safePictureUrl = ProfileSanitizer.protocolImageUrl(pictureUrl)
    val remoteImageAvailable = rememberAvatarImageAvailable(safePictureUrl.takeIf { picture == null }, pictureDomain)
    val avatarImageAvailable = picture != null || remoteImageAvailable
    var viewerOpen by remember(safePictureUrl, picture) { mutableStateOf(false) }
    var copied by remember(descriptionCopyValue) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(KEY_COPY_FEEDBACK_MILLIS)
            copied = false
        }
    }
    IdentityHeader(
        modifier = Modifier.padding(top = WhiteNoiseSpacing.Section, bottom = WhiteNoiseSpacing.Related),
        avatar = { avatarSize ->
            // The clip shapes the picture viewer's ripple; the followed badge sits outside it.
            FollowedPersonAvatarFrame(followedAccount, avatarSize) {
                Box(
                    modifier =
                        Modifier
                            .testTag("chat_info.avatar")
                            .clip(CircleShape)
                            .clickable(
                                enabled = avatarImageAvailable,
                                onClickLabel = stringResource(R.string.profile_view_picture),
                                role = Role.Button,
                            ) { viewerOpen = true },
                ) {
                    Avatar(
                        title = title,
                        seed = seed,
                        size = avatarSize,
                        pictureUrl = safePictureUrl?.takeIf { picture == null },
                        picture = picture,
                        groupAnimation = groupAnimation,
                        pictureDomain = pictureDomain,
                    )
                }
            }
        },
    ) {
        if (onEdit == null) {
            IdentityName(
                title,
                Modifier.testTag("chat_info.name").onGloballyPositioned {
                    onNameBottom(it.positionInWindow().y + it.size.height)
                },
            )
        } else {
            Row(
                modifier =
                    Modifier
                        .widthIn(max = 440.dp)
                        .heightIn(min = 48.dp)
                        .testTag("chat_info.name")
                        .then(editOrigin)
                        .onGloballyPositioned { onNameBottom(it.positionInWindow().y + it.size.height) }
                        .clickable(
                            enabled = editEnabled,
                            onClickLabel = stringResource(R.string.edit_group_name_and_description),
                            role = Role.Button,
                            onClick = editClick,
                        ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IdentityName(title, Modifier.weight(1f, fill = false))
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (description.isNotBlank()) {
            val copyValue = descriptionCopyValue
            if (copyValue == null) {
                EmojiLabel(
                    description,
                    modifier = Modifier.widthIn(max = 440.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                CopyablePublicKey(
                    npub = copyValue,
                    copied = copied,
                    onCopy = {
                        if (copier.copy(copyValue)) copied = true
                    },
                    targetTestTag = "chat_info.copy_public_key",
                    textTestTag = "chat_info.full_public_key",
                    explanationRes = R.string.explanation_chat_info_copy_peer_public_key,
                )
            }
        } else if (onAddDescription != null) {
            TextButton(onClick = descriptionClick, modifier = descriptionOrigin) {
                Text(
                    stringResource(R.string.add_group_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            if (archived) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.archive_chat)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_archive), contentDescription = null) },
                )
            }
        }
    }
    if (viewerOpen && avatarImageAvailable) {
        AvatarFullScreenViewer(
            title = title,
            seed = seed,
            pictureUrl = safePictureUrl,
            picture = picture,
            onDismiss = { viewerOpen = false },
            readLocalBytes = readLocalBytes,
        )
    }
}

private const val KEY_COPY_FEEDBACK_MILLIS = 2_000L
