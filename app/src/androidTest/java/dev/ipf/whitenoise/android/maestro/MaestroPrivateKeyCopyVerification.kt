package dev.ipf.whitenoise.android.maestro

import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.WindowManager
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Generated raw keys exist only in transient memory; receipts contain no key or clipboard contents. */
internal suspend fun verifyMaestroPrivateKeyCopy(
    activity: MainActivity,
    native: Marmot,
    state: WhiteNoiseAppState,
    baseline: MaestroMessageBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("private-key-copy-") != true) return false
    val original = checkNotNull(baseline)
    val target = if (postcondition == "private-key-copy-peer") original.peer else original.account
    return withTimeout(15_000L) {
        val expected =
            withContext(Dispatchers.IO) {
                val secret = runCatchingCancellable { native.revealNsec(target) }.getOrNull()
                checkNotNull(secret) { "Generated SDK private identity is unavailable" }
            }
        check(expected.matches(Regex("nsec1[023456789acdefghjklmnpqrstuvwxyz]{58}")))
        withTimeout(5_000L) {
            var matched = false
            while (!matched) {
                matched =
                    withContext(Dispatchers.Main.immediate) {
                        verifyMaestroPrivateClip(activity, state, target, expected)
                    }
                if (!matched) delay(100L)
            }
        }
        true
    }
}

/** Called on Main: the real foreground secure window and original owner must bracket clipboard observation. */
private fun verifyMaestroPrivateClip(
    activity: MainActivity,
    state: WhiteNoiseAppState,
    account: String,
    expected: String,
): Boolean {
    check(state.activeAccountRef == account)
    check(activity.hasWindowFocus()) { "Private copy requires actual foreground focus" }
    check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
    val label = activity.getString(R.string.private_key)
    return clearMatchedMaestroPrivateClip(clipboard, label, expected).also {
        check(activity.hasWindowFocus())
    }
}

/** Wait for the actual asynchronous copy, then clear only the matched disposable secret. Never log its value. */
private fun clearMatchedMaestroPrivateClip(
    clipboard: ClipboardManager,
    label: String,
    expected: String,
): Boolean {
    val clip = clipboard.primaryClip
    val item = clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)
    val matched = item?.text?.toString() == expected
    if (matched) {
        checkNotNull(clip)
        checkNotNull(item)
        try {
            check(clip.description.label.toString() == label)
            check(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
            check(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true)
            check(item.uri == null && item.intent == null && item.htmlText == null)
        } finally {
            // A bad metadata verdict must still clean this disposable secret, preserving a replaced clip.
            val current = clipboard.primaryClip
            if (current?.itemCount == 1 && current.getItemAt(0).text?.toString() == expected) {
                clipboard.clearPrimaryClip()
                check(clipboard.primaryClip == null && !clipboard.hasPrimaryClip())
            }
        }
    }
    return matched
}
