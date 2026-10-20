package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import dev.ipf.whitenoise.android.ui.qr.ScannerErrorDialog
import dev.ipf.whitenoise.android.ui.settings.AuditLogExportConsentDialog
import dev.ipf.whitenoise.android.ui.settings.AuditLogExportDestinationDialog
import dev.ipf.whitenoise.android.ui.settings.ConfirmEmojiRemoval
import dev.ipf.whitenoise.android.ui.settings.CustomEmojiNameDialog
import dev.ipf.whitenoise.android.ui.settings.EncryptedBackupResultDialog
import dev.ipf.whitenoise.android.ui.settings.StagedEmoji
import dev.ipf.whitenoise.android.ui.settings.StagedEmojiDialog
import dev.ipf.whitenoise.android.ui.share.ShareImportStatus

/** Production dialogs consume bounded state; emoji saves use only a cleaned, task-owned local store. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroModalPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("extra-modal-backup-") -> BackupPresentation(fixture)
        fixture.scenario.startsWith("extra-modal-emoji-wrapper-") -> EmojiWrapperPresentation(fixture)
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
    val request =
        remember(fixture) {
            ShareRequest(
                payload =
                    SharePayload(
                        text = if (content) "Fixture retained caption" else null,
                        streamUris = emptyList(),
                        intentMimeType = "text/plain",
                        importReady = true,
                        importErrors = listOf(ShareImportError.Unreadable),
                        importRejectedCount = 1,
                    ),
                shortcutId = null,
                requestId = "fixture-import-error",
            )
        }
    ShareImportStatus(
        importing = false,
        progress = null,
        request = request,
        onCancel = { fixture.finish("dismiss") },
    ) { allowed ->
        LaunchedEffect(allowed) {
            check(content && allowed) { "Import errors must be acknowledged before showing share content" }
            fixture.finish("continue-handoff")
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun EmojiWrapperPresentation(fixture: MaestroPresentationFixture) {
    val directory = checkNotNull(fixture.emojiDirectory)
    val store = remember(fixture) { CustomEmojiStore(directory) }
    val bytes = remember(fixture) { fixture.emojiBytes }
    val removal = fixture.scenario.contains("remove")
    if (removal) {
        val file = remember(fixture) { java.io.File(directory, "fixture.png").apply { writeBytes(bytes) } }
        val emoji = remember(fixture) { CustomEmoji(":fixture:", file, fixture.image) }
        ConfirmEmojiRemoval(
            emoji = emoji,
            onDismiss = {
                check(file.readBytes().contentEquals(bytes))
                fixture.finish("cancel-retained")
            },
            onConfirm = {
                check(file.delete() && !file.exists())
                fixture.finish("remove-dispatched")
            },
        )
    } else {
        val pending = remember(fixture) { StagedEmoji(bytes, fixture.image, "fixture", "fixture-owner") }
        var cleared by remember { mutableStateOf(false) }
        val save = fixture.scenario.endsWith("save")
        StagedEmojiDialog(fixture.appState, store, pending.takeUnless { cleared }) {
            check(!cleared)
            cleared = true
            if (!save) {
                check(directory.listFiles().orEmpty().isEmpty())
                fixture.finish("dismiss-no-save")
            }
        }
        val saved = store.emoji[":fixture:"]
        LaunchedEffect(saved) {
            if (saved != null) {
                check(save && cleared && saved.shortcode == ":fixture:")
                check(saved.file.readBytes().contentEquals(bytes))
                check(store.emoji.entries.size == 1)
                fixture.finish("save-persisted")
            }
        }
    }
}
