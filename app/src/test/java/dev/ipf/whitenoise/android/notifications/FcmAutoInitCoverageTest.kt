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
 * keeps Firebase Messaging auto-init off and only the delivery controller turns it on: a sync for accounts
 * already on Push does so right before its token read, and a new Push selection reads its token with it off
 * and turns it on once registration commits. Leaving native push for every account turns it off again.
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

    /**
     * A sync for existing Push accounts turns auto-init on before its token read, unless a native-push enable
     * that can still roll back is open, a new selection reads with it off, and only the shared fetch reads the
     * SDK token.
     */
    @Test
    fun syncTokenReadsEnableAutoInitFirst() {
        val sync = appModuleSource(SYNC)
        val fetch = sync.functionBody("fetchFcmTokenOrNull")
        val read = fetch.indexOf("this::readFcmTokenFromSdk")

        assertFalse("a token fetch never turns auto-init on unfenced", "applyFcmAutoInit(enabled = true)" in fetch)
        val forAccounts = sync.functionBody("syncNativePushRegistrationForAccountsLocked")
        val decide = appModuleSource(ADMISSION).functionBody("deferOrEnableFcmAutoInitForSync")
        assertTrue(decide.contains("uncommittedNativePushEnables.get() == 0"))
        assertTrue(
            "an open delivery-mode transaction also defers auto-init",
            decide.contains("activeDeliveryModeTransactions.get() == 0"),
        )
        val fencedEnable = "enableFcmAutoInitIfAdmitted { quiet() && operationIsCurrent() } == null"
        assertTrue("a sync admitted when it writes turns auto-init on, or defers", fencedEnable in decide)
        val deferredRetry = "val retryStored = !autoInitDeferred || recordPendingRegistrationSync()"
        assertTrue("a deferred sync records its retry", deferredRetry in forAccounts)
        val decided = "val autoInitDeferred = deferOrEnableFcmAutoInitForSync(operationIsCurrent)"
        assertTrue(
            "auto-init is turned on before the token read",
            forAccounts.indexOf(decided) in 0 until forAccounts.indexOf("tokenForNativePushSync("),
        )
        val unfenced = Regex("""applyFcmAutoInit\(enabled = true\)""")
        val stateSources = listOf(SYNC, SELECTION, PUSH_CLEARS, AUTO_INIT, MODE_OWNERSHIP).map(::appModuleSource)
        assertTrue("every on write is fenced", stateSources.none { unfenced.containsMatchIn(it) })
        val acknowledge = sync.functionBody("acknowledgeRegistrationSyncUnlessUnsettled")
        assertTrue("and keeps the pending marker", "autoInitDeferred -> true" in acknowledge)
        val unstored = "fcmAutoInitWriteFailed.get() -> !fcmAutoInitRetryUnstored.get()"
        assertTrue("a failed write settles only when its retry was stored", unstored in acknowledge)
        val acknowledged = "acknowledgeRegistrationSyncUnlessUnsettled(token, autoInitDeferred, operationIsCurrent)"
        assertTrue(acknowledged in forAccounts)
        assertTrue("fetchFcmTokenOrNull must read the SDK token", read >= 0)
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

    /** A sync without the capability settles auto-init from account demand even when its fallback is cancelled. */
    @Test
    fun unavailableCapabilitySettlesAutoInitAfterFallback() {
        val sync = appModuleSource(SYNC).functionBody("syncNativePushRegistrationIfEnabledLocked")
        val fallback = sync.indexOf("reconcileUnavailableNativePushFallback(capability, accountRefs)")
        val settle = sync.indexOf("withContext(NonCancellable)", startIndex = fallback)

        assertTrue("the fallback must run before the settle", fallback in 0 until settle)
        assertTrue(sync.substring(fallback, settle).contains("} finally {"))
        assertTrue(sync.substring(settle).contains("disableFcmAutoInitWithoutNativeDemand(accountRefs)"))
        val demandOff = appModuleSource(AUTO_INIT).functionBody("disableFcmAutoInitWithoutNativeDemand")
        assertTrue(demandOff.contains("runCatchingCancellable { nativePushDemand(accountRefs) }"))
        val demandWrite = "if (demand == NativePushDemand.None) disableFcmAutoInitIfUnchanged(accountRefs)"
        assertTrue(demandOff.contains(demandWrite))
        val unchanged = appModuleSource(AUTO_INIT).functionBody("disableFcmAutoInitIfUnchanged")
        val liveRecheck = "if (host.nativePushAccountRefs() != readAccountRefs) return true"
        assertTrue("the off write must recheck the live account list", unchanged.contains(liveRecheck))
        assertTrue(unchanged.substringAfter(liveRecheck).contains("return applyFcmAutoInit(enabled = false)"))
    }

    /** Every locked sync and permission pause settles auto-init from demand when it is cancelled. */
    @Test
    fun cancelledSyncsSettleAutoInit() {
        val sync = appModuleSource(SYNC)
        val settle = appModuleSource(AUTO_INIT).functionBody("settlingFcmAutoInitOnCancel")

        listOf("syncNativePushRegistration", "pauseNotificationDeliveryReconciliation").forEach { locked ->
            val body = sync.functionBody(locked)
            assertTrue("$locked must settle on cancel", body.contains("settlingFcmAutoInitOnCancel {"))
        }
        assertTrue(settle.contains("withContext(NonCancellable) {"))
        val retry = "!= NativePushDemand.None) recordPendingRegistrationSync()"
        assertTrue("a cancelled sync with Push demand keeps a retry", retry in settle)
        assertTrue(settle.contains("throw cancelled"))
    }

    /** The selection checks ownership and stores its token under the sync lock, and reads Firebase outside it. */
    @Test
    fun selectionTokenReadIsFencedByOwnership() {
        val selection = appModuleSource(SELECTION)
        val sync = appModuleSource(SYNC)
        val declaration = "fun NotificationDeliveryController.fetchFcmTokenForOwner("
        assertTrue("missing $declaration", sync.contains(declaration))
        // ktlint keeps this function expression-bodied, so functionBody would return its first lambda instead.
        val fenced = sync.substringAfter(declaration).substringBefore("\n\n")
        val fetch = sync.functionBody("fetchFcmTokenOrNull")

        assertTrue(selection.functionBody("selectFcmTransport").contains("fetchFcmTokenForOwner(owner)"))
        assertTrue(fenced.contains("ownerFence = nativePushSyncMutex"))
        assertTrue(fenced.contains("tokenOwnerIsCurrent = { ownsNotificationDeliveryMode(owner) }"))
        assertFalse("a provisional selection must not turn auto-init on", "applyFcmAutoInit" in fetch)
        val admitAt = fetch.indexOf("val ownerAdmitted = fence.withLock { tokenOwnerIsCurrent() }")
        val read = fetch.indexOf("reader()")
        assertTrue("the owner check must run under the fence", admitAt >= 0)
        assertTrue("the SDK read must run outside the fence", admitAt < read)
        assertTrue("the token store must run under the fence", fetch.indexOf("fence.withLock", read) > read)
    }

    /**
     * Only a committed Push cutover turns auto-init on, under the sync lock while the owner still owns delivery,
     * and an incomplete one settles it. Registration alone never turns it on, since the cutover can still fail.
     */
    @Test
    fun fcmSelectionOwnsAutoInitThroughRegistration() {
        val selection = appModuleSource(SELECTION)
        val autoInit = appModuleSource(AUTO_INIT)

        assertFalse(
            "registration must not set auto-init before the cutover commits",
            selection.functionBody("enableNativeDeliveryForAccounts").contains("applyFcmAutoInit"),
        )
        val select = selection.functionBody("selectFcmDelivery")
        val opened = select.indexOf("uncommittedNativePushEnables.incrementAndGet()")
        val closed = select.indexOf("uncommittedNativePushEnables.decrementAndGet()")
        val committed = select.indexOf("commitFcmAutoInitAfterEnable { ownsNotificationDeliveryMode(owner) }")
        assertTrue("the selection stays uncommitted until its cutover returns", opened in 0 until closed)
        assertTrue("only a cutover that returned true commits auto-init", closed < committed)
        assertTrue(select.contains("if (selected.getOrNull() == true) {"))
        assertTrue(select.contains("settleFcmAutoInitAfterFailedSelection()"))
        val commit = autoInit.functionBody("commitFcmAutoInitAfterEnable")
        assertTrue(commit.contains("nativePushSyncMutex.withLock"))
        val superseded = "if (!isCurrent()) return@withLock recordPendingRegistrationSync()"
        assertTrue("a superseded commit leaves a sync pending", superseded in commit)
        assertTrue("an unsettled commit fails the selection", "return selected.getOrThrow() && settled" in select)
        val required =
            "NativePushDemand.Required -> enableFcmAutoInitIfAdmitted(isCurrent) ?: recordPendingRegistrationSync()"
        assertTrue("Push demand turns auto-init on only while still admitted", required in commit)
        val admitted = appModuleSource(ADMISSION).functionBody("enableFcmAutoInitIfAdmitted")
        val fenced = admitted.substringAfter("synchronized(fcmAutoInitAdmission) {")
        assertTrue(
            "admission is checked with the SDK write under the fence a new change opens under",
            fenced.indexOf("if (admitted())") in 0 until fenced.indexOf("setFcmAutoInitEnabled(true)"),
        )
        val toggle = appModuleSource(PUSH_CLEARS).functionBody("setNativePushEnabledSettlingAutoInit")
        assertTrue(toggle.contains("if (enabled) uncommittedNativePushEnables.incrementAndGet()"))
        val confirmed =
            "!enabled || !changed || commitFcmAutoInitAfterEnable { activeDeliveryModeTransactions.get() == 0 }"
        assertTrue("only a confirmed enable commits, and not while a mode change is open", confirmed in toggle)
        val settle = autoInit.functionBody("settleFcmAutoInitAfterFailedSelection")
        assertTrue(settle.contains("nativePushSyncMutex.withLock"))
        assertTrue(
            "an incomplete change that leaves Push demand, or cannot read it, leaves a registration sync pending",
            settle.contains("disableFcmAutoInitWithoutLoadedNativeDemand() != NativePushDemand.None") &&
                settle.contains("recordPendingRegistrationSync()"),
        )
        val loaded = autoInit.functionBody("disableFcmAutoInitWithoutLoadedNativeDemand")
        assertTrue(loaded.contains("if (accountRefs.isEmpty()) null else disableFcmAutoInitWithoutNativeDemand("))
    }

    /**
     * A device-wide delivery-mode change is counted before its intent advances and without waiting for a lock.
     * The last one to end drains a retry that a sync or enable deferred to it, after finish clears busy, even
     * when finish throws, and a handed-off change ends even if cancelled before it runs.
     */
    @Test
    fun deliveryModeChangesHoldAutoInitUntilTheLastEnds() {
        val ownership = appModuleSource(MODE_OWNERSHIP)
        val begin = ownership.functionBody("beginDeliveryModeTransaction")
        val counted = begin.indexOf("activeDeliveryModeTransactions.incrementAndGet()")
        assertTrue("a change is counted before its intent advances", counted in 0 until begin.indexOf(".advance()"))
        assertFalse("a newer choice never waits on native work to supersede", "withLock" in begin)
        assertTrue("a change opens under the on write's admission fence", "synchronized(fcmAutoInitAdmission)" in begin)
        val end = ownership.functionBody("endDeliveryModeTransaction")
        val lastEnd = "activeDeliveryModeTransactions.decrementAndGet() == 0 &&"
        assertTrue("the last change to end drains the retry", end.substringAfter("withLock {").contains(lastEnd))
        val bookkeeping = end.substringBefore("when {")
        assertTrue("only the count and marker read ignore cancellation", "withContext(NonCancellable)" in bookkeeping)
        assertFalse("the retry itself is cancellable", "NonCancellable" in end.substringAfter("when {"))
        assertTrue("an active caller retries inline", "isActive -> drainDeferredRegistrationSync()" in end)
        val scheduled = "notificationScope.launch { drainDeferredRegistrationSync() }"
        assertTrue("a cancelled caller schedules it", scheduled in end)
        val drain = ownership.functionBody("drainDeferredRegistrationSync")
        assertTrue("the retry is bounded", "withTimeoutOrNull(deferredRegistrationDrainTimeoutMs)" in drain)
        assertTrue("a failed drain never replaces the result", "runCatchingCancellable {" in drain)
        val handoff = ownership.functionBody("launchNotificationDeliveryModeReconciliation")
        assertTrue("a handed-off change always ends", "launch(start = CoroutineStart.ATOMIC)" in handoff)
        val apply = appModuleSource(SELECTION).functionBody("applyMode")
        assertTrue(apply.contains("val intentGeneration = beginDeliveryModeTransaction()"))
        val reconcile =
            appModuleSource("$STATE/NotificationDeliveryController.kt")
                .functionBody("reconcileNotificationDeliveryModeAfterActivation")
        assertTrue(reconcile.contains("val intentGeneration = beginDeliveryModeTransaction()"))
        listOf(handoff, apply, reconcile).forEach { body ->
            val closing = body.substringAfter("} finally {")
            val finish = closing.indexOf("finishNotificationDeliveryModeTransaction(")
            val nested = closing.indexOf("} finally {")
            val ended = closing.indexOf("endDeliveryModeTransaction()")
            assertTrue("busy clears first, and the change ends even if finish throws", finish in 0 until nested)
            assertTrue(nested < ended)
        }
    }

    /** A Local selection that fails or is cancelled after a native disable committed settles auto-init. */
    @Test
    fun incompleteLocalSelectionSettlesAutoInit() {
        val local = appModuleSource(SELECTION).functionBody("selectPersistentDelivery")

        assertTrue(local.contains("runCatching { selectPersistentTransport(owner, nativeDisableCommitted) }"))
        assertTrue(local.contains("withContext(NonCancellable) { settleFcmAutoInitAfterFailedSelection() }"))
        assertTrue(local.contains("selected.getOrThrow()"))
    }

    /**
     * Signing out or wiping the last signed-in account turns auto-init off, and the token goes as before. A wipe
     * settles auto-init from the native account list on every outcome but a reported unfinished cleanup.
     */
    @Test
    fun lastAccountRemovalDisablesAutoInit() {
        val appState = appModuleSource("$STATE/AppState.kt")

        assertTrue(appState.contains("notificationDelivery.forgetFcmTokenAfterLastAccount()"))
        assertTrue(appState.contains("notificationDelivery.settleFcmAfterSignOut(next, refreshed)"))
        assertFalse("the token must not be cleared without auto-init", appState.contains("pushTokenStore.clear()"))
        val autoInit = appModuleSource(AUTO_INIT)
        val forget = autoInit.functionBody("forgetFcmTokenAfterLastAccount")
        assertTrue(forget.contains("pushTokenStore.clear()"))
        assertTrue(forget.contains("applyFcmAutoInit(enabled = false)"))
        val signOut = autoInit.functionBody("settleFcmAfterSignOut")
        assertTrue(signOut.contains("remaining.any { it.isSignedInSigningAccount() }"))
        assertTrue(signOut.contains("applyFcmAutoInit(enabled = false)"))
        assertTrue(appState.contains(".wipeSettlingFcmAutoInit { marmotIo { signOutAndWipe(wipedRef) } }"))
        val wipe = appModuleSource(PUSH_CLEARS).functionBody("wipeSettlingFcmAutoInit")
        assertTrue(wipe.contains("withContext(NonCancellable) { disableFcmAutoInitWithoutNativeAccountDemand() }"))
    }

    /** Leaving native push for every account, by selection or by sync, turns auto-init off. */
    @Test
    fun leavingNativePushDisablesAutoInit() {
        val sync = appModuleSource(SYNC)
        val selection = appModuleSource(SELECTION)

        val autoInit = appModuleSource(AUTO_INIT)
        val completeSync = autoInit.functionBody("completeSyncWithoutNativePush")
        assertTrue(completeSync.contains("disableFcmAutoInitIfUnchanged(accountRefs)"))
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
        const val PUSH_CLEARS = "$STATE/NotificationDeliveryControllerPushClears.kt"
        const val MODE_OWNERSHIP = "$STATE/NotificationDeliveryControllerModeOwnership.kt"
        const val ADMISSION = "$STATE/NotificationDeliveryControllerFcmAutoInitAdmission.kt"
    }
}
