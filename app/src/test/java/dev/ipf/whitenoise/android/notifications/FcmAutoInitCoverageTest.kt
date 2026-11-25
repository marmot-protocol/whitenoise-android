package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Firebase must not request an FCM token before an account selects native push (#4081). The manifest
 * keeps Firebase Messaging auto-init off, only the delivery controller turns it on, and it does so
 * right before every token read. Leaving native push for every account turns it off again.
 * `FcmAutoInitDeliveryTest` drives the same rules through the controller.
 */
@RunWith(RobolectricTestRunner::class)
class FcmAutoInitCoverageTest {
    /** The main manifest declares auto-init exactly once, as `false`. */
    @Test
    fun mainManifestDisablesFirebaseMessagingAutoInit() {
        val values = autoInitMetaDataValues(appModuleFile("src/main/AndroidManifest.xml"))

        assertEquals(listOf("false"), values)
    }

    /** No flavor, build-type or test manifest turns auto-init back on. */
    @Test
    fun noSourceSetManifestReenablesAutoInit() {
        val manifests =
            appModuleFile("src")
                .listFiles()
                .orEmpty()
                .map { File(it, "AndroidManifest.xml") }
                .filter(File::exists)
        assertTrue("expected the main manifest among source sets", manifests.size > 1)

        manifests.forEach { manifest ->
            val values = autoInitMetaDataValues(manifest)
            assertTrue("${manifest.parentFile?.name} must not enable auto-init: $values", values.all { it == "false" })
        }
    }

    /** The merged manifest the app actually ships with keeps auto-init off. */
    @Test
    fun mergedManifestKeepsAutoInitOff() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metaData =
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData

        assertTrue(metaData?.containsKey(AUTO_INIT_KEY) == true)
        assertFalse(metaData!!.getBoolean(AUTO_INIT_KEY, true))
    }

    /** Only the platform boundary writes auto-init, and only the sync file reads a token from the SDK. */
    @Test
    fun firebaseMessagingStateHasOneOwner() {
        val writers = productionSourcesMatching(Regex("""setAutoInitEnabled\s*\(|isAutoInitEnabled\s*="""))
        val sdkUsers = productionSourcesMatching(Regex("""\bFirebaseMessaging\b"""))

        assertEquals(setOf("NativePushFallbackRuntime.kt"), writers)
        assertEquals(setOf("NativePushFallbackRuntime.kt", "NotificationDeliveryControllerNativePushSync.kt"), sdkUsers)
    }

    /** Every token read turns auto-init on first, and only the shared fetch reads the SDK token. */
    @Test
    fun tokenReadsEnableAutoInitFirst() {
        val sync = appModuleSource(SYNC)
        val fetch = sync.functionBody("fetchFcmTokenOrNull")
        val enable = fetch.indexOf("applyFcmAutoInit(enabled = true)")
        val read = fetch.indexOf("this::readFcmTokenFromSdk")

        assertTrue("fetchFcmTokenOrNull must enable auto-init", enable >= 0)
        assertTrue("fetchFcmTokenOrNull must read the SDK token", read >= 0)
        assertTrue("auto-init must be enabled before the token read", enable < read)
        assertEquals(2, Regex("""\breadFcmTokenFromSdk\b""").findAll(sync).count())
    }

    /** Sync reads a token only after it finds an account that still needs native delivery. */
    @Test
    fun syncReadsTokenOnlyForNativeDemand() {
        val accounts = appModuleSource(SYNC).functionBody("syncNativePushRegistrationForAccountsLocked")
        val demand = accounts.indexOf("nativePushDemand(accountRefs)")
        val token = accounts.indexOf("tokenForNativePushSync(")

        assertTrue("sync must check native demand", demand >= 0)
        assertTrue("sync must check demand before reading a token", demand < token)
        assertTrue(accounts.contains("NativePushDemand.None -> return completeSyncWithoutNativePush("))
    }

    /** A completed Push selection writes auto-init on under the sync lock, and an incomplete one settles it. */
    @Test
    fun fcmSelectionOwnsAutoInitThroughRegistration() {
        val selection = appModuleSource(SELECTION)
        val autoInit = appModuleSource(AUTO_INIT)

        assertTrue(
            selection
                .functionBody("enableNativeDeliveryForAccounts")
                .contains("if (registered) applyFcmAutoInit(enabled = true)"),
        )
        assertTrue(
            selection
                .functionBody("selectFcmDelivery")
                .contains("settleFcmAutoInitAfterFailedSelection(owner.accountRefs)"),
        )
        val settle = autoInit.functionBody("settleFcmAutoInitAfterFailedSelection")
        assertTrue(settle.contains("nativePushSyncMutex.withLock"))
        assertTrue(settle.contains("applyFcmAutoInit(enabled = false)"))
    }

    /** Both ways of removing the last signed-in account turn auto-init off with the cached token. */
    @Test
    fun lastAccountRemovalDisablesAutoInit() {
        val appState = appModuleSource("$STATE/AppState.kt")

        assertEquals(2, Regex("""notificationDelivery\.forgetFcmTokenAfterLastAccount\(\)""").findAll(appState).count())
        assertFalse("the token must not be cleared without auto-init", appState.contains("pushTokenStore.clear()"))
        val forget = appModuleSource(AUTO_INIT).functionBody("forgetFcmTokenAfterLastAccount")
        assertTrue(forget.contains("pushTokenStore.clear()"))
        assertTrue(forget.contains("applyFcmAutoInit(enabled = false)"))
    }

    /** Leaving native push for every account, by selection or by sync, turns auto-init off. */
    @Test
    fun leavingNativePushDisablesAutoInit() {
        val sync = appModuleSource(SYNC)
        val selection = appModuleSource(SELECTION)

        val autoInit = appModuleSource(AUTO_INIT)
        assertTrue(autoInit.functionBody("completeSyncWithoutNativePush").contains("applyFcmAutoInit(enabled = false)"))
        assertTrue(
            selection
                .functionBody("disableNativePushForPersistentDelivery")
                .contains("applyFcmAutoInit(enabled = false)"),
        )
        assertTrue(
            sync
                .functionBody("syncNativePushRegistrationIfEnabledLocked")
                .contains("if (accountRefs.isEmpty()) return completeSyncWithoutNativePush("),
        )
    }

    /** Reads every `firebase_messaging_auto_init_enabled` value declared under `<application>`. */
    private fun autoInitMetaDataValues(manifest: File): List<String> {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)
        val metaData = document.getElementsByTagName("meta-data")
        return (0 until metaData.length)
            .map { metaData.item(it) as Element }
            .filter { it.getAttributeNS(ANDROID_NS, "name") == AUTO_INIT_KEY }
            .map { it.getAttributeNS(ANDROID_NS, "value") }
    }

    /** Names the production Kotlin files whose text matches [pattern]. */
    private fun productionSourcesMatching(pattern: Regex): Set<String> =
        appModuleFile("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && pattern.containsMatchIn(it.readText()) }
            .map { it.name }
            .toSet()

    private companion object {
        const val AUTO_INIT_KEY = "firebase_messaging_auto_init_enabled"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val STATE = "src/main/java/dev/ipf/whitenoise/android/state"
        const val SYNC = "$STATE/NotificationDeliveryControllerNativePushSync.kt"
        const val SELECTION = "$STATE/NotificationDeliveryControllerModeSelection.kt"
        const val AUTO_INIT = "$STATE/NotificationDeliveryControllerFcmAutoInit.kt"
    }
}
