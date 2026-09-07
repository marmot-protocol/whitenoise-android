package dev.ipf.whitenoise.android.state

import android.app.Application
import android.os.Looper
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exercises retained-account activation through the production local-read and publication path. */
@RunWith(RobolectricTestRunner::class)
class StartupSelfProfilePresentationTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    /** A reopened account publishes sanitized local identity before its activation callback. */
    @Test
    fun retainedProfileIsReadyAtActivationWithoutRosterOrNetworkReads() =
        runBlocking {
            val readOffMain = AtomicBoolean(false)
            val fixture =
                fixture { id ->
                    readOffMain.set(Looper.myLooper() != Looper.getMainLooper())
                    profile(id)
                }
            try {
                fixture.bootstrap()
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("Alice", fixture.appState.chatMemberTitleCached(SELF))
                assertEquals(AVATAR, fixture.appState.avatarUrl(SELF))
                assertTrue(readOffMain.get())
                assertEquals(0, fixture.memberProjectionCalls.get())
                assertEquals("Alice", fixture.appState.userProfileCached(SELF)?.displayName)
            } finally {
                fixture.close()
            }
        }

    /** Failure of the separate chat-row projection cannot discard a successfully read self profile. */
    @Test
    fun failedChatRowReadStillPublishesTheRetainedProfile() =
        runBlocking {
            val fixture = fixture(failRows = true, read = ::profile)
            try {
                fixture.bootstrap()
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertEquals("Alice", fixture.appState.chatMemberTitleCached(SELF))
                assertEquals(AVATAR, fixture.appState.avatarUrl(SELF))
                assertNull(fixture.appState.consumeAccountSwitchLocalSnapshot("self"))
            } finally {
                fixture.close()
            }
        }

    /** A later real profile clear applies, while an absent local read preserves a useful seed. */
    @Test
    fun sameAccountRestorationPreservesTransientMissAndHonorsAuthoritativeRemoval() =
        runBlocking {
            val persisted = AtomicReference<UserProfileMetadataFfi?>(profile(SELF))
            val fixture = fixture { persisted.get() }
            try {
                fixture.bootstrap()
                persisted.set(null)
                fixture.appState.setActiveAccount(
                    "self",
                    preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                )
                assertEquals(AVATAR, fixture.appState.avatarUrl(SELF))
                persisted.set(profile(SELF).copy(picture = null, displayName = "Updated"))
                fixture.appState.setActiveAccount(
                    "self",
                    preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                )
                assertNull(fixture.appState.avatarUrl(SELF))
                assertEquals("Updated", fixture.appState.chatMemberTitleCached(SELF))
            } finally {
                fixture.close()
            }
        }

    /** A later authoritative picture removal wins even when an older SQLite read finishes afterward. */
    @Test
    fun newerProfileUpdateWinsOverDelayedStartupRead() =
        runBlocking {
            val hold = AtomicBoolean(false)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                fixture { id ->
                    val captured = profile(id)
                    if (hold.get()) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    captured
                }
            try {
                fixture.bootstrap()
                hold.set(true)
                val pending =
                    async {
                        fixture.appState.setActiveAccount(
                            "self",
                            preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                        )
                    }
                withTimeout(3_000) { while (entered.count > 0) yield() }
                fixture.appState.applyAccountSwitchProfileSeed(
                    accountSwitchProfileSeed(SELF, profile(SELF).copy(displayName = "Updated", picture = null), null),
                )
                release.countDown()
                assertTrue(pending.await())
                assertEquals("Updated", fixture.appState.chatMemberTitleCached(SELF))
                assertNull(fixture.appState.avatarUrl(SELF))
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** Unknown local metadata yields an immediately usable fallback without roster enrichment. */
    @Test
    fun genuineLocalMissStillReachesReady() =
        runBlocking {
            val fixture = fixture { null }
            try {
                fixture.bootstrap()
                assertEquals(AppPhase.Ready, fixture.appState.phase)
                assertNull(fixture.appState.avatarUrl(SELF))
                assertTrue(
                    fixture.appState
                        .consumeAccountSwitchLocalSnapshot("self")
                        ?.profiles
                        ?.isEmpty() == true,
                )
            } finally {
                fixture.close()
            }
        }

    /** A slow B read cannot replace the newer A activation or resurrect B's cached identity. */
    @Test
    fun delayedProfileCannotCrossRapidAccountReactivation() =
        runBlocking {
            val holdOther = AtomicBoolean(false)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                fixture { id ->
                    if (id == OTHER && holdOther.get()) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    profile(id)
                }
            try {
                fixture.bootstrap()
                holdOther.set(true)
                val old =
                    async {
                        fixture.appState.setActiveAccount(
                            "other",
                            preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                        )
                    }
                withTimeout(3_000) { while (entered.count > 0) yield() }
                assertTrue(
                    fixture.appState.setActiveAccount(
                        "self",
                        preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                    ),
                )
                release.countDown()
                assertFalse(old.await())
                assertEquals("self", fixture.appState.activeAccountRef)
                assertEquals(AVATAR, fixture.appState.avatarUrl(SELF))
                assertEquals("self", fixture.appState.consumeAccountSwitchLocalSnapshot("self")?.accountRef)
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** Cancellation while SQLite is still reading must not publish the target account or its seed. */
    @Test
    fun cancelledLocalReadNeverActivatesTheTarget() =
        runBlocking {
            val holdOther = AtomicBoolean(false)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                fixture { id ->
                    if (id == OTHER && holdOther.get()) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    profile(id)
                }
            try {
                fixture.bootstrap()
                holdOther.set(true)
                var activated = false
                val pending =
                    async {
                        fixture.appState.setActiveAccount(
                            "other",
                            preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                            onActivated = { activated = true },
                        )
                    }
                withTimeout(3_000) { while (entered.count > 0) yield() }
                pending.cancel()
                release.countDown()
                pending.join()
                assertFalse(activated)
                assertEquals("self", fixture.appState.activeAccountRef)
                assertEquals(AVATAR, fixture.appState.avatarUrl(SELF))
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** A removed target cannot be resurrected by a local read that began before the account refresh. */
    @Test
    fun accountDeletionDuringReadRejectsActivation() =
        runBlocking {
            val accountSnapshot = mutableListOf(account("self", SELF), account("other", OTHER))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                fixture(accountSnapshot = accountSnapshot) { id ->
                    if (id == OTHER) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    profile(id)
                }
            try {
                fixture.bootstrap()
                val pending =
                    async {
                        fixture.appState.setActiveAccount(
                            "other",
                            preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                        )
                    }
                withTimeout(3_000) { while (entered.count > 0) yield() }
                accountSnapshot.removeAll { it.label == "other" }
                fixture.appState.refreshAccounts()
                release.countDown()
                assertFalse(pending.await())
                assertEquals("self", fixture.appState.activeAccountRef)
                assertNull(fixture.appState.consumeAccountSwitchLocalSnapshot("other"))
                assertEquals(AVATAR, fixture.appState.avatarUrl(SELF))
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** Supplies local-only profile reads; unsupported network calls cannot populate this fixture. */
    private fun fixture(
        failRows: Boolean = false,
        accountSnapshot: List<AccountSummaryFfi> = listOf(account("self", SELF), account("other", OTHER)),
        read: (String) -> UserProfileMetadataFfi?,
    ) = NotificationBootstrapTestFixture(
        context = context,
        accounts = accountSnapshot,
        emitStartupNotification = false,
        localDisplayName = null,
        onUserProfile = read,
        onChatList = { if (failRows) error("synthetic local row failure") else emptyList() },
    )

    /** Stable synthetic account metadata avoids any dependency on personal accounts. */
    private fun account(
        label: String,
        id: String,
    ) = AccountSummaryFfi(label, id, true, false, false, true)

    /** A sanitizable persisted profile carries metadata only; avatar pixels are deliberately absent. */
    private fun profile(id: String) =
        UserProfileMetadataFfi(
            name = null,
            displayName = if (id == SELF) "Alice" else "Bob",
            about = null,
            picture = if (id == SELF) AVATAR else "https://profiles.example/bob.png",
            nip05 = null,
            lud16 = null,
        )

    private companion object {
        val SELF = "11".repeat(32)
        val OTHER = "22".repeat(32)
        const val AVATAR = "https://profiles.example/alice.png"
    }
}
