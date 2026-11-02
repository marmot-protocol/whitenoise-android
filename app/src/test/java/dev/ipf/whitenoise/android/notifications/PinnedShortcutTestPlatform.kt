package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.IntentSender
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

/** Keeps Android's real pinned flags while scripting approval timing and failed update responses. */
internal class PinnedShortcutTestPlatform(
    private val context: Context,
) : PinnedShortcutPlatform {
    var supported = true
    var accepted = true
    var failNextUpdate = false
    var inventory = emptyList<ShortcutInfoCompat>()
    var afterInventoryRead: (() -> Unit)? = null
    val requests = mutableListOf<ShortcutInfoCompat>()
    val updates = mutableListOf<List<ShortcutInfoCompat>>()
    val disabled = mutableListOf<String>()

    /** Lets each scenario model a launcher without pinning support. */
    override fun supported(): Boolean = supported

    /** Runs the injected inventory-read race hook before returning the fake launcher inventory. */
    override fun shortcuts(): List<ShortcutInfoCompat> = inventory.also { afterInventoryRead?.invoke() }

    /** Captures launcher requests and their approval callback without implicitly approving a pin. */
    override fun request(
        shortcut: ShortcutInfoCompat,
        callback: IntentSender,
    ): Boolean {
        requests += shortcut
        return accepted
    }

    /** Applies icon/label replacements only to existing fake launcher entries. */
    override fun update(shortcuts: List<ShortcutInfoCompat>): Boolean {
        updates += shortcuts
        if (failNextUpdate) {
            failNextUpdate = false
            error("scripted launcher update failure")
        }
        return true
    }

    /** Retains the launcher item while making its revoked identity unavailable to future taps. */
    override fun disable(ids: List<String>) {
        disabled += ids
    }

    /** Only this explicit fixture approval makes the shortcut appear in Android's pinned inventory. */
    fun approve(shortcut: ShortcutInfoCompat) {
        context.getSystemService(ShortcutManager::class.java).requestPinShortcut(shortcut.toShortcutInfo(), null)
        inventory = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
    }
}
