package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.ProfileKeyFeedbackDialogs

/** The same feedback dialogs as account keys, without exporting or retaining any secret. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroKeyFeedbackPresentation(fixture: MaestroPresentationFixture) {
    var showing by remember { mutableStateOf(true) }
    val kind = fixture.scenario.removePrefix("modal-key-feedback-")

    fun dismissed(expected: String) {
        check(kind == expected || (expected == "native" && kind in setOf("raw", "encrypted")))
        fixture.record("key-$expected-dismiss")
        showing = false
    }
    val nativeError =
        when (kind) {
            "raw" -> R.string.toast_couldnt_export_nsec
            "encrypted" -> R.string.toast_couldnt_create_encrypted_backup
            else -> null
        }
    if (showing) {
        ProfileKeyFeedbackDialogs(
            nativeExportError = nativeError,
            shareErrorDialog = kind == "share",
            saveErrorDialog = kind == "save",
            expiredExportDialog = kind == "expired",
            onNativeErrorDismiss = { dismissed("native") },
            onShareErrorDismiss = { dismissed("share") },
            onSaveErrorDismiss = { dismissed("save") },
            onExpiryDismiss = { dismissed("expired") },
        )
    } else {
        Column {
            Button(onClick = { showing = true }) { Text("Reopen key feedback") }
            Button(onClick = { fixture.finish("done") }) { Text("Finish key feedback check") }
        }
    }
}
