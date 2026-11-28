package dev.ipf.whitenoise.android.state

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.audio.VoiceDraftStore
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.sharedPlaintextPrefix
import dev.ipf.whitenoise.android.notifications.conversationShortcutAccountExtras
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/**
 * A failed post-wipe account refresh must not wipe every other account's member mutes (#2782
 * follow-up). `retainMemberMutesForAccounts` treats its input as an allow-list: a transient
 * `listAccounts()` failure used to fall back to an empty list, which then pruned every stored
 * mute, including ones for accounts the wipe never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountWipeMemberMuteRetentionTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var listAccountsFailure: Throwable? = null
    private var remainingAccounts = listOf(localAccount(SURVIVING_ACCOUNT))
    private var signInCalls = 0
    private var wiped = false
    private var localCleanupCompleted = true
    private var unreachableSibling = false
    private var savedUnlinked = false
    private var nativeWipeTarget: Any? = null

    @Volatile private var heldAccountRead: HeldAccountRead? = null

    /** Stops the next account read inside the runtime, once it is [reached], until the test supplies its [listing]. */
    private class HeldAccountRead {
        val reached = CompletableDeferred<Unit>()
        val listing = CompletableFuture<List<AccountSummaryFfi>>()
    }

    /** Builds a locally signing account summary whose id is derived from the label hash. */
    private fun localAccount(
        label: String,
        signedOut: Boolean = false,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex =
            label
                .hashCode()
                .toUInt()
                .toString(16)
                .padStart(8, '0'),
        localSigning = true,
        externalSigning = false,
        signedOut = signedOut,
        running = !signedOut,
        unlinked = false,
    )

    /** The accounts MDK lists while the unlinked copy is still on this device. */
    private fun unlinkedListing(): List<AccountSummaryFfi> {
        val unlinked = localAccount(WIPED_ACCOUNT).copy(unlinked = savedUnlinked)
        return listOf(unlinked) + remainingAccounts
    }

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            /** Resumes the suspended native call with the given failure. */
            fun suspendFailure(failure: Throwable): Any {
                (arguments!!.last() as Continuation<Any?>).resumeWithException(failure)
                return COROUTINE_SUSPENDED
            }

            when (method.name) {
                "signOutAndWipe" ->
                    WipeOutcomeFfi(
                        groupsLeft = 0u,
                        groupLeaveFailures = emptyList(),
                        keyPackagesDeleted = 1u,
                        keyPackageFailures = emptyList(),
                        localCleanup = LocalCleanupReportFfi(completed = localCleanupCompleted, reason = null),
                        groupsLeftToSiblings = 0u,
                        siblingsNotReached = unreachableSibling,
                    ).also {
                        nativeWipeTarget = arguments?.firstOrNull()
                        wiped = localCleanupCompleted
                    }
                "listAccounts" -> {
                    val held = heldAccountRead
                    if (held != null) {
                        heldAccountRead = null
                        held.reached.complete(Unit)
                        held.listing.get(PAUSED_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } else {
                        val listing = if (wiped) remainingAccounts else unlinkedListing()
                        listAccountsFailure?.let(::suspendFailure) ?: listing
                    }
                }
                "signInAccount" -> {
                    signInCalls++
                    Unit
                }
                "attachmentDownloadPolicy" -> AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "AccountWipeMemberMuteRetentionMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        suspendFailure(UnsupportedOperationException("Unexpected Marmot call: ${method.name}"))
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    @Before
    fun setUp() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    private fun appState(active: String = WIPED_ACCOUNT): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(localAccount(WIPED_ACCOUNT), localAccount(SURVIVING_ACCOUNT)),
            activeAccountRef = active,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** A completed native wipe triggers cleanup for the account whose shares were staged. */
    @Test
    fun completedWipeRemovesOnlyTheWipedAccountsStagedCopies() =
        runBlocking {
            val shared = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
            val removed =
                File(shared, sharedPlaintextPrefix(context, WIPED_ACCOUNT) + "wipe.pdf").apply { writeText("test") }
            val preserved =
                File(shared, sharedPlaintextPrefix(context, SURVIVING_ACCOUNT) + "keep.pdf").apply { writeText("test") }

            val result = checkNotNull(appState().signOutAndWipeActiveAccount())

            assertTrue(result.native.localCleanup.completed)
            assertFalse(removed.exists())
            assertTrue(preserved.exists())
        }

    /** A refused wipe preserves the saved take, and a successful retry purges only the wiped account. */
    @Test
    fun voiceDraftPurgesOnlyWhenNativeWipeCompletes() =
        runBlocking {
            val removed = saveVoiceDraft(WIPED_ACCOUNT)
            val retained = saveVoiceDraft(SURVIVING_ACCOUNT)
            val state = appState()
            localCleanupCompleted = false

            assertFalse(checkNotNull(state.signOutAndWipeActiveAccount()).native.localCleanup.completed)
            assertTrue(removed.isFile)
            assertTrue(retained.isFile)

            localCleanupCompleted = true
            assertTrue(checkNotNull(state.signOutAndWipeActiveAccount()).native.localCleanup.completed)
            assertFalse(removed.exists())
            assertTrue(retained.isFile)
        }

    /** Saves a real indexed take in the same private store the app's voice owner opens. */
    private fun saveVoiceDraft(account: String): File {
        val store = VoiceDraftStore(context.noBackupFilesDir.resolve("voice-drafts"))
        val key = VoiceNoteKey(account, GROUP)
        val capture = store.recordingDirectory(key).resolve("voice-fixture.m4a").apply { writeText("audio") }
        return checkNotNull(store.commit(key, capture, 2_000L, stopReason = null)).file
    }

    /** Wiping the final signed-in identity must not select a retained signed-out identity. */
    @Test
    fun wipeWithOnlySignedOutProfilesReturnsToOnboarding() =
        runBlocking {
            remainingAccounts = listOf(localAccount("retained", signedOut = true))
            val state = appState()

            state.signOutAndWipeActiveAccount()

            org.junit.Assert.assertNull(state.activeAccountRef)
            org.junit.Assert.assertEquals(AppPhase.Onboarding, state.phase)
            org.junit.Assert.assertFalse(
                context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE).contains("active_account"),
            )
            org.junit.Assert.assertEquals(remainingAccounts, state.accounts)
            org.junit.Assert.assertEquals(0, signInCalls)
        }

    /** Inventory order and tracked-only identities must not displace the surviving signing profile. */
    @Test
    fun wipeSkipsSignedOutAndTrackedProfilesBeforeSignedInSuccessor() =
        runBlocking {
            remainingAccounts =
                listOf(
                    localAccount("retained", signedOut = true),
                    localAccount("tracked").copy(localSigning = false),
                    localAccount(SURVIVING_ACCOUNT),
                )
            val state = appState()

            state.signOutAndWipeActiveAccount()

            org.junit.Assert.assertEquals(SURVIVING_ACCOUNT, state.activeAccountRef)
            org.junit.Assert.assertEquals(AppPhase.Ready, state.phase)
            org.junit.Assert.assertEquals(
                SURVIVING_ACCOUNT,
                context
                    .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
                    .getString("active_account", null),
            )
            org.junit.Assert.assertEquals(0, signInCalls)
        }

    /** Tracked-only inventory is retained but cannot become a signed-in successor. */
    @Test
    fun wipeWithOnlyTrackedProfilesReturnsToOnboarding() =
        runBlocking {
            remainingAccounts = listOf(localAccount("tracked").copy(localSigning = false))
            val state = appState()
            state.signOutAndWipeActiveAccount()
            org.junit.Assert.assertNull(state.activeAccountRef)
            org.junit.Assert.assertEquals(AppPhase.Onboarding, state.phase)
            org.junit.Assert.assertEquals(remainingAccounts, state.accounts)
            org.junit.Assert.assertEquals(0, signInCalls)
        }

    /** A clean wipe still prunes a genuinely removed account's mutes as before. */
    @Test
    fun successfulRefreshPrunesTheWipedAccountsMutesOnly() =
        runBlocking {
            val appState = appState()
            appState.memberMutePreferences.setMuted(WIPED_ACCOUNT, GROUP, MEMBER, muted = true)
            appState.memberMutePreferences.setMuted(SURVIVING_ACCOUNT, GROUP, MEMBER, muted = true)

            appState.signOutAndWipeActiveAccount()

            assertTrue(
                "the surviving account's mute must remain after a clean wipe",
                appState.memberMutePreferences.isMuted(SURVIVING_ACCOUNT, GROUP, MEMBER),
            )
            assertTrue(
                "the wiped account's own mute is expected to be pruned once it is gone",
                !appState.memberMutePreferences.isMuted(WIPED_ACCOUNT, GROUP, MEMBER),
            )
        }

    /** Stages originals for two accounts and verifies successful cleanup releases only the wiped account. */
    @Test
    fun successfulWipePurgesItsPrivateShareShelfAndPreservesAnotherAccount() =
        runBlocking {
            val state = appState()
            val files =
                dev.ipf.whitenoise.android.share
                    .PrivateShareFiles(context)
            val removed = stagedFile(files, localAccount(WIPED_ACCOUNT).accountIdHex)
            val retained = stagedFile(files, localAccount(SURVIVING_ACCOUNT).accountIdHex)
            try {
                state.signOutAndWipeActiveAccount()
                org.junit.Assert.assertNull(files.resolve(removed))
                org.junit.Assert.assertNotNull(files.metadata(retained))
            } finally {
                files.leases.releaseAccount(localAccount(WIPED_ACCOUNT).accountIdHex)
                files.leases.releaseAccount(localAccount(SURVIVING_ACCOUNT).accountIdHex)
            }
        }

    /** Makes native local cleanup incomplete and verifies the Android source owner is retained for recovery. */
    @Test
    fun failedNativeLocalCleanupKeepsThePrivateShareShelf() =
        runBlocking {
            localCleanupCompleted = false
            val state = appState()
            val files =
                dev.ipf.whitenoise.android.share
                    .PrivateShareFiles(context)
            val uri = stagedFile(files, localAccount(WIPED_ACCOUNT).accountIdHex)
            try {
                state.signOutAndWipeActiveAccount()
                org.junit.Assert.assertNotNull(files.metadata(uri))
            } finally {
                files.leases.releaseAccount(localAccount(WIPED_ACCOUNT).accountIdHex)
            }
        }

    /** Creates a completed one-byte original with an account-scoped shelf lease for wipe assertions. */
    private fun stagedFile(
        files: dev.ipf.whitenoise.android.share.PrivateShareFiles,
        account: String,
    ): android.net.Uri {
        val (uri, file) = files.newFile()
        file.writeBytes(byteArrayOf(1))
        files.finish(uri, "file", "application/octet-stream", 1)
        files.leases.saveShelf(account, GROUP, listOf(uri))
        return uri
    }

    /** A refresh that fails right after the wipe must not prune an unrelated account's mutes. */
    @Test
    fun failedPostWipeRefreshLeavesTheSurvivingAccountsMuteIntact() =
        runBlocking {
            val appState = appState()
            appState.memberMutePreferences.setMuted(SURVIVING_ACCOUNT, GROUP, MEMBER, muted = true)
            val author = "a".repeat(64)
            val profileChoice =
                dev.ipf.whitenoise.android.notifications.ProfileNotificationOverride(
                    dev.ipf.whitenoise.android.notifications.ProfileNotificationMode.MUTED,
                )
            appState.profileNotificationOverrides.set(SURVIVING_ACCOUNT, author, profileChoice)
            listAccountsFailure = RuntimeException("account refresh unavailable")

            appState.signOutAndWipeActiveAccount()

            assertTrue(
                "a transient refresh failure must not wipe an unrelated account's mute",
                appState.memberMutePreferences.isMuted(SURVIVING_ACCOUNT, GROUP, MEMBER),
            )
            org.junit.Assert.assertEquals(
                profileChoice,
                appState.profileNotificationOverrides.get(SURVIVING_ACCOUNT, author),
            )
        }

    /** A failed inventory read after removing an inactive profile must keep the other profile open. */
    @Test
    fun postWipeReadFailureRetainsTheUnrelatedActiveProfile() =
        runBlocking {
            val state = appState(active = SURVIVING_ACCOUNT)
            listAccountsFailure = RuntimeException("inventory unavailable")
            val completion = state.withAccountLifetime { state.signOutAndWipeActiveAccountLocked(WIPED_ACCOUNT) }
            assertTrue(checkNotNull(completion).native.localCleanup.completed)
            org.junit.Assert.assertEquals(SURVIVING_ACCOUNT, state.activeAccountRef)
            org.junit.Assert.assertEquals(listOf(SURVIVING_ACCOUNT), state.accounts.map { it.label })
            assertFalse(state.accountListLoaded)
        }

    /** Removing an unlinked profile while another is active must preserve its upload, composer and cold-read cache. */
    @Test
    fun inactiveRemovalKeepsTheSelectedAccountAndItsWork() = verifyInactiveWipe(completed = true)

    /** A native refusal must leave the selected profile's work and the unlinked account's files intact. */
    @Test
    fun refusedInactiveRemovalKeepsBothProfilesIntact() = verifyInactiveWipe(completed = false)

    /**
     * Removes the unlinked inactive account, with native cleanup finishing or refused as [completed] says, and checks
     * the surviving profile's session, files and upload are untouched. The unlinked notice goes only when it finished.
     */
    private fun verifyInactiveWipe(completed: Boolean) =
        runBlocking {
            savedUnlinked = true
            localCleanupCompleted = completed
            unreachableSibling = completed
            val state = appState(active = SURVIVING_ACCOUNT)
            state.presentDeviceUnlinked(WIPED_ACCOUNT)
            assertEquals(WIPED_ACCOUNT, state.toast?.unlinkedAccount)
            val files =
                listOf(MediaCacheDirs.COMPOSER_PASTE, MediaCacheDirs.UPLOAD_SOURCES).map { directory ->
                    val root = File(context.cacheDir, directory).apply { mkdirs() }
                    File(root, "active-copy").apply { writeText("mine") }
                }
            val scopedShare =
                File(
                    File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() },
                    sharedPlaintextPrefix(context, WIPED_ACCOUNT) + "removed.pdf",
                ).apply { writeText("theirs") }
            val epoch = state.runtimeGeneration
            val cached = state.diskMediaCache.capturePublicationToken()
            val uploadEpoch = state.mediaUploadSessionEpoch()
            val registered = CompletableDeferred<Unit>()
            val upload =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    state.trackInFlightMediaUpload(SURVIVING_ACCOUNT, GROUP, "outgoing")
                    registered.complete(Unit)
                    awaitCancellation()
                }
            try {
                registered.await()
                assertEquals(completed, state.removeUnlinkedAccount(WIPED_ACCOUNT))
                assertEquals(WIPED_ACCOUNT, nativeWipeTarget)
                // The notice asks for this removal, so it goes with the removed copy and stays when removal is refused.
                assertEquals(if (completed) null else WIPED_ACCOUNT, state.toast?.unlinkedAccount)
                assertEquals(SURVIVING_ACCOUNT, state.activeAccountRef)
                assertEquals(epoch, state.runtimeGeneration)
                assertEquals(uploadEpoch, state.mediaUploadSessionEpoch())
                assertEquals(cached, state.diskMediaCache.capturePublicationToken())
                assertTrue(upload.isActive)
                assertTrue(files.all { it.readText() == "mine" })
                assertEquals(!completed, scopedShare.exists())
                assertFalse(checkNotNull(state.pendingWipeReport).clean)
            } finally {
                upload.cancel()
            }
        }

    /** Removing one unlinked account leaves a notice about another account, and any ordinary notice, on screen. */
    @Test
    fun removingAnUnlinkedAccountKeepsEveryOtherNotice() =
        runBlocking {
            val others =
                listOf(
                    ToastMessage(AppText.Plain("unlinked"), unlinkedAccount = SURVIVING_ACCOUNT),
                    ToastMessage(AppText.Plain("something else")),
                )
            for (other in others) {
                savedUnlinked = true
                wiped = false
                val state = appState(active = SURVIVING_ACCOUNT)
                state.presentText(other)

                assertTrue(state.removeUnlinkedAccount(WIPED_ACCOUNT))
                assertEquals(other, state.toast)
            }
        }

    /** An unlinked device takes the account's conversation shortcuts down at once and keeps the other account's. */
    @Test
    fun unlinkClearsOnlyTheUnlinkedAccountsShortcuts() =
        runBlocking {
            savedUnlinked = true
            val removed = publishConversationShortcut(WIPED_ACCOUNT)
            val kept = publishConversationShortcut(SURVIVING_ACCOUNT)

            appState(active = SURVIVING_ACCOUNT).presentDeviceUnlinked(WIPED_ACCOUNT)

            val left = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
            assertFalse("the unlinked account's shortcut must go", removed in left)
            assertTrue("the other account's shortcut must stay", kept in left)
        }

    /** An account listed as linked again when the handler reads it was signed in again; its shortcuts stay. */
    @Test
    fun unlinkKeepsTheShortcutsOfAnAccountSignedInAgain() =
        runBlocking {
            savedUnlinked = false
            val kept = publishConversationShortcut(WIPED_ACCOUNT)

            appState(active = SURVIVING_ACCOUNT).presentDeviceUnlinked(WIPED_ACCOUNT)

            assertTrue(kept in ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })
        }

    /** Publishes [account]'s conversation shortcut for [GROUP] the way notifications scope it, and returns its id. */
    private fun publishConversationShortcut(account: String): String {
        val id = checkNotNull(conversationShortcutId(account, GROUP))
        val shortcut =
            ShortcutInfoCompat
                .Builder(context, id)
                .setShortLabel("Chat")
                .setIntent(Intent(Intent.ACTION_VIEW))
                .setLongLived(true)
                .setExtras(checkNotNull(conversationShortcutAccountExtras(account)))
                .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        return id
    }

    /**
     * The unlink handler re-reads the accounts before it speaks. A removal that finishes while that read is paused
     * leaves nothing to announce, whether the read then returns the list from before the removal or from after it.
     */
    @Test
    fun aNoticeStillBeingPreparedIsNotPublishedOnceItsAccountIsRemoved() =
        runBlocking {
            for (readPredatesRemoval in listOf(false, true)) {
                savedUnlinked = true
                wiped = false
                val state = appState(active = SURVIVING_ACCOUNT)
                val read = HeldAccountRead().also { heldAccountRead = it }
                val handler = launch(start = CoroutineStart.UNDISPATCHED) { state.presentDeviceUnlinked(WIPED_ACCOUNT) }
                withTimeout(PAUSED_READ_TIMEOUT_MS) { read.reached.await() }
                val listedBefore = unlinkedListing()

                assertTrue(withTimeout(PAUSED_READ_TIMEOUT_MS) { state.removeUnlinkedAccount(WIPED_ACCOUNT) })
                read.listing.complete(if (readPredatesRemoval) listedBefore else remainingAccounts)
                withTimeout(PAUSED_READ_TIMEOUT_MS) { handler.join() }

                assertNull(state.toast)
                assertTrue(state.accounts.none { it.label == WIPED_ACCOUNT })
            }
        }

    /**
     * An unlink reported before the removal stays silent even when its handler only starts once the removal is done,
     * which is the order the main thread can run them in.
     */
    @Test
    fun anUnlinkReportedBeforeTheRemovalIsNotAnnouncedAfterIt() =
        runBlocking {
            savedUnlinked = true
            val state = appState(active = SURVIVING_ACCOUNT)
            val handlers = Job()
            val scope = CoroutineScope(handlers)

            state.presentEventNotices(MarmotEventFfi.DeviceUnlinked("id", WIPED_ACCOUNT), marmot, scope, scope)
            assertTrue(state.removeUnlinkedAccount(WIPED_ACCOUNT))
            withTimeout(PAUSED_READ_TIMEOUT_MS) {
                while (handlers.children.any()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(HANDLER_POLL_MS)
                }
            }

            assertNull(state.toast)
            assertEquals(SURVIVING_ACCOUNT, state.activeAccountRef)
        }

    /**
     * An account removed and then listed again while the unlink handler was still reading is a fresh copy, and the
     * late handler does not announce an unlink for it.
     */
    @Test
    fun aNoticeStillBeingPreparedIsDroppedOnceItsAccountIsRemovedAndAddedBack() =
        runBlocking {
            savedUnlinked = true
            val state = appState(active = SURVIVING_ACCOUNT)
            val read = HeldAccountRead().also { heldAccountRead = it }
            val handler = launch(start = CoroutineStart.UNDISPATCHED) { state.presentDeviceUnlinked(WIPED_ACCOUNT) }
            withTimeout(PAUSED_READ_TIMEOUT_MS) { read.reached.await() }

            assertTrue(withTimeout(PAUSED_READ_TIMEOUT_MS) { state.removeUnlinkedAccount(WIPED_ACCOUNT) })
            savedUnlinked = false
            wiped = false
            state.refreshAccounts()
            assertTrue(state.accounts.any { it.label == WIPED_ACCOUNT && !it.unlinked })
            read.listing.complete(unlinkedListing())
            withTimeout(PAUSED_READ_TIMEOUT_MS) { handler.join() }

            assertNull(state.toast)
            assertEquals(SURVIVING_ACCOUNT, state.activeAccountRef)
        }

    /**
     * A handler outdated by its copy's removal must not begin a profile switch at all, because beginning one
     * supersedes a switch the user has pending. Advancing the copy's boundary stands in for the removal and the
     * account being added back and made active again, which this runtime double cannot activate.
     */
    @Test
    fun anOutdatedHandlerLeavesAPendingProfileSwitchAlone() =
        runBlocking {
            savedUnlinked = true
            val state = appState(active = WIPED_ACCOUNT)
            val read = HeldAccountRead().also { heldAccountRead = it }
            val handler = launch(start = CoroutineStart.UNDISPATCHED) { state.presentDeviceUnlinked(WIPED_ACCOUNT) }
            withTimeout(PAUSED_READ_TIMEOUT_MS) { read.reached.await() }

            state.accountSession.copyLifetimes.copyRemoved(WIPED_ACCOUNT) {}
            val pendingSwitch = state.accountSession.handoff.beginRequest(SURVIVING_ACCOUNT)
            read.listing.complete(unlinkedListing())
            withTimeout(PAUSED_READ_TIMEOUT_MS) { handler.join() }

            assertTrue(state.accountSession.handoff.isCurrent(pendingSwitch))
            assertNull(state.toast)
            assertEquals(WIPED_ACCOUNT, state.activeAccountRef)
        }

    /**
     * Sign out and wipe in Settings retires the unlinked notice as well. Both removal routes do it in a finally
     * after their wipe attempt, so cleanup that throws once the removal is published cannot leave the notice up.
     */
    @Test
    fun bothRemovalRoutesRetireTheUnlinkedNoticeEvenWhenTheWipeThrows() {
        val settings =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/settings/AccountWipeAndSignOut.kt")
                .functionBody("launchAccountWipe")
        val removal =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/UnlinkedAccountRemoval.kt")
                .functionBody("removeUnlinkedAccountForOutcome")
        val routes =
            listOf(
                Triple(settings, "signOutAndWipeActiveAccount()", "retireDeviceUnlinkedNotice(requested)"),
                Triple(removal, "signOutAndWipeActiveAccountLocked(", "retireDeviceUnlinkedNotice(label)"),
            )

        for ((body, attempt, retire) in routes) {
            val attempted = body.indexOf(attempt)
            val guarded = body.indexOf("} finally {", attempted)
            assertTrue(attempted >= 0 && guarded > attempted)
            // Neither finally body nests a block, so its first closing brace ends it.
            val finallyBody = body.substring(guarded + "} finally {".length).substringBefore("}")
            assertTrue(retire in finallyBody)
        }
    }

    /** An inactive wipe reports retained ownerless files, evicts unused playback and keeps a live player readable. */
    @Test
    fun retainedPlaybackAndOpaqueMediaKeepInactiveWipePartial() =
        runBlocking {
            val state = appState(active = SURVIVING_ACCOUNT)

            fun file(
                folder: String,
                name: String,
            ): File {
                val dir = File(context.cacheDir, folder).apply { mkdirs() }
                return File(dir, name).apply { writeText("private fixture") }
            }
            val playing = file(MediaCacheDirs.VOICE, "abc-name.ogg")
            val unused = file(MediaCacheDirs.VIDEO, "def-name.mp4")
            AttachmentPlaintextCache.protectPublicationFile(playing)
            try {
                val failure = state.wipeDecryptedMediaFromDisk(WIPED_ACCOUNT, retireSession = false)
                assertEquals(context.getString(R.string.wipe_media_ownership_unknown), failure?.reason)
                assertEquals("private fixture", playing.readText())
                assertFalse(unused.exists())
            } finally {
                AttachmentPlaintextCache.unprotectPublicationFile(playing)
            }
            val folders =
                listOf(
                    MediaCacheDirs.ORIGINALS,
                    MediaCacheDirs.DISPLAY_THUMBNAILS,
                    MediaCacheDirs.DOCUMENT_READER,
                    MediaCacheDirs.NOSTR_VIDEO,
                    MediaCacheDirs.NATIVE_ATTACHMENT_LEASES,
                    MediaCacheDirs.COMPOSER_PASTE,
                    MediaCacheDirs.UPLOAD_SOURCES,
                )
            for (folder in folders) {
                val opaque = file(folder, "entry")
                val failure = state.wipeDecryptedMediaFromDisk(WIPED_ACCOUNT, retireSession = false)
                assertEquals(folder, context.getString(R.string.wipe_media_ownership_unknown), failure?.reason)
                assertTrue(opaque.delete())
            }
            assertFalse(playing.exists())
            org.junit.Assert.assertNull(state.wipeDecryptedMediaFromDisk(WIPED_ACCOUNT, retireSession = false))
        }

    /** An old active-profile confirmation and a profile that is no longer unlinked have no authority to wipe. */
    @Test
    fun staleRemovalCannotWipeTheCurrentAccount() =
        runBlocking {
            val state = appState(active = SURVIVING_ACCOUNT)
            savedUnlinked = true
            org.junit.Assert.assertNull(state.removeUnlinkedAccountForOutcome(WIPED_ACCOUNT, requireInView = true))
            val retired = state.runtimeGeneration - 1
            org.junit.Assert.assertNull(state.removeUnlinkedAccountForOutcome(WIPED_ACCOUNT, retired))
            savedUnlinked = false
            org.junit.Assert.assertNull(state.removeUnlinkedAccountForOutcome(WIPED_ACCOUNT))
            org.junit.Assert.assertNull(nativeWipeTarget)
            assertEquals(SURVIVING_ACCOUNT, state.activeAccountRef)
        }

    private companion object {
        const val WIPED_ACCOUNT = "wiped-account"
        const val SURVIVING_ACCOUNT = "surviving-account"
        const val GROUP = "aa11bb22"
        const val MEMBER = "11aa22bb"
        const val PAUSED_READ_TIMEOUT_MS = 10_000L
        const val HANDLER_POLL_MS = 10L
    }
}
