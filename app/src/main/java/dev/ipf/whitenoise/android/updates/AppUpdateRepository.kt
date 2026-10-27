package dev.ipf.whitenoise.android.updates

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class AppUpdateRepository(
    context: Context,
    private val fetchLatestRelease: suspend (String, String?) -> ZapstoreLatestRelease? =
        ZapstoreReleaseClient()::fetchLatest,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    private val selfUpdateEnabled: Boolean = BuildConfig.SELF_UPDATE_ENABLED,
    private val automaticNetworkAllowed: () -> Boolean = { automaticUpdateNetworkAllowed(context.applicationContext) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    /** Automatic checks wait 24 hours after success and 15 minutes after a failed attempt. */
    fun shouldCheck(nowMillis: Long = currentTimeMillis()): Boolean {
        val lastSuccess = preferences.getLong(KEY_LAST_SUCCESSFUL_CHECK_MS, 0L)
        val lastFailure =
            if (preferences.contains(KEY_LAST_ATTEMPT_ERROR_REPORT)) {
                preferences.getLong(KEY_LAST_ATTEMPT_MS, 0L)
            } else {
                0L
            }
        return updateIntervalElapsed(nowMillis, lastSuccess, CHECK_INTERVAL_MS) &&
            updateIntervalElapsed(nowMillis, lastFailure, FAILURE_COOLDOWN_MS)
    }

    /** Returns null when distribution, connectivity, Data Saver or persisted timing denies automatic work. */
    suspend fun refreshAutomatically(installedVersion: String = installedVersionName()): AppUpdateInfo? =
        refreshAdmitted(installedVersion, automatic = true)

    /**
     * Explicit Check now bypasses automatic timing and Data Saver admission, but never distribution policy.
     * It joins an active check rather than starting a second fetch; a later tap may retry immediately.
     */
    suspend fun refresh(installedVersion: String = installedVersionName()): AppUpdateInfo =
        refreshAdmitted(installedVersion, automatic = false) ?: loadInfo(installedVersion)

    /** Applies policy before joining the process-wide flight; the owner persists the outcome exactly once. */
    private suspend fun refreshAdmitted(
        installedVersion: String,
        automatic: Boolean,
    ): AppUpdateInfo? =
        withContext(ioDispatcher) {
            if (!selfUpdateEnabled || (automatic && !automaticNetworkAllowed())) return@withContext null
            val checked =
                AppUpdateAdmission.run(shouldStart = { !automatic || shouldCheck() }) {
                    fetchAndPersist(installedVersion)
                }
            if (checked) loadInfo(installedVersion) else null
        }

    /** Records completion, leaving cancellation unrecorded so a stopped owner cannot poison retries. */
    @Suppress("TooGenericExceptionCaught") // Repository boundary records every non-cancellation fetch failure.
    private suspend fun fetchAndPersist(installedVersion: String) {
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
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            recordFailure(error)
            throw error
        }
    }

    /** Persists only the bounded, privacy-safe failure report and its retry timestamp. */
    private fun recordFailure(error: Throwable): String {
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
        const val FAILURE_COOLDOWN_MS: Long = 15L * 60L * 1000L
        const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
