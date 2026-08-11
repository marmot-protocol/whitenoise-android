package dev.ipf.whitenoise.android.updates

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class AppUpdateRepository(
    context: Context,
    private val fetchLatestRelease: suspend (String, String?) -> ZapstoreLatestRelease? =
        ZapstoreReleaseClient()::fetchLatest,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
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
            lastAttemptAtMillis = preferences.getLong(KEY_LAST_ATTEMPT_MS, 0L).takeIf { it > 0L },
            lastAttemptErrorReport = preferences.getString(KEY_LAST_ATTEMPT_ERROR_REPORT, null),
        )

    fun shouldCheck(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val last = preferences.getLong(KEY_LAST_SUCCESSFUL_CHECK_MS, 0L)
        return last <= 0L || nowMillis - last >= CHECK_INTERVAL_MS
    }

    suspend fun refresh(installedVersion: String = installedVersionName()): AppUpdateInfo =
        withContext(Dispatchers.IO) {
            try {
                val release = fetchLatestRelease(AppUpdateConstants.WHITENOISE_ZAPSTORE_APP_ID, installedVersion)
                val checkedAt = currentTimeMillis()
                preferences
                    .edit()
                    .putLong(KEY_LAST_SUCCESSFUL_CHECK_MS, checkedAt)
                    .putLong(KEY_LAST_ATTEMPT_MS, checkedAt)
                    .remove(KEY_LAST_ATTEMPT_ERROR_REPORT)
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
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                recordFailure(error)
                throw error
            }
        }

    fun recordFailure(error: Throwable): String {
        val attemptedAt = currentTimeMillis()
        val report =
            DiagnosticFormatter.errorReport(
                operationCode = "BACKGROUND_UPDATE_CHECK",
                throwable = error,
                context =
                    DiagnosticFormatter.ErrorReportContext(
                        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        occurredAtUtc = Instant.ofEpochMilli(attemptedAt).toString(),
                    ),
            )
        preferences
            .edit()
            .putLong(KEY_LAST_ATTEMPT_MS, attemptedAt)
            .putString(KEY_LAST_ATTEMPT_ERROR_REPORT, report)
            .apply()
        return report
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
        private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
        private const val KEY_LAST_ATTEMPT_ERROR_REPORT = "last_attempt_error_report"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_RELEASES_BEHIND = "releases_behind"
        private const val RELEASES_BEHIND_UNKNOWN = -1
        const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
