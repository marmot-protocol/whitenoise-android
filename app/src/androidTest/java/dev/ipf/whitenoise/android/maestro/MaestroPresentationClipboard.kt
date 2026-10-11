package dev.ipf.whitenoise.android.maestro

import android.content.ClipboardManager
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Disposable-emulator copy proof rejects an earlier journey's payload and owns its cleanup. */
internal class MaestroPresentationClipboard(
    private val activity: MainActivity,
    private val expected: String,
) {
    private val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
    var verified = false
        private set
    var cleared = false
        private set

    init {
        clipboard.clearPrimaryClip()
        check(clipboard.primaryClip == null && !clipboard.hasPrimaryClip()) {
            "Presentation clipboard baseline was not cleared"
        }
    }

    fun verifyAndClear() {
        check(activity.hasWindowFocus()) { "Presentation clipboard proof requires application focus" }
        val clip = checkNotNull(clipboard.primaryClip) { "Presentation Copy produced no clipboard payload" }
        check(clip.itemCount == 1) { "Unexpected presentation clipboard item count" }
        val item = clip.getItemAt(0)
        check(item.text?.toString() == expected && item.uri == null && item.intent == null && item.htmlText == null) {
            "Presentation Copy did not produce the expected plain-text payload"
        }
        verified = true
        clear()
    }

    /** Before closing the foreground Activity, clear only the fixture's matching payload. */
    fun close() {
        if (cleared) return
        check(activity.hasWindowFocus()) { "Presentation clipboard cleanup requires application focus" }
        val clip = clipboard.primaryClip
        check(clip == null || (clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == expected)) {
            "Presentation clipboard ownership changed before cleanup"
        }
        clear()
    }

    private fun clear() {
        clipboard.clearPrimaryClip()
        check(activity.hasWindowFocus() && clipboard.primaryClip == null && !clipboard.hasPrimaryClip()) {
            "Presentation clipboard cleanup was not verified"
        }
        cleared = true
    }
}

internal fun maestroPresentationClipboard(
    scenario: String,
    activity: MainActivity,
    state: WhiteNoiseAppState,
): MaestroPresentationClipboard? {
    val expected =
        when {
            scenario.startsWith("feedback-") && scenario.endsWith("copyable") -> "Synthetic diagnostic report"
            scenario == "text-dialog-copy" -> "Fixture decoded first line\nFixture decoded last line"
            scenario == "surface-profile-qr-copy" ->
                state.npubForDisplay(
                    checkNotNull(state.activeAccount).accountIdHex,
                )
            else -> null
        }
    return expected?.let { MaestroPresentationClipboard(activity, it) }
}
