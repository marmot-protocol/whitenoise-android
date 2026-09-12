package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Contract of the Relays list: publication status, contextual actions, one row per relay, and Restore defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1600dp-mdpi")
class RelaysContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var opened: String? = null
    private var adds = 0
    private var refreshes = 0
    private var publishes = 0
    private var restores = 0

    /** A relay in both lists appears once with both roles; a posting-only relay carries one role. */
    @Test
    fun relaysAreUnitedAcrossListsInFirstSeenOrder() {
        val relays = accountRelays(relayLists(nip65 = listOf(A, B), inbox = listOf(B, C)))
        assertEquals(listOf(A, B, C), relays.map { it.url })
        assertEquals(setOf(AccountRelayRole.Profile), relays[0].roles)
        assertEquals(setOf(AccountRelayRole.Profile, AccountRelayRole.Inbox), relays[1].roles)
        assertEquals("b.example.com", relays[1].name)
    }

    /** Both lists published: Published statuses, Refresh status only, and Restore enabled away from the defaults. */
    @Test
    fun publishedListsOfferRefreshOnly() {
        render(relayLists(nip65 = listOf(POST), inbox = listOf(INBOX)))
        composeRule.onNodeWithTag("relay.publication.posting").assertExists()
        composeRule
            .onAllNodesWithText(app.getString(R.string.relay_list_published))
            .assertCountEquals(2)
        composeRule.onNodeWithText(app.getString(R.string.relay_list_publish)).assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.relay_lists_published_help)).assertExists()
        composeRule.onNodeWithTag("relay.publication.refresh").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshes) }
        composeRule.onNodeWithText("post.example.com").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(POST, opened) }
        composeRule.onNodeWithText(app.getString(R.string.add_relay)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, adds) }
        composeRule
            .onNodeWithTag("relays.restore")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, restores) }
    }

    /** A missing inbox list reads Missing and offers Publish missing lists. */
    @Test
    fun missingListOffersPublish() {
        render(relayLists(nip65 = listOf(POST), inbox = emptyList(), missing = listOf(MissingRelayListKindFfi.INBOX)))
        composeRule.onNodeWithText(app.getString(R.string.relay_list_missing)).assertExists()
        composeRule.onNodeWithText(app.getString(R.string.relay_lists_missing_help)).assertExists()
        composeRule.onNodeWithTag("relay.publication.publish").performClick()
        composeRule.runOnIdle { assertEquals(1, publishes) }
    }

    /** Without a projection both statuses are unavailable and the saved-settings explainer shows. */
    @Test
    fun unavailableProjectionShowsUnavailableStatuses() {
        render(lists = null)
        composeRule.onNodeWithText(app.getString(R.string.relay_lists_unavailable_help)).assertExists()
        composeRule.onNodeWithTag("relays.restore").performScrollTo().assertIsNotEnabled()
    }

    /** While refreshing the progress row replaces the actions; after a failure Retry names the failed operation. */
    @Test
    fun runningAndFailedPublicationShowProgressThenRetry() {
        render(
            relayLists(nip65 = listOf(POST), inbox = listOf(INBOX)),
            publication = RelayPublicationState(running = RelayPublicationOperation.Refresh),
        )
        composeRule.onNodeWithText(app.getString(R.string.relay_list_refreshing)).assertExists()
        composeRule.onNodeWithTag("relay.publication.refresh").assertDoesNotExist()
    }

    /** A failed publish keeps the last status and offers Retry with the publish failure note. */
    @Test
    fun failedPublishOffersRetry() {
        render(
            relayLists(nip65 = listOf(POST), inbox = emptyList(), missing = listOf(MissingRelayListKindFfi.INBOX)),
            publication = RelayPublicationState(failed = RelayPublicationOperation.PublishMissing),
        )
        composeRule.onNodeWithText(app.getString(R.string.relay_list_publish_failed)).assertExists()
        composeRule.onNodeWithTag("relay.publication.retry").performClick()
        composeRule.runOnIdle { assertEquals(1, publishes) }
    }

    /** An imported insecure address stays listed, flagged, with the callout above the lists. */
    @Test
    fun importedInsecureRelayIsFlaggedNotDropped() {
        render(relayLists(nip65 = listOf(POST, LEGACY), inbox = emptyList()))
        composeRule.onNodeWithTag("relays.imported.issue").assertExists()
        composeRule.onNodeWithText("legacy.example.com").performScrollTo().assertExists()
        composeRule.onNodeWithText("$LEGACY · " + app.getString(R.string.relay_needs_attention)).assertExists()
    }

    /** Restore default relays is disabled while both lists already equal the defaults. */
    @Test
    fun restoreIsDisabledAtDefaults() {
        val lists = relayLists(nip65 = listOf(A), inbox = listOf(A), defaults = listOf(A))
        assertTrue(lists.usesDefaultRelays())
        assertFalse(relayLists(nip65 = listOf(B), inbox = listOf(A), defaults = listOf(A)).usesDefaultRelays())
        render(lists)
        composeRule.onNodeWithTag("relays.restore").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(app.getString(R.string.relay_defaults_in_use)).assertExists()
        composeRule.onAllNodesWithText(app.getString(R.string.relay_list_published)).assertCountEquals(2)
    }

    /** Publication disables every path to an edit, including opening a relay's role switches. */
    @Test
    fun publicationDisablesAddRestoreAndRelayDetails() {
        render(
            relayLists(nip65 = listOf(POST), inbox = emptyList(), missing = listOf(MissingRelayListKindFfi.INBOX)),
            publication = RelayPublicationState(running = RelayPublicationOperation.PublishMissing),
        )
        composeRule.onNodeWithText(app.getString(R.string.add_relay)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("relays.restore").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("relays.row.$POST").performScrollTo().assertIsNotEnabled()
    }

    /** An in-flight edit disables both refresh and missing-list recovery so their snapshots cannot race. */
    @Test
    fun editDisablesPublicationAndRefresh() {
        render(
            relayLists(nip65 = listOf(POST), inbox = emptyList(), missing = listOf(MissingRelayListKindFfi.INBOX)),
            busy = true,
        )
        composeRule.onNodeWithTag("relay.publication.refresh").assertIsNotEnabled()
        composeRule.onNodeWithTag("relay.publication.publish").assertIsNotEnabled()
    }

    /** After one role was added, retry adds only the remaining selected role instead of rejecting the URL. */
    @Test
    fun partialAddCanRetryTheMissingRole() {
        val existing = listOf(AccountRelay(A, setOf(AccountRelayRole.Profile)))
        assertEquals(
            setOf(AccountRelayRole.Inbox),
            missingRelayRoles(existing, A, AccountRelayRole.entries.toSet()),
        )
        assertTrue(missingRelayRoles(existing, A, setOf(AccountRelayRole.Profile)).isEmpty())
    }

    /** Reopening a screen shares its account's gate; switching accounts gets an independent operation state. */
    @Test
    fun operationGateSurvivesScreenVisitsAndSeparatesAccounts() {
        val appState =
            WhiteNoiseAppState(
                context = app,
                draftStore = DraftStore.forContext(app),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "one",
            )
        assertSame(appState.relayOperationState("one"), appState.relayOperationState("one"))
        assertNotSame(appState.relayOperationState("one"), appState.relayOperationState("two"))
    }

    /** Renders the list content with counting callbacks. */
    private fun render(
        lists: AccountRelayListsFfi?,
        publication: RelayPublicationState = RelayPublicationState(),
        busy: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                RelaysContent(
                    state = RelaysUiState(lists = lists, publication = publication, busy = busy),
                    onBack = {},
                    onOpenRelay = { opened = it },
                    onAdd = { adds++ },
                    onRefresh = { refreshes++ },
                    onPublishMissing = { publishes++ },
                    onRestore = { restores++ },
                )
            }
        }
    }

    private companion object {
        const val A = "wss://a.example.com"
        const val B = "wss://b.example.com"
        const val C = "wss://c.example.com"
        const val POST = "wss://post.example.com"
        const val INBOX = "wss://inbox.example.com"
        const val LEGACY = "ws://legacy.example.com"
    }
}

/** One account relay-list projection with the given lists; missing kinds mark it incomplete. */
internal fun relayLists(
    nip65: List<String>,
    inbox: List<String>,
    missing: List<MissingRelayListKindFfi> = emptyList(),
    defaults: List<String> = emptyList(),
): AccountRelayListsFfi =
    AccountRelayListsFfi(
        complete = missing.isEmpty(),
        missing = missing,
        defaultRelays = defaults,
        bootstrapRelays = emptyList(),
        nip65 = RelayListFfi(kind = 10_002uL, relays = nip65),
        inbox = RelayListFfi(kind = 10_050uL, relays = inbox),
    )
