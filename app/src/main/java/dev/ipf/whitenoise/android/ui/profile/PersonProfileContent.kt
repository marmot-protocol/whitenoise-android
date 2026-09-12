@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.IdentifierCopyCapsule
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsBottomAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Prototype Person Profile chrome consumes real identity/relationship state and leaves every mutation to its owner. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun PersonProfileContent(
    person: PersonProfilePresentation,
    scroll: ScrollState,
    follow: ProfileFollowRowState,
    busy: Boolean,
    canPromote: Boolean,
    showSharedGroups: Boolean,
    copied: Boolean,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onFollow: () -> Unit,
    onPrivateDetails: () -> Unit,
    onStartGroup: () -> Unit,
    onGroupEntry: () -> Unit,
    onPromote: () -> Unit,
    onCopy: () -> Unit,
    onAvatar: () -> Unit,
    onBanner: () -> Unit,
    onCopyLightning: () -> Unit,
    sharedAvatars: @Composable () -> Unit = {},
    error: @Composable () -> Unit = {},
    adminActions: @Composable () -> Unit = {},
) {
    SettingsScaffold(
        title =
            person.roleLabel?.let { stringResource(R.string.person_profile_with_role, it) }
                ?: stringResource(R.string.person_profile_title),
        onBack = onBack,
        bottomBar = {
            if (person.hasTarget && !person.self) {
                PersonProfileBottomAction(
                    stringResource(R.string.message),
                    onMessage,
                    !busy,
                    busy,
                    modifier = Modifier.testTag(PROFILE_MESSAGE_ACTION_TAG),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxSize()
                    .whiteNoiseVerticalScroll(scroll)
                    .padding(vertical = WhiteNoiseSpacing.Section)
                    .testTag(PROFILE_SHEET_CONTENT_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PersonProfileIdentity(person, copied, onCopy, onAvatar, onBanner, onCopyLightning)
                error()
                if (!person.hasTarget) {
                    Text(
                        stringResource(R.string.couldnt_read_profile_code),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (person.hasTarget && !person.self) {
                    Spacer(Modifier.height(WhiteNoiseSpacing.Section))
                    SettingsGroup {
                        row("groups") { row ->
                            if (showSharedGroups) {
                                SettingsLink(
                                    row,
                                    stringResource(R.string.person_groups_in_common),
                                    onGroupEntry,
                                    enabled = !busy,
                                    leading = sharedAvatars,
                                    modifier = Modifier.testTag("person_profile.groups"),
                                )
                            } else {
                                SettingsAction(
                                    row,
                                    stringResource(R.string.person_add_to_group),
                                    onGroupEntry,
                                    enabled = !busy,
                                    leading = { Icon(painterResource(R.drawable.ic_group_add), null) },
                                    modifier = Modifier.testTag("person_profile.add_to_group"),
                                )
                            }
                        }
                        row("private") { row ->
                            SettingsAction(
                                row,
                                stringResource(R.string.profile_nickname_and_notes),
                                onPrivateDetails,
                                enabled = !busy,
                                leading = { Icon(painterResource(R.drawable.ic_edit), null) },
                                modifier = Modifier.testTag("person_profile.private_details"),
                            )
                        }
                        row("start_group") { row ->
                            SettingsAction(
                                row,
                                stringResource(R.string.person_start_group),
                                onStartGroup,
                                enabled = !busy,
                                leading = { Icon(painterResource(R.drawable.ic_group_add), null) },
                                modifier = Modifier.testTag("person_profile.start_group"),
                            )
                        }
                        if (canPromote) {
                            row("promote") { row ->
                                SettingsAction(
                                    row,
                                    stringResource(R.string.person_promote_groups),
                                    onPromote,
                                    enabled = !busy,
                                    leading = { Icon(painterResource(R.drawable.ic_admin_panel_settings), null) },
                                    modifier = Modifier.testTag("person_profile.promote_groups"),
                                )
                            }
                        }
                        row("follow") { row ->
                            SettingsAction(
                                row,
                                stringResource(
                                    if (follow.showsUnfollow) R.string.profile_unfollow else R.string.profile_follow,
                                ),
                                onFollow,
                                enabled = follow.enabled,
                                leading = {
                                    if (follow.inProgress) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    } else {
                                        Icon(
                                            painterResource(
                                                if (follow.showsUnfollow) {
                                                    R.drawable.ic_person_remove
                                                } else {
                                                    R.drawable.ic_settings_person_add
                                                },
                                            ),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                modifier = Modifier.testTag(PROFILE_FOLLOW_ACTION_TAG),
                            )
                        }
                    }
                    adminActions()
                }
            }
        }
    }
}

/**
 * Prototype hero uses a 32% avatar bounded to 104–152 dp, with public identity values below the optional about
 * card.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun PersonProfileIdentity(
    person: PersonProfilePresentation,
    copied: Boolean,
    onCopy: () -> Unit,
    onAvatar: () -> Unit,
    onBanner: () -> Unit,
    onCopyLightning: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avatarSize = (maxWidth * 0.32f).coerceIn(104.dp, 152.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            person.bannerUrl?.let { url ->
                val banner = rememberProfileBannerLoadState(url)
                if (banner.visible) {
                    Box(
                        Modifier
                            .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                            .padding(bottom = WhiteNoiseSpacing.FormField),
                    ) {
                        Surface(
                            onClick = onBanner,
                            enabled = banner.image != null,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f).testTag(PROFILE_BANNER_TAG),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                banner.image?.let {
                                    Image(
                                        it,
                                        stringResource(R.string.profile_banner),
                                        Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                    ?: CircularProgressIndicator(Modifier.testTag(PROFILE_BANNER_LOADING_TAG))
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.size(avatarSize).testTag("person_profile.avatar").clickable(
                    enabled = person.avatarClickable,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.profile_view_picture),
                    onClick = onAvatar,
                ),
            ) {
                Avatar(person.title, person.seed, avatarSize, pictureUrl = person.pictureUrl)
            }
            Text(
                person.title,
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = WhiteNoiseSpacing.FormField)
                    .testTag("person_profile.name"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (person.title != person.publishedName) {
                Text(
                    person.publishedName,
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            person.about?.let { about ->
                Surface(
                    Modifier
                        .padding(top = WhiteNoiseSpacing.FormField)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("person_profile.about"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        about,
                        Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(WhiteNoiseSpacing.Related))
            }
            person.nip05?.let { address ->
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        address,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (person.nip05Verified) {
                        Icon(
                            painterResource(R.drawable.ic_verified_filled),
                            stringResource(R.string.profile_nip05_verified),
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            person.lightningAddress?.let { address ->
                Text(
                    stringResource(R.string.person_lightning_display, address),
                    Modifier
                        .padding(horizontal = 16.dp, vertical = WhiteNoiseSpacing.Related)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.copy),
                            onClick = onCopyLightning,
                        ).testTag("person_profile.lightning"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (person.publicKey.isNotBlank()) {
                IdentifierCopyCapsule(
                    person.publicKey,
                    copied,
                    onCopy,
                    stringResource(R.string.copy_public_key),
                    stringResource(R.string.copied),
                    stringResource(R.string.not_copied),
                    stringResource(R.string.copied),
                    "person_profile.copy_public_key",
                    "person_profile.copy_public_key.visual",
                    Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Pinned Message/Add action uses the prototype's scroll-reactive low/container surface and bounded shared button. */
@Suppress("FunctionNaming")
@Composable
internal fun PersonProfileBottomAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    groupAction: Boolean = false,
) {
    val scrolled = (LocalWhiteNoiseHeaderScroll.current?.state?.overlappedFraction ?: 0f) > 0f
    val color by animateColorAsState(
        if (scrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "PersonProfileBottomAction",
    )
    SettingsBottomAction(modifier, color, tonalElevation = 0.dp) {
        WhiteNoiseButton(
            onClick,
            Modifier.widthIn(max = 520.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            enabled = enabled,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painterResource(
                        if (groupAction) {
                            R.drawable.ic_group_add
                        } else {
                            R.drawable.ic_settings_chat_bubble_outline
                        },
                    ),
                    null,
                    Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(WhiteNoiseSpacing.Related))
            Text(label)
        }
    }
}
