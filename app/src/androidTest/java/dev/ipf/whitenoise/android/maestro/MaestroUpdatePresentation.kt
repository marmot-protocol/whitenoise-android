package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.AppSelfUpdateContent
import dev.ipf.whitenoise.android.updates.AppSelfUpdateState
import dev.ipf.whitenoise.android.updates.ZapstoreApkAsset
import java.io.File

/** Exercises real update chrome and dispatch; synthetic assets are never downloaded or installed. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroUpdatePresentation(fixture: MaestroPresentationFixture) {
    val asset =
        ZapstoreApkAsset(
            "a".repeat(64),
            "test",
            "2099.1.2",
            "b".repeat(64),
            "https://example.invalid/test.apk",
            100,
            emptySet(),
        )
    val reminder = fixture.scenario.startsWith("update-reminder-")
    val phase =
        if (reminder) fixture.scenario.removePrefix("update-reminder-") else fixture.scenario.removePrefix("update-")
    val state = maestroUpdateState(phase, asset)
    AppSelfUpdateContent(
        state = state,
        selfUpdateEnabled = phase != "disabled",
        canRemindLater = reminder,
        onCancel = { fixture.finish("cancel") },
        onDownload = { fixture.finish("download") },
        onInstall = { fixture.finish("install-handoff") },
        onOpenSettings = { fixture.finish("settings-handoff") },
        onRetry = { fixture.finish("retry") },
        onRemindLater = { fixture.finish("remind-later") },
    )
}

/** Injects actual display phases while preventing all external updater operations. */
private fun maestroUpdateState(
    phase: String,
    asset: ZapstoreApkAsset,
): AppSelfUpdateState =
    when (phase) {
        "idle" -> AppSelfUpdateState.Idle
        "disabled" -> AppSelfUpdateState.Confirming(asset)
        "resolving" -> AppSelfUpdateState.Resolving
        "confirm", "confirm-cancel" -> AppSelfUpdateState.Confirming(asset)
        "download", "complete-bytes" ->
            AppSelfUpdateState.Downloading(asset, if (phase == "complete-bytes") 100 else 25, 100)
        "unknown-total" -> AppSelfUpdateState.Downloading(asset, 25, null)
        "verifying" -> AppSelfUpdateState.Verifying(asset)
        "verified" -> AppSelfUpdateState.Verified(asset, File("never-created.apk"))
        "permission" -> AppSelfUpdateState.PermissionRequired(asset, File("never-created.apk"))
        "retryable" -> AppSelfUpdateState.Error(R.string.app_self_update_hash_mismatch, true)
        "terminal" -> AppSelfUpdateState.Error(R.string.app_self_update_hash_mismatch, false)
        else -> error("Unknown presentation update phase")
    }
