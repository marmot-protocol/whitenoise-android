package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.IdentityFormatter
import kotlinx.coroutines.test.runTest
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
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
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
        runTest {
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

    private fun appState(
        native: MarmotInterface? = null,
        readDisplayName: suspend (String) -> String? = { null },
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
            profileRefreshRequest = {},
            npubPresentationReader = readIdentity.takeIf { native == null },
            initialMarmotRuntime = native?.let { AppMarmotRuntime("search-native-fixture", it) },
        )
    }

    private companion object {
        const val OWNER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PEER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        // Synthetic native-reader fixture, matching the existing presentation contract test shape.
        const val CANONICAL_NPUB = "npub1abcdefghijklmnopqrstuvwxyz234567"
    }
}
