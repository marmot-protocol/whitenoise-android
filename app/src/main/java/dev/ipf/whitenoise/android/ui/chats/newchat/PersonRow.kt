package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.FollowedPersonBadge
import dev.ipf.whitenoise.android.ui.common.GroupAvatarAnimation
import dev.ipf.whitenoise.android.ui.common.PublicKeyCopyText
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.abbreviatesPublicKey
import dev.ipf.whitenoise.android.ui.common.isFollowedPerson
import dev.ipf.whitenoise.android.ui.settings.rememberSettingsNativeSurface
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSizes
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

/**
 * The one person (or chat) row of every picker: a segmented list item with the picker avatar, a title, an optional
 * subtitle and a [trailing] slot for a selection indicator, checkbox or action. [index] and [count] place the row in
 * its group so neighbours connect; a lone row keeps the defaults. A row that already sits inside its own card passes
 * `segmented = false` and draws no surface of its own.
 *
 * [isFollowed] adds the followed-person avatar badge and exports the localized `You follow`
 * state once from the row; a row naming its person as [followedAccount] gets both while the
 * active account follows them. [selectionState] remains separate so multi-select pickers can expose
 * selected and followed at the same time without making the badge look like a selection control.
 * [avatarAnimationKey] lets a stored person picture in [avatarImage] animate under the profile policy.
 * [avatarDomain] is the worker domain that decodes [avatarUrl], single-use unless the caller names one: a person
 * row passes [dev.ipf.whitenoise.android.core.AvatarImageLoader.publicProfiles], a group row its presentation's.
 * [publicKey] is the person's complete canonical npub: when the subtitle, or with [publicKeyInTitle] the nameless
 * title, displays that key, its own text copies the whole value while the row keeps its action (#3565).
 * [publicKeyCopyEnabled] defaults to [enabled]; a row that refuses selection can still offer its key for copying,
 * and then the key takes its own keyboard focus, since the disabled row cannot. A row without [onClick] is read-only.
 * [nativeDestination] registers the card as an outlined destination of the settings card morph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun PersonRow(
    title: String,
    subtitle: String?,
    avatarSeed: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    segmented: Boolean = true,
    avatarImage: ImageBitmap? = null,
    avatarAnimationKey: String? = null,
    isFollowed: Boolean = false,
    followedAccount: String? = null,
    selectionState: Boolean? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    publicKey: String? = null,
    publicKeyInTitle: Boolean = false,
    publicKeyCopyEnabled: Boolean = enabled,
    trailing: (@Composable () -> Unit)? = null,
    titleMaxLines: Int = 1,
    groupAnimation: GroupAvatarAnimation? = null,
    secondaryContent: (@Composable () -> Unit)? = null,
    avatarDomain: MediaTrustDomain = MediaTrustDomain.Unique,
    nativeDestination: String? = null,
) {
    val followed = isFollowedPerson(isFollowed, followedAccount)
    val followedStateDescription = if (followed) stringResource(R.string.user_search_you_follow) else null
    // The key takes its own focus stop only when the row offers none, so a selectable row keeps one stop.
    val keyFocusable = publicKeyCopyEnabled && (onClick == null || !enabled)
    val shapes =
        if (segmented) WhiteNoiseListItemDefaults.segmentedShapes(index, count) else WhiteNoiseListItemDefaults.shapes()
    val surface = if (segmented) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent
    val colors = ListItemDefaults.colors(containerColor = surface)
    val rowModifier =
        modifier
            .fillMaxWidth()
            .nativeOutlineDestination(nativeDestination, shapes.shape)
            .let { if (segmented) it.amoledSurfaceBorder(shapes.shape) else it }
            .recipientRelationshipSemantics(followedStateDescription, selectionState)
    val leading: @Composable () -> Unit = {
        Box(Modifier.size(WhiteNoiseSizes.AvatarPicker)) {
            Avatar(
                title = title,
                seed = avatarSeed,
                size = WhiteNoiseSizes.AvatarPicker,
                pictureUrl = avatarUrl?.takeIf { avatarImage == null },
                picture = avatarImage,
                animationKey = avatarAnimationKey,
                groupAnimation = groupAnimation,
                pictureDomain = avatarDomain,
            )
            if (followed) FollowedPersonBadge(Modifier.align(Alignment.BottomEnd))
        }
    }
    val headline: @Composable () -> Unit = {
        if (publicKey != null && publicKeyInTitle) {
            PublicKeyCopyText(
                title,
                publicKey,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = titleMaxLines,
                enabled = publicKeyCopyEnabled,
                keyboardFocusable = keyFocusable,
            )
        } else {
            EmojiLabel(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val supporting: (@Composable () -> Unit)? =
        if (subtitle == null && secondaryContent == null) {
            null
        } else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
                    if (subtitle != null) {
                        ContactRowSubtitle(
                            subtitle,
                            stateOnly = followedStateDescription != null && subtitle == followedStateDescription,
                            publicKey = publicKey?.takeUnless { publicKeyInTitle },
                            enabled = publicKeyCopyEnabled,
                            keyboardFocusable = keyFocusable,
                        )
                    }
                    secondaryContent?.invoke()
                }
            }
        }
    val margin = if (segmented) WhiteNoiseSpacing.CompactScreenMargin else 0.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = margin)) {
        if (onClick == null) {
            Surface(shape = shapes.shape, color = surface) {
                ListItem(
                    headlineContent = headline,
                    supportingContent = supporting,
                    leadingContent = leading,
                    trailingContent = trailing,
                    colors = colors,
                    modifier = rowModifier,
                )
            }
        } else {
            ListItem(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
                shapes = shapes,
                colors = colors,
                modifier =
                    rowModifier.semantics { role = if (selectionState == null) Role.Button else Role.Checkbox },
                leadingContent = leading,
                supportingContent = supporting,
                trailingContent = trailing,
                content = headline,
            )
        }
        // Connected rows are separated by the list gap, not a drawn line.
        if (segmented && index < count - 1) Spacer(Modifier.height(WhiteNoiseListItemDefaults.segmentedGap))
    }
}

/** Where a person's public key is displayed, so that text (and only that text) copies the whole key. */
internal data class PersonKeyPlacement(
    val publicKey: String?,
    val inTitle: Boolean,
    val subtitle: String?,
)

/**
 * Decides whether [title] or [subtitle] is an abbreviation of [npub]. A key title replaces a repeated key subtitle;
 * a name that merely starts with `npub1` stays a name.
 */
internal fun personKeyPlacement(
    title: String,
    subtitle: String?,
    npub: String,
): PersonKeyPlacement {
    val inTitle = abbreviatesPublicKey(title, npub)
    val visibleSubtitle = subtitle?.takeUnless { inTitle && abbreviatesPublicKey(it, npub) }
    val keySubtitle = visibleSubtitle != null && abbreviatesPublicKey(visibleSubtitle, npub)
    return PersonKeyPlacement(
        publicKey = npub.takeIf { inTitle || keySubtitle },
        inTitle = inTitle,
        subtitle = visibleSubtitle,
    )
}

/**
 * Reports an outlined native destination for the card morph between New message, New group and Set up group,
 * or nothing when [destination] is null. The stroke follows the AMOLED surface outline.
 */
@Composable
internal fun Modifier.nativeOutlineDestination(
    destination: String?,
    shape: Shape,
): Modifier =
    if (destination == null) {
        this
    } else {
        then(
            rememberSettingsNativeSurface(
                destination = destination,
                shape = shape,
                strokeColor =
                    if (isAmoledSurfaceTheme()) MaterialTheme.colorScheme.outlineVariant else Color.Unspecified,
            ).first,
        )
    }
