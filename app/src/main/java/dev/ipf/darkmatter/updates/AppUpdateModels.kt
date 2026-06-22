package dev.ipf.darkmatter.updates

/** Constants for Dark Matter's Zapstore listing and release metadata. */
object AppUpdateConstants {
    const val DARKMATTER_ZAPSTORE_APP_ID = "org.parres.darkmatter"
    const val ZAPSTORE_LISTING_URL = "https://zapstore.dev/apps/$DARKMATTER_ZAPSTORE_APP_ID"
    const val FAR_BEHIND_RELEASES = 3
}

data class AppUpdateInfo(
    val installedVersion: String,
    val latestVersion: String?,
    val checkedAtMillis: Long?,
    val dismissedVersion: String?,
    val releasesBehind: Int?,
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
            val cmp = l.getOrElse(index) { 0 }.compareTo(r.getOrElse(index) { 0 })
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

    private fun segments(version: String): List<Int> =
        version
            .trim()
            .split('.')
            .filter { it.isNotEmpty() }
            .map { segment -> leadingNumber.find(segment)?.value?.toIntOrNull() ?: 0 }
}
