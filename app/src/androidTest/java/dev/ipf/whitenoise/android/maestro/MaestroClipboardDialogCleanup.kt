package dev.ipf.whitenoise.android.maestro

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.TextView
import dev.ipf.whitenoise.android.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Verify ordinary copy first, then exercise the exact failed-journey cleanup condition on this emulator. */
internal suspend fun verifyMaestroPresentation(
    presentation: MaestroPresentationFixture?,
    activity: MainActivity,
): Any {
    val result = withContext(Dispatchers.Main.immediate) { presentation?.verify() ?: JSONObject.NULL }
    if (result is JSONObject && result.optBoolean("clipboardVerified")) {
        verifyMaestroDialogClipboardCleanup(activity)
        result.put("clipboardDialogCleanupVerified", true)
    }
    return result
}

/** An app-owned dialog steals Activity focus; successful clearing must not require that focus. */
private suspend fun verifyMaestroDialogClipboardCleanup(activity: MainActivity) =
    withContext(Dispatchers.Main.immediate) {
        val manager = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
        val expected = "Disposable cleanup boundary payload"
        val clipboard = MaestroPresentationClipboard(activity, expected)
        val dialog = Dialog(activity)
        try {
            manager.setPrimaryClip(ClipData.newPlainText("Fixture", expected))
            dialog.setContentView(TextView(activity).apply { text = "Disposable clipboard cleanup boundary" })
            dialog.show()
            withTimeout(3_000L) {
                while (dialog.window?.decorView?.hasWindowFocus() != true) delay(16L)
            }
            check(!activity.hasWindowFocus()) { "Dialog did not acquire the failed-journey focus boundary" }
            clipboard.close()
            check(clipboard.cleared && !clipboard.verified) { "Cleanup was confused with Copy proof" }
            dialog.dismiss()
            withTimeout(3_000L) { while (!activity.hasWindowFocus()) delay(16L) }
            check(manager.primaryClip == null && !manager.hasPrimaryClip()) {
                "Failed-journey payload survived cleanup"
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                try {
                    clipboard.close()
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }
