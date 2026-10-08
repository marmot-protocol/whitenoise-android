package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.core.nostr.sha256
import dev.ipf.whitenoise.android.core.nostr.toHex

/**
 * Reading needs the whole body, including sentences past the normal collapse
 * cutoff. Keep revealed rows stable after speech moves on or stops, without
 * changing playback or manual viewport ownership. The saved value contains
 * only a source digest and session id, and survives lazy-row eviction.
 */
@Composable
internal fun rememberTtsMessageBodyExpanded(
    messageIdHex: String,
    sourceText: String?,
    sessionId: Long?,
    verifiedActivePassage: Boolean,
): Boolean {
    var revealedIdentity by rememberSaveable(messageIdHex) { mutableStateOf<String?>(null) }
    val needsIdentity = verifiedActivePassage || revealedIdentity != null
    val sourceDigest =
        remember(sourceText, needsIdentity) {
            sourceText?.takeIf { needsIdentity }?.let { sha256(it.toByteArray()).toHex() }
        }
    val currentIdentity = if (sourceDigest != null && sessionId != null) "$sourceDigest:$sessionId" else null
    val reveal = verifiedActivePassage && currentIdentity != null
    val retained =
        revealedIdentity != null &&
            sourceDigest != null &&
            if (sessionId == null) {
                revealedIdentity?.startsWith("$sourceDigest:") == true
            } else {
                revealedIdentity == currentIdentity
            }
    SideEffect {
        if (reveal) {
            revealedIdentity = currentIdentity
        } else if (!retained) {
            revealedIdentity = null
        }
    }
    return reveal || retained
}
