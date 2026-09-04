package dev.ipf.whitenoise.android.updates

import java.math.BigInteger

/** Constants for White Noise's Zapstore listing and release metadata. */
object AppUpdateConstants {
    const val WHITENOISE_ZAPSTORE_APP_ID = "dev.ipf.whitenoise.android"
    const val ZAPSTORE_LISTING_URL = "https://zapstore.dev/apps/$WHITENOISE_ZAPSTORE_APP_ID"
    const val FAR_BEHIND_RELEASES = 3
}

data class AppUpdateInfo(
    val installedVersion: String,
    val latestVersion: String?,
    val checkedAtMillis: Long?,
    val dismissedVersion: String?,
    val releasesBehind: Int?,
    val lastAttemptAtMillis: Long? = null,
    val lastAttemptErrorReport: String? = null,
) {
    val isUpdateAvailable: Boolean
        get() = latestVersion?.let { CalVer.compare(it, installedVersion) > 0 } ?: false

    val isDismissedForLatest: Boolean
        get() = latestVersion != null && latestVersion == dismissedVersion

    val isFarBehind: Boolean
        get() = (releasesBehind ?: 0) >= AppUpdateConstants.FAR_BEHIND_RELEASES

    val shouldShowBanner: Boolean
        get() = isUpdateAvailable && (isFarBehind || !isDismissedForLatest)
}

data class ZapstoreLatestRelease(
    val version: String,
    val releasesBehind: Int?,
)

internal fun shouldPostAppUpdateNotification(
    info: AppUpdateInfo,
    notifyIfNewer: Boolean,
    appInForeground: Boolean,
): Boolean = notifyIfNewer && !appInForeground && info.shouldShowBanner

/** CalVer segment comparison for Zapstore version strings such as `2026.6.20`. */
object CalVer {
    private val leadingNumber = Regex("^\\d+")

    fun compare(
        left: String,
        right: String,
    ): Int {
        val l = segments(left)
        val r = segments(right)
        val width = maxOf(l.size, r.size)
        for (index in 0 until width) {
            val cmp = l.getOrElse(index) { BigInteger.ZERO }.compareTo(r.getOrElse(index) { BigInteger.ZERO })
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun releasesBehind(
        installedVersion: String,
        releaseVersions: Collection<String>,
    ): Int =
        releaseVersions
            .asSequence()
            .distinct()
            .count { compare(it, installedVersion) > 0 }

    private fun segments(version: String): List<BigInteger> =
        version
            .trim()
            .split('.')
            .filter { it.isNotEmpty() }
            .map { segment -> leadingNumber.find(segment)?.value?.let(::BigInteger) ?: BigInteger.ZERO }
}
