package dev.ipf.darkmatter.updates

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Flavor-neutral self-update controller API used by [dev.ipf.darkmatter.state.DarkMatterAppState]. */
interface AppSelfUpdateFlow {
    val state: AppSelfUpdateState

    fun start(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    )

    fun confirmDownload(
        scope: CoroutineScope,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    )

    fun retry(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    )

    fun cancel(
        deleteVerifiedApk: Boolean,
        onStateChanged: ((AppSelfUpdateState) -> Unit)? = null,
    )

    fun refreshInstallPermission(onStateChanged: (AppSelfUpdateState) -> Unit)

    suspend fun launchInstall(
        context: Context,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ): Boolean

    fun openInstallPermissionSettings(context: Context)

    fun sweepStaleApks()

    /** Cancel active resolve/download when the app leaves the foreground. */
    fun onBackground(onStateChanged: (AppSelfUpdateState) -> Unit)
}
