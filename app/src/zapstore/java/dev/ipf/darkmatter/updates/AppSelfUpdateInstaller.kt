package dev.ipf.darkmatter.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object AppSelfUpdateInstaller {
    fun canRequestPackageInstalls(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun installPermissionSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun launchInstall(
        context: Context,
        apkFile: File,
    ): Boolean {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, AndroidAbi.APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
