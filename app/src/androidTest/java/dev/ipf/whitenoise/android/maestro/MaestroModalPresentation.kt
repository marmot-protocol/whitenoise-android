package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.qr.ScannerErrorDialog
import dev.ipf.whitenoise.android.ui.settings.AuditLogExportConsentDialog
import dev.ipf.whitenoise.android.ui.settings.AuditLogExportDestinationDialog
import dev.ipf.whitenoise.android.ui.settings.CustomEmojiNameDialog
import dev.ipf.whitenoise.android.ui.settings.EncryptedBackupResultDialog
import dev.ipf.whitenoise.android.ui.share.ShareImportErrorDialog

/** Existing dialog controls consume bounded state; no camera, key export, emoji save or diagnostic upload runs. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroModalPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("extra-modal-backup-") -> BackupPresentation(fixture)
        fixture.scenario.startsWith("extra-modal-emoji-") -> EmojiNamePresentation(fixture)
        fixture.scenario.startsWith("extra-modal-scanner-") ->
            ScannerErrorDialog(
                detail = "Fixture camera binding failure",
                onDismiss = { fixture.finish("dismiss") },
                onRetry = { fixture.finish("retry-handoff") },
            )
        fixture.scenario.startsWith("extra-modal-audit-consent-") ->
            AuditLogExportConsentDialog(
                onDismiss = { fixture.finish("dismiss") },
                onConfirm = { fixture.finish("consent-handoff") },
            )
        fixture.scenario.startsWith("extra-modal-audit-destination-") ->
            AuditLogExportDestinationDialog(
                onDismiss = { fixture.finish("dismiss") },
                onSave = { fixture.finish("save-handoff") },
                onShare = { fixture.finish("share-handoff") },
            )
        else -> ShareFailurePresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun BackupPresentation(fixture: MaestroPresentationFixture) {
    var copied by remember(fixture) { mutableStateOf(false) }
    EncryptedBackupResultDialog(
        backup = "ncryptsec1fixtureencryptedresult",
        copied = copied,
        onCopy = {
            fixture.record("copy-handoff")
            copied = true
        },
        onExport = { fixture.finish("export-handoff") },
        onHide = { fixture.finish("dismiss") },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun EmojiNamePresentation(fixture: MaestroPresentationFixture) {
    CustomEmojiNameDialog(
        preview = fixture.image,
        suggestedCode = if (fixture.scenario.endsWith("empty")) "" else "fixture",
        existing = CustomEmojiSet.Empty,
        onDismiss = { fixture.finish("dismiss") },
        onSave = {
            check(it == "fixture")
            fixture.finish("save-handoff")
        },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun ShareFailurePresentation(fixture: MaestroPresentationFixture) {
    val content = fixture.scenario.endsWith("partial")
    ShareImportErrorDialog(
        payload =
            SharePayload(
                text = if (content) "Fixture retained caption" else null,
                streamUris = emptyList(),
                intentMimeType = "text/plain",
                importReady = true,
                importErrors = listOf(ShareImportError.Unreadable),
                importRejectedCount = 1,
            ),
        hasContent = content,
        onCancel = { fixture.finish("dismiss") },
        onContinue = { fixture.finish("continue-handoff") },
    )
}
