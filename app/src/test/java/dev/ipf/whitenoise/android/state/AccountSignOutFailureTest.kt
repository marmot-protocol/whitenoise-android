package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.marmotkit.AppQuarantinedGroupFfi
import dev.ipf.marmotkit.RelayFailureFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.CONVERSATION_CHANNEL_OWNERS_PREFS
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.sha256Hex
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Native acknowledgement loss must never masquerade as durable local sign-out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class AccountSignOutFailureTest : ExternalSignerSignOutFixture() {
    /** Unknown durable status retains the session instead of guessing that cleanup committed. */
    @Test
    fun thrownReplyAndFailedStatusReadKeepTheActiveAccount() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic reply failure")
            listAccountsFailure = IllegalStateException("synthetic read failure")
            val state = appState()
            markReady(state)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(accountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertFalse(state.accounts.single().signedOut)
            assertEquals(1, listAccountsCalls.get())
            assertFalse(nativePushEnabled)
            assertColdBootstrap(nativeAccounts(), accountRef)
        }

    /** Losing a reply after native commit still retires the local account and preserves a signed-in successor. */
    @Test
    fun thrownReplyAfterNativeCommitCompletesLocalSignOutAndSelectsSuccessor() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic lost reply")
            persistSignedOutBeforePartialFailure = true
            additionalAccounts =
                listOf(externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "dd".repeat(32)))
            val state = appState(profileReader = { null })

            assertEquals(SignOutCompletion.RelayCleanupIncomplete, state.signOutActiveAccount())
            assertEquals(otherAccountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertTrue(state.accounts.first { it.label == accountRef }.signedOut)
        }

    /** The default UI path shows failure while startup sees the same still-signed-in native account. */
    @Test
    fun defaultUiSignOutFailureIsHonestAcrossColdBootstrap() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic native rejection")
            val state = appState()
            markReady(state)
            context
                .getSharedPreferences("whitenoise", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("active_account", accountRef)
                .commit()

            dev.ipf.whitenoise.android.ui.settings
                .signOutActiveAccount(state, deleteKeyPackages = true)
            awaitUiSignOut(state)

            assertEquals(AppText.Resource(R.string.toast_couldnt_sign_out), state.toast?.title)
            assertNull(state.transientNotice)
            assertEquals(accountRef, state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertEquals(
                accountRef,
                context
                    .getSharedPreferences("whitenoise", android.content.Context.MODE_PRIVATE)
                    .getString("active_account", null),
            )
            assertColdBootstrap(nativeAccounts(), accountRef)
        }

    /** Confirmed native commit with a lost reply stays signed out on restart and reports unknown relay cleanup. */
    @Test
    fun confirmedNativeSignOutSurvivesLostReplyAndColdBootstrap() =
        runBlocking {
            signOutFailure = IllegalStateException("synthetic lost reply")
            persistSignedOutBeforePartialFailure = true
            val state = appState()
            markReady(state)

            dev.ipf.whitenoise.android.ui.settings
                .signOutActiveAccount(state, deleteKeyPackages = true)
            awaitUiSignOut(state)

            assertEquals(AppText.Resource(R.string.toast_signed_out_relay_cleanup_incomplete), state.toast?.title)
            assertNull(state.activeAccountRef)
            assertEquals(AppPhase.Onboarding, state.phase)
            assertTrue(state.accounts.single().signedOut)
            assertColdBootstrap(nativeAccounts(), null)
        }

    /** Confirmed native sign-out with unfinished local cleanup reports signed out, not a failed sign-out (#3626). */
    @Test
    fun partialReplyAfterConfirmedSignOutReportsSignedOutWithLocalCleanupIncomplete() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup = dev.ipf.marmotkit.LocalCleanupReportFfi(false, "account worker still active"),
                )
            persistSignedOutBeforePartialFailure = true
            val state = appState()
            markReady(state)

            dev.ipf.whitenoise.android.ui.settings
                .signOutActiveAccount(state, deleteKeyPackages = true)
            awaitUiSignOut(state)

            assertEquals(AppText.Resource(R.string.toast_signed_out_local_cleanup_incomplete), state.toast?.title)
            assertNull(state.activeAccountRef)
            assertEquals(AppPhase.Onboarding, state.phase)
            assertTrue(state.accounts.single().signedOut)
            assertColdBootstrap(nativeAccounts(), null)
        }

    /** Ordinary relay failures remain best-effort cleanup after a successful native local commit. */
    @Test
    fun typedRelayFailureStillCompletesLocalSignOut() =
        runBlocking {
            signOutOutcome = signOutOutcome.copy(keyPackageFailures = listOf(RelayFailureFfi("", "unavailable")))
            val state = appState()
            assertEquals(SignOutCompletion.RelayCleanupIncomplete, state.signOutActiveAccount())
            assertNull(state.activeAccountRef)
            assertEquals(AppPhase.Onboarding, state.phase)
            assertTrue(state.accounts.single().signedOut)
        }

    /** Transient chat-list and quarantined-group read errors are retried before native sign-out revocation. */
    @Test
    fun captureAccountKnownGroupIdsRetriesTransientNativeReadsAndSavesOwners() =
        runBlocking {
            val chatGroup = "aa".repeat(16)
            val quarantinedGroup = "bb".repeat(16)
            val chatAttempts = AtomicInteger(0)
            val quarantinedAttempts = AtomicInteger(0)
            chatListReply = {
                if (chatAttempts.incrementAndGet() == 1) error("transient chatList lock")
                listOf(chatRow(chatGroup))
            }
            quarantinedGroupsReply = {
                if (quarantinedAttempts.incrementAndGet() == 1) error("transient quarantinedGroups lock")
                listOf(
                    AppQuarantinedGroupFfi(
                        quarantinedGroup,
                        AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_LOAD_FAILED,
                    ),
                )
            }
            val ownerPrefs = context.getSharedPreferences(CONVERSATION_CHANNEL_OWNERS_PREFS, Context.MODE_PRIVATE)
            ownerPrefs.edit().clear().commit()
            val state = appState()

            val captured = state.captureAccountKnownGroupIds(accountRef)

            assertEquals(2, chatAttempts.get())
            assertEquals(2, quarantinedAttempts.get())
            assertTrue(chatGroup in captured)
            assertTrue(quarantinedGroup in captured)
            val stored = ownerPrefs.getStringSet(sha256Hex(accountRef), emptySet()).orEmpty()
            assertTrue(checkNotNull(conversationShortcutId(accountRef, chatGroup)) in stored)
            assertTrue(checkNotNull(conversationShortcutId(accountRef, quarantinedGroup)) in stored)
        }

    /** Permanent inventory read errors still capture mounted chats and never trap sign-out on a degraded store. */
    @Test
    fun permanentInventoryReadFailureUsesMountedChatsWithoutBlockingSignOut() =
        runBlocking {
            val mountedGroup = "cc".repeat(16)
            val chatAttempts = AtomicInteger(0)
            chatListReply = {
                chatAttempts.incrementAndGet()
                error("corrupted chat table")
            }
            quarantinedGroupsReply = { error("corrupted quarantine table") }
            val state = appState()
            val controller = ChatsController(state, accountRef) { _, _ -> emptyList() }
            try {
                state.chatsController = controller
                controller.setChatListVisible(false)
                controller.applyChatListRow(chatRow(mountedGroup))
                controller.setChatListVisible(true)

                val captured = state.captureAccountKnownGroupIds(accountRef)

                assertEquals(3, chatAttempts.get())
                assertTrue(mountedGroup in captured)
                assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())
            } finally {
                controller.onCleared()
            }
        }

    /** Pump the actual app-owned UI mutation rather than substituting a direct completion mapper. */
    private suspend fun awaitUiSignOut(state: WhiteNoiseAppState) {
        withTimeout(10_000L) {
            while (state.signOutInProgress) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(1L)
            }
        }
    }

    /** Read the fake's durable native state independently of Android's post-operation account cache. */
    private fun nativeAccounts() = listOf(externalSignerAccount(signedOut = engineSignedOut)) + additionalAccounts

    /** A fresh runtime consumes the durable native snapshot and the saved host selection without reactivation. */
    private suspend fun assertColdBootstrap(
        accounts: List<dev.ipf.marmotkit.AccountSummaryFfi>,
        expectedRef: String?,
    ) {
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                accounts = accounts,
                initialActiveAccountRef = null,
                emitStartupNotification = false,
            )
        try {
            fixture.bootstrap()
            assertEquals(expectedRef, fixture.appState.activeAccountRef)
            assertEquals(if (expectedRef == null) AppPhase.Onboarding else AppPhase.Ready, fixture.appState.phase)
            assertTrue(fixture.signInRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }
}
