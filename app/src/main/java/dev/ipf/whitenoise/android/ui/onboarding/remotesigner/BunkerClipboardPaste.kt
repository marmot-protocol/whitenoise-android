package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard

/** Identifies a provider-backed bunker link that the user pasted from one Android clipboard copy. */
internal class BunkerClipboardPaste private constructor(
    private val link: String,
    private val uri: Uri,
    private val timestamp: Long,
) {
    /** Checks that the proof still belongs to the edited or submitted value. */
    fun matchesLink(value: String): Boolean = link == value.trim()

    /** Matches the original provider clip without reopening its stream. */
    fun matches(
        clip: ClipData,
        spent: String,
    ): Boolean =
        matchesLink(spent) &&
            clip.description.timestamp == timestamp &&
            clip.itemCount == 1 &&
            clip.getItemAt(0).let { it.text == null && it.uri == uri }

    companion object {
        /** Records the clip identity after an explicit paste has already read [value]. */
        fun capture(
            clip: ClipData,
            value: String,
        ): BunkerClipboardPaste? {
            val timestamp = clip.description.timestamp
            if (clip.itemCount != 1 || timestamp <= 0 || !isBunkerLink(value)) return null
            val item = clip.getItemAt(0)
            return item.uri
                ?.takeIf { item.text == null && it.scheme == ContentResolver.SCHEME_CONTENT }
                ?.let { BunkerClipboardPaste(value.trim(), it, timestamp) }
        }
    }
}

/** Retires a matching spent link using clipboard fields only, before the signed-in UI changes. */
internal fun clearClipboardIfHolding(
    context: Context,
    used: String,
    paste: BunkerClipboardPaste? = null,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    runCatching {
        val clip = clipboard.primaryClip?.takeIf { it.itemCount == 1 } ?: return@runCatching
        val item = clip.getItemAt(0)
        val inline = item.text?.toString() ?: item.uri?.toString()?.takeIf(::isBunkerLink)
        if (inline?.trim() == used || paste?.matches(clip, used) == true) clearSensitiveClipboard(context)
    }
}
