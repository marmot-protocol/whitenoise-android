package dev.ipf.whitenoise.android.ui.conversation.stickers

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerRefFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.StickerAssetCache
import dev.ipf.whitenoise.android.state.StickerKey
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

internal val StickerTileShape = RoundedCornerShape(16.dp)

private enum class StickerPhase { Idle, Loading, WaitingForTap, Failed }

/**
 * One sticker image. Loads by itself only when [loadsAutomatically]; when false a
 * tap loads it, since loading contacts the pack author's server, and while null it
 * shows an empty frame. [onClick] applies once the image is shown, so a sticker that
 * finishes loading becomes tappable.
 */
@Suppress("FunctionNaming")
@Composable
internal fun StickerImage(
    key: StickerKey,
    loadsAutomatically: Boolean?,
    cache: StickerAssetCache<ImageBitmap>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var loaded by remember(key) { mutableStateOf(cache.cached(key)) }
    var phase by remember(key) { mutableStateOf(StickerPhase.Idle) }
    var explicitRequests by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, loadsAutomatically, explicitRequests) {
        if (loaded != null) return@LaunchedEffect
        if (loadsAutomatically == null && explicitRequests == 0) {
            phase = StickerPhase.Idle
            return@LaunchedEffect
        }
        val explicit = explicitRequests > 0
        phase =
            when {
                loadsAutomatically == false && !explicit -> StickerPhase.WaitingForTap
                cache.hasFailed(key) && !explicit -> StickerPhase.Failed
                else -> StickerPhase.Loading
            }
        if (phase != StickerPhase.Loading) return@LaunchedEffect
        val result = if (explicit) cache.retry(key) else cache.image(key)
        loaded = result
        phase = if (result == null) StickerPhase.Failed else StickerPhase.Idle
    }
    val label = stickerAccessibilityLabel(loaded?.sticker)
    val current = loaded
    if (current != null) {
        Image(
            bitmap = current.image,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier =
                if (onClick != null) {
                    modifier
                        .clip(StickerTileShape)
                        .clickable(onClickLabel = stringResource(R.string.send), role = Role.Button, onClick = onClick)
                } else {
                    modifier
                },
        )
    } else {
        StickerPlaceholder(phase, label, onTap = { explicitRequests++ }, modifier = modifier)
    }
}

/** A sticker that isn't shown yet: loading, waiting for a tap, or failed and waiting for a retry. */
@Suppress("FunctionNaming")
@Composable
private fun StickerPlaceholder(
    phase: StickerPhase,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val failed = phase == StickerPhase.Failed
    val tappable = phase == StickerPhase.WaitingForTap || failed
    val loadHint = stringResource(R.string.sticker_load_hint)
    Box(
        modifier
            .clip(StickerTileShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, StickerTileShape)
            .then(if (tappable) Modifier.clickable(onClickLabel = loadHint, onClick = onTap) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (tappable) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(WhiteNoiseSpacing.Related),
            ) {
                Icon(
                    painterResource(if (failed) R.drawable.ic_warning else R.drawable.ic_emoji_smileys),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(if (failed) R.string.sticker_tap_to_retry else R.string.sticker_tap_to_load),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (phase == StickerPhase.Loading) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

/** Alt text first, then the pack's emoji, then the generic name. */
@Composable
private fun stickerAccessibilityLabel(sticker: StickerFfi?): String {
    val alt = StickerPresentation.accessibleAlt(sticker?.alt)
    val emoji = StickerPresentation.labelEmoji(sticker?.emoji)
    return when {
        alt != null -> stringResource(R.string.sticker_alt, alt)
        emoji != null -> stringResource(R.string.sticker_with_emoji, emoji)
        else -> stringResource(R.string.sticker)
    }
}

/** A sticker in the timeline, sized like iOS and loaded per [StickerPresentation.loadsAutomatically]. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerMessageContent(
    ref: StickerRefFfi,
    mine: Boolean,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    modifier: Modifier = Modifier,
) {
    val key = remember(ref) { StickerKey.from(ref) }
    val library = controller.stickerLibrary
    LaunchedEffect(library) { library.loadInstalledIfNeeded() }
    val packInstalled = library.isInstalled(key.packCoordinate).takeIf { library.hasTriedInstalled }
    StickerImage(
        key = key,
        loadsAutomatically =
            StickerPresentation.loadsAutomatically(
                isFromMe = mine,
                packInstalled = packInstalled,
                autoDownloadImages = appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image),
            ),
        cache = controller.stickerAssets,
        modifier = modifier.size(StickerMetrics.MESSAGE_SIDE_DP.dp).testTag("message.sticker"),
    )
}
