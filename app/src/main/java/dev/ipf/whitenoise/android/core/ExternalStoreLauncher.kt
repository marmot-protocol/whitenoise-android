package dev.ipf.whitenoise.android.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Package ID of the Zapstore Android app used when opening store listings directly. */
internal const val ZAPSTORE_PACKAGE = "dev.zapstore.app"

/**
 * Builds an `ACTION_VIEW` intent for an external store listing or release page that always starts in its own
 * task (`Intent.FLAG_ACTIVITY_NEW_TASK`), keeping the external app off White Noise's task stack so Recents
 * and launcher launches always resume White Noise. Back navigation inside the launched app remains
 * controlled by that app's own task (for example, Zapstore handles Back internally rather than finishing
 * back to White Noise).
 */
internal fun externalStoreViewIntent(
    url: String,
    packageName: String? = null,
): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        if (packageName != null) setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/**
 * Builds the ordered candidate intents for opening an external store listing: the preferred store app when
 * [preferPackage] is non-null, then the same listing URL in a browser, then any [fallbackUrls] in order.
 * Every candidate carries `Intent.FLAG_ACTIVITY_NEW_TASK`.
 */
internal fun externalStoreListingIntents(
    listingUrl: String,
    preferPackage: String? = ZAPSTORE_PACKAGE,
    fallbackUrls: List<String> = emptyList(),
): List<Intent> {
    val preferred = preferPackage?.let { externalStoreViewIntent(listingUrl, packageName = it) }
    val browser = externalStoreViewIntent(listingUrl)
    val fallbacks = fallbackUrls.map { externalStoreViewIntent(it) }
    return listOfNotNull(preferred, browser) + fallbacks
}

/**
 * Opens an external store listing in its own task so the store or browser never joins White Noise's task stack.
 * Tries [preferPackage] first when non-null, falls back to the same listing URL in a browser, and then tries
 * any [fallbackUrls] in order. Returns `true` as soon as one launch succeeds.
 */
internal fun openExternalStoreListing(
    context: Context,
    listingUrl: String,
    preferPackage: String? = ZAPSTORE_PACKAGE,
    fallbackUrls: List<String> = emptyList(),
    startActivity: (Intent) -> Unit = context::startActivity,
): Boolean {
    val candidates =
        externalStoreListingIntents(
            listingUrl = listingUrl,
            preferPackage = preferPackage,
            fallbackUrls = fallbackUrls,
        )
    return candidates.any { intent -> runCatching { startActivity(intent) }.isSuccess }
}
