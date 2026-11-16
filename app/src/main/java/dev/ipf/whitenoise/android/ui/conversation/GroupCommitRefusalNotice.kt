package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.marmotkit.GroupCommitRefusalCauseFfi
import dev.ipf.marmotkit.GroupCommitRefusedRelayFfi
import dev.ipf.whitenoise.android.R
import java.net.URI
import java.util.Locale

/** What the recovery card shows about relays that refused one of the user's group changes for good. */
internal data class GroupCommitRefusalNoticeModel(
    val refusedRelays: List<GroupCommitRefusedRelayFfi> = emptyList(),
    val dismissing: Boolean = false,
    @param:StringRes val dismissFailure: Int? = null,
)

/** One refusing relay named by its host, or a count of relays, worded for whether every refusal was about size. */
internal sealed interface GroupCommitRefusalMessage {
    /** A single refusing relay, named by its host. */
    data class Named(
        @param:StringRes val resId: Int,
        val host: String,
    ) : GroupCommitRefusalMessage

    /** Several relays, or one whose URL has no readable host, counted. */
    data class Counted(
        @param:PluralsRes val resId: Int,
        val count: Int,
    ) : GroupCommitRefusalMessage
}

/** Says the change was too large only when every relay refused it for size, and names a lone relay by host. */
internal fun groupCommitRefusalMessage(refused: List<GroupCommitRefusedRelayFfi>): GroupCommitRefusalMessage {
    val tooLarge = refused.isNotEmpty() && refused.all { it.cause == GroupCommitRefusalCauseFfi.TOO_LARGE }
    val host = refused.singleOrNull()?.let { relayHost(it.relayUrl) }
    return when {
        host != null && tooLarge -> GroupCommitRefusalMessage.Named(R.string.group_commit_refused_relay_too_large, host)
        host != null -> GroupCommitRefusalMessage.Named(R.string.group_commit_refused_relay, host)
        tooLarge -> GroupCommitRefusalMessage.Counted(R.plurals.group_commit_refused_relays_too_large, refused.size)
        else -> GroupCommitRefusalMessage.Counted(R.plurals.group_commit_refused_relays, refused.size)
    }
}

/** The lowercased host of a relay URL without a trailing dot, or null when it has none. */
private fun relayHost(url: String): String? =
    runCatching { URI(url.trim()).host }
        .getOrNull()
        ?.lowercase(Locale.ROOT)
        ?.trimEnd('.')
        ?.takeIf { it.isNotEmpty() }

/**
 * Tells the user that relays refused one of their changes to this group for good, so members who read only those
 * relays may not get it, with a Dismiss that MDK must accept before the warning leaves and group changes resume.
 */
@Composable
@Suppress("FunctionNaming")
internal fun GroupCommitRefusalNotice(
    refusal: GroupCommitRefusalNoticeModel,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    if (refusal.refusedRelays.isEmpty()) return
    Text(
        stringResource(R.string.group_commit_refused_title),
        style = MaterialTheme.typography.titleSmall.copy(textAlign = TextAlign.Center),
    )
    val message =
        when (val wording = groupCommitRefusalMessage(refusal.refusedRelays)) {
            is GroupCommitRefusalMessage.Named -> stringResource(wording.resId, wording.host)
            is GroupCommitRefusalMessage.Counted -> pluralStringResource(wording.resId, wording.count, wording.count)
        }
    Text(message, style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center))
    refusal.dismissFailure?.takeUnless { refusal.dismissing }?.let { failure ->
        Text(
            stringResource(failure),
            // The dismissal's outcome appears without focus moving, so TalkBack announces it politely.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            color = MaterialTheme.colorScheme.error,
        )
    }
    TextButton(onClick = onDismiss, enabled = !busy && !refusal.dismissing) {
        Text(stringResource(R.string.dismiss))
    }
}
