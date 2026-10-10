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
    val asset = ZapstoreApkAsset("a".repeat(64), "test", "2099.1.2", "b".repeat(64), "https://example.invalid/test.apk", 100, emptySet())
    val state =
        when (fixture.scenario) {
            "update-resolving" -> AppSelfUpdateState.Resolving
            "update-confirm" -> AppSelfUpdateState.Confirming(asset)
            "update-download", "update-complete-bytes" ->
                AppSelfUpdateState.Downloading(asset, if (fixture.scenario == "update-complete-bytes") 100 else 25, 100)
            "update-unknown-total" -> AppSelfUpdateState.Downloading(asset, 25, null)
            "update-verifying" -> AppSelfUpdateState.Verifying(asset)
            "update-verified" -> AppSelfUpdateState.Verified(asset, File("never-created.apk"))
            "update-permission" -> AppSelfUpdateState.PermissionRequired(asset, File("never-created.apk"))
            "update-retryable" -> AppSelfUpdateState.Error(R.string.app_self_update_hash_mismatch, true)
            "update-terminal" -> AppSelfUpdateState.Error(R.string.app_self_update_hash_mismatch, false)
            else -> error("Unknown presentation update phase")
        }
    AppSelfUpdateContent(
        state = state,
        selfUpdateEnabled = true,
        onCancel = { fixture.finish("cancel") },
        onDownload = { fixture.finish("download") },
        onInstall = { fixture.finish("install-handoff") },
        onOpenSettings = { fixture.finish("settings-handoff") },
        onRetry = { fixture.finish("retry") },
    )
}
