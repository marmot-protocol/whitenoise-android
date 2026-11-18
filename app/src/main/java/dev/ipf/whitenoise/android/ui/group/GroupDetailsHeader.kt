package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Archive
import dev.ipf.whitenoise.android.ui.icons.filled.Edit
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.settings.IdentifierCopyCapsule
import kotlinx.coroutines.delay

/** Adaptive conversation identity with native edit, full-picture and public-key copy actions. */
@Composable
// Existing adaptive header keeps its optional viewer action at the presentation boundary.
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
) {
    val (editOrigin, editClick) =
        dev.ipf.whitenoise.android.ui.settings
            .rememberSettingsRouteClick { onEdit?.invoke() }
    val (descriptionOrigin, descriptionClick) =
        dev.ipf.whitenoise.android.ui.settings
            .rememberSettingsRouteClick { onAddDescription?.invoke() }
    val clipboard = LocalClipboardManager.current
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avatarSize = (maxWidth * 0.32f).coerceIn(104.dp, 152.dp)
        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            if (onEdit == null) {
                EmojiLabel(
                    title,
                    modifier =
                        Modifier.padding(top = 4.dp).testTag("chat_info.name").onGloballyPositioned {
                            onNameBottom(it.positionInWindow().y + it.size.height)
                        },
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(
                    modifier =
                        Modifier
                            .widthIn(max = 440.dp)
                            .padding(top = 4.dp)
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
                    EmojiLabel(
                        title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.Default.Edit,
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
                    IdentifierCopyCapsule(
                        value = copyValue,
                        copied = copied,
                        onCopy = {
                            clipboard.setText(AnnotatedString(copyValue))
                            copied = true
                        },
                        copyContentDescription = stringResource(R.string.copy_public_key),
                        copiedContentDescription = stringResource(R.string.copied),
                        notCopiedStateDescription = stringResource(R.string.not_copied),
                        copiedStateDescription = stringResource(R.string.copied),
                        targetTestTag = "chat_info.copy_public_key",
                        visualTestTag = "chat_info.copy_public_key.visual",
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (archived) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.archive_chat)) },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    )
                }
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
