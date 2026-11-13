package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.marmotkit.RelayLivenessFfi
import dev.ipf.marmotkit.RelayStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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

    /** Different rows update from native status without changing the publication state. */
    @Test
    fun livenessUpdatesTheMatchingRow() {
        val configured = "WSS://A.example.com:443/"
        val nonDefaultPort = "$B:8443"
        val lists = relayLists(nip65 = listOf(configured, nonDefaultPort), inbox = listOf(C))
        val a = RelayLivenessFfi(A, RelayStatusFfi.CONNECTING, null, null)
        val b = RelayLivenessFfi(nonDefaultPort, RelayStatusFfi.OFFLINE, 1_700_000_000uL, null)
        val c = RelayLivenessFfi(C, RelayStatusFfi.UNKNOWN, null, null)
        val endpoints =
            mapOf(
                configured to allowedEndpoint(configured, "$A/"),
                nonDefaultPort to allowedEndpoint(nonDefaultPort, nonDefaultPort),
            )
        val urls = listOf(configured, nonDefaultPort, C)
        val initialConnections = projectRelayConnections(urls, endpoints, listOf(a, b, c))
        val state = mutableStateOf(RelaysUiState(lists, connections = initialConnections))
        renderState { state.value }

        assertConnection(configured, R.string.relay_connection_connecting)
        assertConnection(nonDefaultPort, R.string.relay_connection_offline)
        composeRule.onNodeWithTag("relays.imported.unusable").assertDoesNotExist()
        composeRule.onNodeWithTag("relays.imported.editor_restriction").assertExists()
        assertConnection(C, R.string.relay_connection_unknown)

        composeRule.runOnIdle {
            val connected = a.copy(status = RelayStatusFfi.CONNECTED)
            val connections = projectRelayConnections(urls, endpoints, listOf(connected))
            state.value = state.value.copy(connections = connections)
        }
        assertConnection(configured, R.string.relay_connection_connected)
        assertConnection(nonDefaultPort, R.string.relay_list_status_unavailable)
    }

    /** Use native identities even when two spellings would collapse under a Java URL parser. */
    @Test
    fun livenessKeysPreserveDistinctNativeEndpoints() {
        val dotPath = "$A/room/."
        val trailingSlash = "$A/room/"
        val offline = RelayLivenessFfi("$A/room", RelayStatusFfi.OFFLINE, null, null)
        val connected = RelayLivenessFfi(trailingSlash, RelayStatusFfi.CONNECTED, null, null)
        val endpoints =
            mapOf(
                dotPath to allowedEndpoint(dotPath, offline.url),
                trailingSlash to allowedEndpoint(trailingSlash, connected.url),
            )
        val connections = projectRelayConnections(listOf(dotPath, trailingSlash), endpoints, listOf(offline, connected))
        assertEquals(offline, connections[dotPath]?.liveness)
        assertEquals(connected, connections[trailingSlash]?.liveness)
    }

    /** A retry shows request progress, allows retry after failure, and lets MDK decide when the row is connected. */
    @Test
    fun reconnectShowsProgressAndFailureUntilAnotherRequest() {
        var response = CompletableDeferred<Boolean>()
        val operations = RelayOperationState()
        val showDetails = mutableStateOf(true)
        var requests = 0
        val connection = mutableStateOf(RelayLivenessFfi(A, RelayStatusFfi.OFFLINE, null, null))
        renderReconnectVisit(operations, showDetails, connection) {
            requests++
            response.await()
        }

        val retry = composeRule.onNodeWithText(app.getString(R.string.relay_connection_retry))
        val attempt = composeRule.onNodeWithText(app.getString(R.string.relay_connection_attempt))
        attempt.assertDoesNotExist()
        retry.performClick()
        composeRule
            .onNodeWithText(app.getString(R.string.relay_connection_retrying))
            .assertIsNotEnabled()

        composeRule.runOnIdle { showDetails.value = false }
        retry.assertDoesNotExist()
        composeRule
            .onNodeWithTag("relays.row.$A")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule
            .onNodeWithText(app.getString(R.string.relay_connection_retrying))
            .assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(1, requests)
            assertEquals(RelayReconnectState.Idle, operations.reconnectState(1, A))
            response.complete(false)
        }
        composeRule.onNodeWithText(app.getString(R.string.relay_connection_retry_failed)).assertIsDisplayed()
        composeRule.runOnIdle { response = CompletableDeferred() }
        retry.assertIsEnabled().performClick()

        composeRule.runOnIdle { response.complete(true) }
        composeRule.onNodeWithText(app.getString(R.string.relay_connection_offline)).assertIsDisplayed()
        retry.assertIsEnabled()
        composeRule.runOnIdle {
            connection.value =
                connection.value.copy(
                    status = RelayStatusFfi.CONNECTED,
                    lastAttemptUnix = 1_700_000_000uL,
                )
        }
        composeRule.onNodeWithText(app.getString(R.string.relay_connection_connected)).assertIsDisplayed()
        attempt.assertIsDisplayed()
        retry.assertDoesNotExist()
    }

    /** Allows real list-to-detail navigation while retaining the shared request owner. */
    private fun renderReconnectVisit(
        operations: RelayOperationState,
        showDetails: MutableState<Boolean>,
        connection: MutableState<RelayLivenessFfi>,
        request: suspend () -> Boolean,
    ) {
        val lists = relayLists(nip65 = listOf(A), inbox = emptyList())
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                val scope = rememberCoroutineScope()
                if (showDetails.value) {
                    RelayDetailsScreen(
                        relay = AccountRelay(A, setOf(AccountRelayRole.Profile)),
                        lists = null,
                        busy = operations.busy,
                        onBack = {},
                        onSetRole = { _, _ -> },
                        onRemove = {},
                        connection = connection.value,
                        reconnectState = operations.reconnectState(0, A),
                        onReconnect = {
                            operations.reconnect({ block -> scope.launch { block() } }, 0, A, request)
                        },
                    )
                } else {
                    RelaysContent(
                        state =
                            RelaysUiState(
                                lists = lists,
                                busy = operations.busy,
                                reconnecting = operations.reconnecting,
                            ),
                        onBack = {},
                        onOpenRelay = { showDetails.value = true },
                        onAdd = {},
                        onRefresh = {},
                        onPublishMissing = {},
                        onRestore = {},
                    )
                }
            }
        }
    }

    /** Native fixture classification for an admitted address and its canonical spelling. */
    private fun allowedEndpoint(
        endpoint: String,
        normalized: String,
    ): RelayEndpointClassificationFfi {
        val policy = RelayEndpointPolicyFfi.ALLOWED
        return RelayEndpointClassificationFfi(endpoint, normalized, policy)
    }

    /** Check the status in the accessible row that owns the configured URL. */
    private fun assertConnection(
        url: String,
        statusRes: Int,
    ) {
        composeRule
            .onNodeWithTag("relays.row.$url")
            .performScrollTo()
            .assert(hasText(app.getString(statusRes), substring = true))
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
        val note = app.getString(R.string.relay_needs_attention)
        composeRule.onNodeWithText("$LEGACY · $note", substring = true).assertExists()
    }

    /** A valid onion relay gets the Tor explanation, not the insecure-connection callout or note. */
    @Test
    fun importedOnionRelayExplainsTorInsteadOfInsecureTransport() {
        render(relayLists(nip65 = listOf(POST, ONION), inbox = listOf(ONION)))
        composeRule.onNodeWithTag("relays.imported.onion").assertExists()
        composeRule.onNodeWithText(app.getString(R.string.relay_onion_issue_detail)).assertExists()
        composeRule.onNodeWithTag("relays.imported.issue").assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.relay_imported_issue_detail)).assertDoesNotExist()
        composeRule
            .onNodeWithText("$ONION · " + app.getString(R.string.relay_onion_needs_tor), substring = true)
            .performScrollTo()
            .assertExists()
        composeRule
            .onNodeWithText("$ONION · " + app.getString(R.string.relay_needs_attention), substring = true)
            .assertDoesNotExist()
    }

    /** Onion, clearnet ws:// and unusable imports each keep their own callout and summary note. */
    @Test
    fun mixedImportsShowDistinctOnionInsecureAndInvalidFeedback() {
        render(relayLists(nip65 = listOf(POST, LEGACY, ONION, DECEPTIVE, UNUSABLE), inbox = emptyList()))
        composeRule.onNodeWithTag("relays.imported.issue").assertExists()
        composeRule.onNodeWithTag("relays.imported.onion").assertExists()
        composeRule.onNodeWithTag("relays.imported.unusable").assertExists()
        composeRule.onNodeWithText(app.getString(R.string.relay_unusable_issue_title)).assertExists()
        composeRule
            .onNodeWithText("$DECEPTIVE · " + app.getString(R.string.relay_needs_attention), substring = true)
            .performScrollTo()
            .assertExists()
        composeRule
            .onNodeWithText("$UNUSABLE · " + app.getString(R.string.relay_unusable_address), substring = true)
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText(POST, substring = true).performScrollTo().assertExists()
    }

    /** Imported onion entries and their roles stay listed rather than being filtered from the projection. */
    @Test
    fun importedOnionEntriesKeepTheirRoles() {
        val relays = accountRelays(relayLists(nip65 = listOf(ONION), inbox = listOf(ONION, POST)))
        assertEquals(listOf(ONION, POST), relays.map { it.url })
        assertEquals(setOf(AccountRelayRole.Profile, AccountRelayRole.Inbox), relays.first().roles)
        assertTrue(relays.first().needsAttention)
    }

    /** An onion address shows the Tor explanation once, announces a short error and keeps Add disabled. */
    @Test
    fun addSheetExplainsOnionAddressesNeedTor() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AddRelaySheet(
                    existing = emptyList(),
                    busy = false,
                    rejectedUrl = null,
                    onDismiss = {},
                    onAdd = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("relay.add.url").performTextInput(ONION)
        composeRule.onNodeWithText(app.getString(R.string.relay_onion_add_error)).assertExists()
        // The long explanation is visible once; the announced error is only the short note, not a repeat.
        composeRule
            .onNodeWithTag("relay.add.url")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Error, app.getString(R.string.relay_onion_needs_tor)),
            )
        composeRule.onNodeWithTag("relay.add.submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("relay.add.url").performTextClearance()
        composeRule.onNodeWithTag("relay.add.url").performTextInput("wss://$ONION_HOST")
        composeRule.onNodeWithText(app.getString(R.string.relay_onion_add_error)).assertExists()
        composeRule.onNodeWithTag("relay.add.submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("relay.add.url").performTextClearance()
        composeRule.onNodeWithTag("relay.add.url").performTextInput(LEGACY)
        composeRule.onNodeWithText(app.getString(R.string.relay_onion_add_error)).assertDoesNotExist()
        composeRule.onNodeWithTag("relay.add.submit").assertIsNotEnabled()
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

    /** Submitting the real Add Relay sheet enters its labelled, disabled progress state without throwing. */
    @Test
    fun addRelayShowsLabelledProgressWhilePublishing() {
        val busy = mutableStateOf(false)
        var submittedUrl: String? = null
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AddRelaySheet(
                    existing = emptyList(),
                    busy = busy.value,
                    rejectedUrl = null,
                    onDismiss = {},
                    onAdd = { url, _ ->
                        submittedUrl = url
                        busy.value = true
                    },
                )
            }
        }

        composeRule.onNodeWithTag("relay.add.url").performTextInput(A)
        composeRule.onNodeWithTag("relay.add.submit").assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(A, submittedUrl) }
        composeRule.onNodeWithText(app.getString(R.string.relay_list_publishing)).assertIsDisplayed()
        composeRule
            .onNodeWithTag("relay.add.submit")
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    app.getString(R.string.button_in_progress),
                ),
            )
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
    ) = renderState { RelaysUiState(lists = lists, publication = publication, busy = busy) }

    /** Renders a projection that can be replaced while the screen stays open. */
    private fun renderState(state: () -> RelaysUiState) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                RelaysContent(
                    state = state(),
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
        const val ONION_HOST = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
        const val ONION = "ws://$ONION_HOST"
        const val DECEPTIVE = "ws://$ONION_HOST.example"
        const val UNUSABLE = "wss://relay.example.com:7777"
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
