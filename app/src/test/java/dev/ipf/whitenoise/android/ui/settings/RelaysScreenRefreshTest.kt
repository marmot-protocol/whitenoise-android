package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.relayListRefreshRelays
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy

/**
 * Refresh status reads the account's relay lists from relays, not only from the local cache, so a list published from
 * another client hides Publish missing lists before the user can overwrite it (#3818).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1600dp-mdpi")
class RelaysScreenRefreshTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val fetches = mutableListOf<Pair<String, List<String>>>()

    /** An Inbox list the cache never saw is found by Refresh, which then hides Publish missing lists. */
    @Test
    fun refreshFindsAnInboxListPublishedElsewhereAndHidesPublish() {
        show { relayLists(nip65 = listOf(POST), inbox = listOf(INBOX)) }
        awaitTag(PUBLISH, present = true)

        compose.onNodeWithTag(REFRESH).performClick()

        awaitTag(PUBLISH, present = false)
        compose.onAllNodesWithText(app.getString(R.string.relay_list_published)).assertCountEquals(2)
        assertEquals(listOf(ACCOUNT_ID to MarmotClient.discoveryRelays + POST), fetches)
    }

    /** A failed fetch keeps the cached status and says so, and Retry fetches from the relays again. */
    @Test
    fun failedRefreshKeepsTheLastKnownStatusAndRetryFetchesAgain() {
        show { error("relays did not answer") }
        awaitTag(PUBLISH, present = true)

        compose.onNodeWithTag(REFRESH).performClick()

        awaitTag(RETRY, present = true)
        compose.onNodeWithText(app.getString(R.string.relay_list_refresh_failed)).assertExists()
        compose.onNodeWithText(app.getString(R.string.relay_list_missing)).assertExists()
        compose.onNodeWithTag(RETRY).performClick()
        awaitCondition { fetches.size == 2 }
    }

    /** Refresh asks every discovery relay, then each cached NIP-65 relay once, even one the publish check refuses. */
    @Test
    fun refreshRelaysAreDiscoveryThenCachedNip65WithoutRepeats() {
        val cached = listOf(POST, MarmotClient.discoveryRelays.first(), CUSTOM_PORT)

        assertEquals(MarmotClient.discoveryRelays + POST + CUSTOM_PORT, relayListRefreshRelays(cached))
    }

    /** Shows the Relays screen for an account whose cache lacks an Inbox list; [fetched] answers each refresh. */
    private fun show(fetched: () -> AccountRelayListsFfi) {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { ACCOUNT_ID },
                accounts = listOf(AccountSummaryFfi(ACCOUNT_REF, ACCOUNT_ID, true, false, false, true, false)),
                activeAccountRef = ACCOUNT_REF,
                initialMarmotRuntime = AppMarmotRuntime("relays-refresh-test", native(fetched)),
                marmotIoDispatcher = Dispatchers.Unconfined,
            )
        compose.setContent { WhiteNoiseTheme(darkTheme = false) { RelaysScreen(appState = appState, onBack = {}) } }
    }

    /** A MarmotKit fake whose cache lacks an Inbox list and whose relay fetch is recorded and answered by [fetched]. */
    private fun native(fetched: () -> AccountRelayListsFfi): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "accountRelayLists" -> relayLists(listOf(POST), emptyList(), listOf(MissingRelayListKindFfi.INBOX))
                "refreshUserRelayLists" -> {
                    @Suppress("UNCHECKED_CAST")
                    fetches += args[0] as String to args[1] as List<String>
                    fetched()
                }
                "toString" -> "RelaysRefreshFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args.firstOrNull()
                else -> error("unexpected native call ${method.name}")
            }
        } as MarmotInterface

    /** Waits until a node tagged [tag] exists or not, idling the main looper the mutation scope runs on. */
    private fun awaitTag(
        tag: String,
        present: Boolean,
    ) = awaitCondition { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == present }

    /** Waits for [condition], idling the main looper so work posted back from the mutation scope can finish. */
    private fun awaitCondition(condition: () -> Boolean) =
        compose.waitUntil(WAIT_MILLIS) {
            ShadowLooper.idleMainLooper()
            condition()
        }

    private companion object {
        const val ACCOUNT_REF = "relays-account"
        val ACCOUNT_ID = "a1".repeat(32)
        const val POST = "wss://post.example.com"
        const val INBOX = "wss://inbox.example.com"
        const val CUSTOM_PORT = "wss://relay.example.com:7777"
        const val PUBLISH = "relay.publication.publish"
        const val REFRESH = "relay.publication.refresh"
        const val RETRY = "relay.publication.retry"
        const val WAIT_MILLIS = 5_000L
    }
}
