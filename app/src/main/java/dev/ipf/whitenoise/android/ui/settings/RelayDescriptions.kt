package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Visible guidance under the URL field: why an onion address cannot be added, else the wss:// hint. */
@StringRes
internal fun addRelayHelpRes(onion: Boolean): Int =
    when {
        onion -> R.string.relay_onion_add_error
        else -> R.string.relay_url_help
    }

/**
 * The short error announcement; an onion address announces "Needs Tor" while its full explanation stays in the
 * visible supporting text, so TalkBack does not read the same sentence twice. Null when the field has no error.
 */
@StringRes
internal fun addRelayErrorRes(
    onion: Boolean,
    duplicateOrRejected: Boolean,
): Int? =
    when {
        onion -> R.string.relay_onion_needs_tor
        duplicateOrRejected -> R.string.relay_unique_url_error
        else -> null
    }

/** Address and, when it is not a usable secure wss:// URL, the note naming its kind of concern. */
@Composable
internal fun relaySummary(relay: AccountRelay): String {
    val note = relay.attention.summaryNoteRes() ?: return relay.url
    return "${relay.url} · ${stringResource(note)}"
}

internal val RelayProgressSize = 20.dp

@StringRes
internal fun AccountRelayRole.explanationRes(): Int =
    when (this) {
        AccountRelayRole.Profile -> R.string.explanation_relays_role_profile
        AccountRelayRole.Inbox -> R.string.explanation_relays_role_inbox
    }
