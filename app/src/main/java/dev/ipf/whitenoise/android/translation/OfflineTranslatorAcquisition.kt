@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.ZAPSTORE_PACKAGE

/**
 * The publisher's Zapstore app identity: kind 32267 app `dev.davidv.translator`, signed by the publisher key
 * `8eba32d745cf422b8470d44ea644c2b1213f6e6bf11f383c502c815adb9a588c` named in upstream `zapstore.yaml`, with
 * the relay.zapstore.dev hint. Unlike a package-name address, it pins the publisher as well as the app.
 */
internal const val OFFLINE_TRANSLATOR_ZAPSTORE_URL =
    "https://zapstore.dev/apps/naddr1qq2kgetk9ejxzanfv3mzuarjv9h8xmrpw3hhyqgcwaehxw309aex2mrp0yh85ctswd6x7un99" +
        "ejx2aszyz8t5vkhgh85y2uywr2yafjyc2cjz0mwd0c37wpu2qkgzkkmnfvgcqcyqqq8uzcdudkdh"

/** The publisher's GitHub release page for the newest reviewed engine, the compatible fallback route. */
internal val offlineTranslatorReleaseUrl: String =
    "https://github.com/DavidVentura/offline-translator/releases/tag/v" +
        OfflineTranslatorLanguageCatalog.engineVersions.last()

/** A store's offered engine release as last reviewed: its version name and APK signing-certificate SHA-256. */
internal data class ReviewedStoreRelease(
    val versionName: String,
    val signer: String,
)

/**
 * The newest release the publisher's Zapstore listing offered when last reviewed. White Noise never queries a
 * store at runtime, so change this only in a reviewed edit after re-reading the listing. Read 2026-10-15 from
 * wss://relay.zapstore.dev: release event 1b4d0f7f…57bb0 and asset event 33174333…78a53, version 0.8.2 (code
 * 272) with `apk_certificate_hash` equal to the pinned publisher signer. That version predates the reviewed
 * Binder contract, so the GitHub release stays the route until the publisher ships a newer one there.
 */
internal val offlineTranslatorZapstoreRelease =
    ReviewedStoreRelease(
        versionName = "0.8.2",
        signer = "2b3806e745d809018a51be58d0635ffc74cc9733439407ab1ed0424a4db3e1fb",
    )

/** Where someone without a usable engine is sent to get one. */
internal enum class OfflineTranslatorRoute { ZAPSTORE, GITHUB_RELEASE }

/**
 * Zapstore is preferred only when its reviewed release would pass discovery: the pinned publisher signer
 * and a release whose catalog and Binder contract were reviewed. Otherwise the publisher's GitHub release is.
 */
internal fun offlineTranslatorRoute(zapstore: ReviewedStoreRelease = offlineTranslatorZapstoreRelease) =
    if (
        zapstore.signer == OFFLINE_TRANSLATOR_SIGNER &&
        zapstore.versionName in OfflineTranslatorLanguageCatalog.engineVersions
    ) {
        OfflineTranslatorRoute.ZAPSTORE
    } else {
        OfflineTranslatorRoute.GITHUB_RELEASE
    }

/** Whether a launchable engine is installed, whatever its version; the manifest declares its package visible. */
internal fun offlineTranslatorInstalled(context: Context): Boolean =
    runCatching { context.packageManager.getLaunchIntentForPackage(OFFLINE_TRANSLATOR_PACKAGE) != null }
        .getOrDefault(false)

/**
 * Opens the installed engine for model management, even a version White Noise cannot translate with.
 * Otherwise it opens [route]: the Zapstore app, then the same listing in a browser, then the GitHub release.
 * Every intent names only a package or a public page, never message text or translation arguments. Returns
 * false when no app could open any of them.
 */
internal fun openOfflineTranslator(
    context: Context,
    route: OfflineTranslatorRoute = offlineTranslatorRoute(),
    startActivity: (Intent) -> Unit = { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
): Boolean {
    val launch = runCatching { context.packageManager.getLaunchIntentForPackage(OFFLINE_TRANSLATOR_PACKAGE) }
    val zapstore = Uri.parse(OFFLINE_TRANSLATOR_ZAPSTORE_URL)
    val candidates =
        listOfNotNull(
            launch.getOrNull(),
            Intent(Intent.ACTION_VIEW, zapstore).setPackage(ZAPSTORE_PACKAGE).takeIf {
                route == OfflineTranslatorRoute.ZAPSTORE
            },
            Intent(Intent.ACTION_VIEW, zapstore).takeIf { route == OfflineTranslatorRoute.ZAPSTORE },
            Intent(Intent.ACTION_VIEW, Uri.parse(offlineTranslatorReleaseUrl)),
        )
    return candidates.any { intent -> runCatching { startActivity(intent) }.isSuccess }
}

/**
 * The one engine control shared by global settings, chat settings and message recovery. An installed engine
 * opens for language packs; otherwise the label names the reviewed acquisition route, and the GitHub fallback
 * says why Zapstore is not offered. Installation state refreshes whenever the screen resumes.
 */
@Composable
internal fun OfflineTranslatorEngineAction(
    onOpen: () -> Boolean,
    modifier: Modifier = Modifier,
    installed: (Context) -> Boolean = ::offlineTranslatorInstalled,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var openFailed by remember { mutableStateOf(false) }
    val engineInstalled = remember(context, refreshToken, installed) { installed(context) }
    val route = offlineTranslatorRoute()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshToken++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val label =
        when {
            engineInstalled -> R.string.translation_open_engine
            route == OfflineTranslatorRoute.ZAPSTORE -> R.string.translation_install_zapstore
            else -> R.string.translation_install_release
        }
    Column(modifier) {
        TextButton(onClick = { openFailed = !onOpen() }, modifier = Modifier.testTag("translation.engine")) {
            Text(stringResource(label))
        }
        if (!engineInstalled && route == OfflineTranslatorRoute.GITHUB_RELEASE) {
            Text(
                stringResource(
                    R.string.translation_install_release_reason,
                    offlineTranslatorZapstoreRelease.versionName,
                    OfflineTranslatorLanguageCatalog.engineVersions.last(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (openFailed) {
            Text(
                stringResource(R.string.translation_install_open_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
