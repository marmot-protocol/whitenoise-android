package dev.ipf.whitenoise.android.maestro

import android.content.ClipDescription
import android.content.ClipboardManager
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Compare actual Android clipboard contents to the original SDK identity, never an acknowledged UI glyph. */
internal suspend fun verifyMaestroPublicKeyCopy(
    activity: MainActivity,
    native: Marmot,
    state: WhiteNoiseAppState,
    baseline: MaestroMessageBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("public-key-copy-") != true) return false
    val before = checkNotNull(baseline)
    val target = if (postcondition == "public-key-copy-peer") before.peer else before.account
    val expected =
        withContext(Dispatchers.IO) {
            val account = native.listAccounts().single { it.label == target }
            native.npub(account.accountIdHex)
        }
    check(expected.matches(Regex("npub1[023456789acdefghjklmnpqrstuvwxyz]{58}")))
    return withTimeout(5_000L) {
        withContext(Dispatchers.Main.immediate) {
            check(activity.hasWindowFocus()) { "Clipboard proof requires actual application focus" }
            check(state.activeAccountRef == before.account)
            val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
            val clip = checkNotNull(clipboard.primaryClip) { "Actual public-key copy is missing" }
            check(clip.itemCount == 1)
            check(clip.description.label.toString() == activity.getString(R.string.public_key))
            check(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
            check(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) != true)
            val item = clip.getItemAt(0)
            check(item.text?.toString() == expected)
            check(item.uri == null && item.intent == null && item.htmlText == null)
            // Clear only the just-verified disposable public identity while the fixture still has focus.
            clipboard.clearPrimaryClip()
            check(activity.hasWindowFocus())
            check(clipboard.primaryClip == null && !clipboard.hasPrimaryClip())
            true
        }
    }
}
