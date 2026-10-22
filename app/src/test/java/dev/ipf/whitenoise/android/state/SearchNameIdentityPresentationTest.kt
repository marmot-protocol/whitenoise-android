package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.IdentityFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Rendering a cold name never crosses the native identity boundary on the caller's thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchNameIdentityPresentationTest {
    @Test
    fun coldNameRendersUnknownThenExistingLocalMaterializationPublishesCanonicalIdentity() =
        runIdentityPresentationTest {
            val reads = AtomicInteger()
            val appState =
                appState {
                    assertFalse(Thread.currentThread() === Looper.getMainLooper().thread)
                    reads.incrementAndGet()
                    CANONICAL_NPUB
                }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val before = appState.profileRevisionForCompose
            assertEquals(
                context.getString(dev.ipf.whitenoise.android.R.string.unknown),
                appState.contactDisplayNameCached(PEER),
            )
            assertEquals(0, reads.get())

            appState.warmProfilePresentationsBlocking(listOf(PEER))

            assertEquals(1, reads.get())
            assertNotEquals(before, appState.profileRevisionForCompose)
            val expected = IdentityFormatter.short(CANONICAL_NPUB, prefix = 10, suffix = 8)
            assertEquals(expected, appState.contactDisplayNameCached(PEER))
            assertEquals(expected, appState.chatMemberTitleCached(PEER))
            appState.setContactNickname(PEER, "Local alias")
            assertEquals("Local alias", appState.contactDisplayNameCached(PEER))
            assertEquals(1, reads.get())
        }

    @Test
    fun invalidEncodingDoesNotEscapeAsHexOrRepeatPerKeyAndExistingRefreshCanRecover() =
        runIdentityPresentationTest {
            val reads = AtomicInteger()
            var encoded = PEER
            val appState =
                appState {
                    reads.incrementAndGet()
                    encoded
                }
            val unknown =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .getString(dev.ipf.whitenoise.android.R.string.unknown)
            appState.warmProfilePresentationsBlocking(listOf(PEER))
            repeat(100) { assertEquals(unknown, appState.contactDisplayNameCached(PEER)) }
            assertEquals(1, reads.get())
            val before = appState.profileRevisionForCompose
            encoded = CANONICAL_NPUB
            appState.refreshProfile(PEER)
            assertNotEquals(before, appState.profileRevisionForCompose)
            assertEquals(2, reads.get())
            assertEquals(IdentityFormatter.short(CANONICAL_NPUB, 10, 8), appState.contactDisplayNameCached(PEER))
        }

    @Test
    fun identityOnlyRefreshLeavesLaterLocalNameMaterializationAvailable() =
        runIdentityPresentationTest {
            var localName: String? = null
            val nameReads = AtomicInteger()
            val identityReads = AtomicInteger()
            val appState =
                appState(readDisplayName = {
                    nameReads.incrementAndGet()
                    localName
                }) {
                    identityReads.incrementAndGet()
                    CANONICAL_NPUB
                }
            appState.refreshProfile(PEER)
            assertEquals(1, nameReads.get())
            assertEquals(1, identityReads.get())

            localName = "Recently cached person"
            appState.warmProfilePresentationsBlocking(listOf(PEER))

            assertEquals("Recently cached person", appState.contactDisplayNameCached(PEER))
            assertEquals(2, nameReads.get())
            assertEquals(1, identityReads.get())
        }

    @Test
    fun staleRefreshCannotPublishAnIdentityIntoTheCurrentPresentationLifetime() =
        runIdentityPresentationTest {
            val appState = appState { CANONICAL_NPUB }
            val before = appState.profileRevisionForCompose
            appState.refreshProfile(PEER, epoch = -1L)
            val unknown =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .getString(dev.ipf.whitenoise.android.R.string.unknown)
            assertEquals(unknown, appState.contactDisplayNameCached(PEER))
            assertEquals(before, appState.profileRevisionForCompose)
        }

    @Test
    fun nativeRuntimeEncodingPublishesOnlyAfterLifetimeFenceAndBumpsIdentityOnlyRevision() =
        runIdentityPresentationTest {
            val reads = AtomicInteger()
            var encoded = PEER
            val runtime =
                nativeRuntime {
                    assertFalse(Thread.currentThread() === Looper.getMainLooper().thread)
                    reads.incrementAndGet()
                    encoded
                }
            val appState = appState(native = runtime) { null }
            appState.warmProfilePresentationsBlocking(listOf(PEER))
            val before = appState.profileRevisionForCompose
            encoded = CANONICAL_NPUB
            appState.refreshProfile(PEER)
            assertNotEquals(before, appState.profileRevisionForCompose)
            assertEquals(2, reads.get())
            assertEquals(IdentityFormatter.short(CANONICAL_NPUB, 10, 8), appState.contactDisplayNameCached(PEER))

            val stale = appState(native = runtime) { null }
            val beforeStale = stale.profileRevisionForCompose
            stale.refreshProfile(PEER, epoch = -1L)
            val unknown =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .getString(dev.ipf.whitenoise.android.R.string.unknown)
            assertEquals(unknown, stale.contactDisplayNameCached(PEER))
            assertEquals(beforeStale, stale.profileRevisionForCompose)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun readableLocalNameNeedsNoCanonicalEncodingDuringWarmOrCooldownRequests() =
        runIdentityPresentationTest {
            val identityReads = AtomicInteger()
            val appState =
                appState(readDisplayName = { "Known local person" }) {
                    identityReads.incrementAndGet()
                    CANONICAL_NPUB
                }
            appState.warmProfilePresentationsBlocking(listOf(PEER))
            assertEquals("Known local person", appState.contactDisplayNameCached(PEER))
            assertEquals(0, identityReads.get())
            val gate =
                WhiteNoiseAppState::class.java
                    .getDeclaredField("profileRefreshGate")
                    .apply { isAccessible = true }
                    .get(appState) as ProfileRefreshGate
            gate.finish(PEER, System.currentTimeMillis() + 60_000L)
            repeat(20) { appState.requestProfile(PEER) }
            runCurrent()
            assertEquals("Known local person", appState.chatMemberTitleCached(PEER))
            assertEquals(0, identityReads.get())
        }

    @Test
    fun namelessAccountSwitchSeedHydratesIdentityDuringRelayCooldownWithoutDuplicateReads() {
        assertSeededIdentityDuringCooldown(staleLifetime = false)
    }

    @Test
    fun heldSeededIdentityReadCannotPublishAfterAccountCacheLifetimeChanges() {
        assertSeededIdentityDuringCooldown(staleLifetime = true)
    }

    /** Holds the shared identity read across cooldown and optional account-lifetime invalidation. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun assertSeededIdentityDuringCooldown(staleLifetime: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val releaseRead = CompletableDeferred<Unit>()
            var profileJob: Job? = null
            try {
                val readStarted = CompletableDeferred<Unit>()
                val identityReads = AtomicInteger()
                val nameReads = AtomicInteger()
                val relayReads = AtomicInteger()
                val appState =
                    appState(
                        readDisplayName = {
                            nameReads.incrementAndGet()
                            null
                        },
                        refreshRequest = { relayReads.incrementAndGet() },
                    ) {
                        assertFalse(Thread.currentThread() === Looper.getMainLooper().thread)
                        identityReads.incrementAndGet()
                        readStarted.complete(Unit)
                        releaseRead.await()
                        CANONICAL_NPUB
                    }
                profileJob = profileScope(appState).coroutineContext[Job]
                appState.applyAccountSwitchProfileSeed(accountSwitchProfileSeed(PEER, null, null))
                coolDownProfileRefresh(appState)
                val before = appState.profileRevisionForCompose
                repeat(20) { appState.requestProfile(PEER) }
                runCurrent()
                readStarted.await()
                assertEquals(1, identityReads.get())
                assertEquals(0, nameReads.get())
                assertEquals(0, relayReads.get())
                val warm = async { appState.warmProfilePresentationsBlocking(listOf(PEER)) }
                runCurrent()
                if (staleLifetime) {
                    val lifetime =
                        WhiteNoiseAppState::class.java
                            .getDeclaredField("profileCacheLifetime")
                            .apply { isAccessible = true }
                            .get(appState) as StalenessGuard
                    lifetime.advance()
                }
                releaseRead.complete(Unit)
                warm.await()
                assertSeededPresentation(appState, staleLifetime, before)
                assertEquals(1, identityReads.get())
                assertEquals(0, nameReads.get())
                assertEquals(0, relayReads.get())
            } finally {
                releaseRead.complete(Unit)
                try {
                    withContext(NonCancellable) { profileJob?.cancelAndJoin() }
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }

    /** A retired read must leave both fallback text and revision untouched; a current read publishes once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.assertSeededPresentation(
        appState: WhiteNoiseAppState,
        staleLifetime: Boolean,
        before: ProfilePresentationRevision,
    ) {
        if (staleLifetime) {
            val unknown =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .getString(dev.ipf.whitenoise.android.R.string.unknown)
            assertEquals(unknown, appState.chatMemberTitleCached(PEER))
            assertEquals(before, appState.profileRevisionForCompose)
        } else {
            assertEquals(IdentityFormatter.short(CANONICAL_NPUB, 10, 8), appState.chatMemberTitleCached(PEER))
            assertNotEquals(before, appState.profileRevisionForCompose)
            appState.requestProfile(PEER)
            runCurrent()
        }
    }

    /** Returns the actual materialization owner so the fixture joins its children before resetting Main. */
    private fun profileScope(appState: WhiteNoiseAppState): CoroutineScope =
        WhiteNoiseAppState::class.java
            .getDeclaredField("profileScope")
            .apply { isAccessible = true }
            .get(appState) as CoroutineScope

    /** Keeps relay refresh in cooldown to exercise local hydration independently of network admission. */
    private fun coolDownProfileRefresh(appState: WhiteNoiseAppState) {
        val gate =
            WhiteNoiseAppState::class.java
                .getDeclaredField("profileRefreshGate")
                .apply { isAccessible = true }
                .get(appState) as ProfileRefreshGate
        gate.finish(PEER, System.currentTimeMillis() + 60_000L)
    }

    private val createdAppStates = mutableListOf<WhiteNoiseAppState>()

    /** Drives Main publication on the test scheduler and joins every created state before restoring Main. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun runIdentityPresentationTest(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                try {
                    withContext(NonCancellable) {
                        createdAppStates.forEach { appState ->
                            val scope =
                                WhiteNoiseAppState::class.java
                                    .getDeclaredField("profileScope")
                                    .apply { isAccessible = true }
                                    .get(appState) as CoroutineScope
                            scope.coroutineContext[Job]?.cancelAndJoin()
                        }
                    }
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }

    /** Permits only authoritative identity encoding; unexpected native work fails the fixture. */
    private fun nativeRuntime(encode: () -> String): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "npub" -> encode()
                else -> error("Unexpected native call: ${method.name}")
            }
        } as MarmotInterface

    /** Pins one synthetic account and injected readers while retaining the production profile scope. */
    private fun appState(
        native: MarmotInterface? = null,
        readDisplayName: suspend (String) -> String? = { null },
        refreshRequest: suspend (String) -> Unit = {},
        readIdentity: suspend (String) -> String?,
    ): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { OWNER },
            accounts = listOf(AccountSummaryFfi("search-owner", OWNER, true, false, false, true)),
            activeAccountRef = "search-owner",
            profileReader = { null },
            profileDisplayNameReader = readDisplayName,
            profileRefreshRequest = refreshRequest,
            npubPresentationReader = readIdentity.takeIf { native == null },
            initialMarmotRuntime = native?.let { AppMarmotRuntime("search-native-fixture", it) },
        ).also { createdAppStates += it }
    }

    private companion object {
        const val OWNER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PEER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        // Synthetic native-reader fixture, matching the existing presentation contract test shape.
        const val CANONICAL_NPUB = "npub1abcdefghijklmnopqrstuvwxyz234567"
    }
}
