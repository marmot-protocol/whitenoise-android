package dev.ipf.darkmatter.updates

import android.content.Context
import android.content.SharedPreferences
import dev.ipf.darkmatter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppUpdateRepository(
    context: Context,
    private val client: ZapstoreReleaseClient = ZapstoreReleaseClient(),
) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadInfo(installedVersion: String = installedVersionName()): AppUpdateInfo =
        AppUpdateInfo(
            installedVersion = installedVersion,
            latestVersion = preferences.getString(KEY_LATEST_VERSION, null),
            checkedAtMillis = preferences.getLong(KEY_LAST_SUCCESSFUL_CHECK_MS, 0L).takeIf { it > 0L },
            dismissedVersion = preferences.getString(KEY_DISMISSED_VERSION, null),
            releasesBehind = preferences.getInt(KEY_RELEASES_BEHIND, RELEASES_BEHIND_UNKNOWN).takeIf { it >= 0 },
        )

    fun shouldCheck(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val last = preferences.getLong(KEY_LAST_SUCCESSFUL_CHECK_MS, 0L)
        return last <= 0L || nowMillis - last >= CHECK_INTERVAL_MS
    }

    suspend fun refresh(installedVersion: String = installedVersionName()): AppUpdateInfo =
        withContext(Dispatchers.IO) {
            val release = client.fetchLatest(AppUpdateConstants.DARKMATTER_ZAPSTORE_APP_ID, installedVersion)
            preferences
                .edit()
                .putLong(KEY_LAST_SUCCESSFUL_CHECK_MS, System.currentTimeMillis())
                .apply {
                    if (release == null) {
                        remove(KEY_LATEST_VERSION)
                        putInt(KEY_RELEASES_BEHIND, RELEASES_BEHIND_UNKNOWN)
                    } else {
                        putString(KEY_LATEST_VERSION, release.version)
                        putInt(KEY_RELEASES_BEHIND, release.releasesBehind ?: RELEASES_BEHIND_UNKNOWN)
                    }
                }.apply()
            loadInfo(installedVersion)
        }

    fun dismissLatest(installedVersion: String = installedVersionName()): AppUpdateInfo {
        val latest = preferences.getString(KEY_LATEST_VERSION, null) ?: return loadInfo(installedVersion)
        preferences.edit().putString(KEY_DISMISSED_VERSION, latest).apply()
        return loadInfo(installedVersion)
    }

    private fun installedVersionName(): String =
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
        }.getOrDefault(BuildConfig.VERSION_NAME)

    companion object {
        private const val PREFERENCES_NAME = "darkmatter_app_updates"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_LAST_SUCCESSFUL_CHECK_MS = "last_successful_check_ms"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_RELEASES_BEHIND = "releases_behind"
        private const val RELEASES_BEHIND_UNKNOWN = -1
        const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
