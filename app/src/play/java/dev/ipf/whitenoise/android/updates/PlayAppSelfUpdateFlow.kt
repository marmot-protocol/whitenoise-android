package dev.ipf.whitenoise.android.updates

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Play distribution builds never resolve, download, verify, or install APKs in-app. */
internal class PlayAppSelfUpdateFlow : AppSelfUpdateFlow {
    override val state: AppSelfUpdateState = AppSelfUpdateState.Idle

    override fun start(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) = Unit

    override fun confirmDownload(
        scope: CoroutineScope,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) = Unit

    override fun retry(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) = Unit

    override fun cancel(
        deleteVerifiedApk: Boolean,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) = Unit

    override fun refreshInstallPermission(onStateChanged: (AppSelfUpdateState) -> Unit) = Unit

    override fun launchInstall(
        context: Context,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ): Boolean = false

    override fun openInstallPermissionSettings(context: Context) = Unit

    override fun sweepStaleApks() = Unit
}
