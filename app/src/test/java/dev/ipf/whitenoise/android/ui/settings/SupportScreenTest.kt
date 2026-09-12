package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.SupportContact
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Support's real owner only reads configuration and waits for an explicit user action; all external
 * boundaries are local fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SupportScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = mutableStateOf(supportTestAccount(context, "alice"))
    private val presented = mutableListOf<String>()
    private val opened = mutableListOf<ChatListItem>()
    private var relaysOpened = 0

    /** A pending native read never guesses a new account is ready or opens the support profile automatically. */
    @Test fun loadingWaitsForExplicitAction() {
        val load = CompletableDeferred<AccountRelayListsFfi?>()
        show(load = { load.await() })
        composeRule.onNodeWithTag("support.start").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<String>(), presented)
        load.complete(relayLists(emptyList(), listOf("wss://inbox.example.com")))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("support.start").assertIsEnabled().performClick()
        assertEquals(listOf(SupportContact.NPUB), presented)
        assertEquals(emptyList<ChatListItem>(), opened)
    }

    /** Posting/bootstrap relays do not stand in for the active account's receiving relay list. */
    @Test fun missingInboxOffersRelaysAndKeepsStartDisabled() {
        show(load = { relayLists(listOf("wss://posting.example.com"), emptyList()) })
        composeRule.onNodeWithTag("support.start").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("support.relays").performScrollTo().performClick()
        assertEquals(1, relaysOpened)
        assertEquals(emptyList<String>(), presented)
    }

    /** Unavailable projections have Retry, and a successful explicit retry updates only this account's state. */
    @Test fun failedReadCanRetryWithoutStartingAChat() {
        var calls = 0
        show(load = { if (++calls == 1) null else relayLists(emptyList(), listOf("wss://inbox.example.com")) })
        composeRule.onNodeWithTag("support.retry").performScrollTo().performClick()
        composeRule.onNodeWithTag("support.start").performScrollTo().assertIsEnabled()
        assertEquals(2, calls)
        assertEquals(emptyList<String>(), presented)
    }

    /** Existing canonical DMs remain reachable while relay configuration is unavailable or still loading. */
    @Test fun existingCanonicalChatBypassesRelayReadAndOpensOnlyOnTap() {
        val chat = supportChat()
        show(load = { awaitCancellation() }, existing = {
            assertEquals(SupportContact.NPUB, it)
            chat
        })
        composeRule.onNodeWithTag("support.start").performScrollTo().assertIsEnabled()
        assertEquals(emptyList<ChatListItem>(), opened)
        composeRule.onNodeWithTag("support.start").performClick()
        assertSame(chat, opened.single())
        assertEquals(emptyList<String>(), presented)
    }

    /** A DM that appears after composition is chosen again at the tap boundary instead of opening a duplicate flow. */
    @Test fun canonicalChatSelectionIsReadAgainAtTap() {
        var chat: ChatListItem? = null
        show(load = { relayLists(emptyList(), listOf("wss://inbox.example.com")) }, existing = { chat })
        composeRule.onNodeWithTag("support.start").performScrollTo().assertIsEnabled()
        composeRule.runOnIdle { chat = supportChat() }
        composeRule.onNodeWithTag("support.start").performClick()
        assertSame(chat, opened.single())
        assertEquals(emptyList<String>(), presented)
    }

    /** A completed old-account native read cannot unlock the new account's Start action. */
    @Test fun accountSwitchIgnoresOldRelayCompletion() {
        val old = CompletableDeferred<AccountRelayListsFfi?>()
        show(load = { if (it == "alice") old.await() else awaitCancellation() })
        composeRule.runOnIdle { owner.value = supportTestAccount(context, "bob") }
        old.complete(relayLists(emptyList(), listOf("wss://old.example.com")))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("support.start").performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<String>(), presented)
    }

    /** Recovery returns to the Support destination before returning to the Settings hub. */
    @Test fun recoveryParentPreservesSupportDestination() {
        assertEquals(SettingsDetail.Support, settingsDetailParent(SettingsDetail.SupportRelays))
        assertNull(settingsDetailParent(SettingsDetail.Support))
        assertNull(settingsDetailParent(SettingsDetail.Relays))
    }

    /** Missing publication and unsafe imported URLs never qualify a new support-chat profile handoff. */
    @Test fun configurationUsesNativeInboxEvidence() {
        assertEquals(SupportRelayState.Unavailable, supportRelayState(null))
        assertEquals(
            SupportRelayState.Missing,
            supportRelayState(
                relayLists(
                    emptyList(),
                    listOf("wss://inbox.example.com"),
                    missing = listOf(MissingRelayListKindFfi.INBOX),
                ),
            ),
        )
        assertEquals(
            SupportRelayState.Missing,
            supportRelayState(relayLists(emptyList(), listOf("ws://legacy.example.com"))),
        )
        assertEquals(
            SupportRelayState.Configured,
            supportRelayState(relayLists(emptyList(), listOf("wss://inbox.example.com"))),
        )
    }

    /** Teardown and a missing account both disable the visible action even when cached readiness says configured. */
    @Test fun accountAndTeardownGatesDisableStart() {
        val hasAccount = mutableStateOf(false)
        val busy = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                SupportContent(
                    hasAccount.value,
                    false,
                    SupportRelayState.Configured,
                    busy.value,
                    {},
                    { presented.add("unexpected") },
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithTag("support.start").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle {
            hasAccount.value = true
            busy.value = true
        }
        composeRule.onNodeWithTag("support.start").assertIsNotEnabled()
        assertEquals(emptyList<String>(), presented)
    }

    private fun show(
        load: suspend (String) -> AccountRelayListsFfi?,
        existing: (String) -> ChatListItem? = { null },
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                SupportScreen(
                    owner.value,
                    {},
                    { opened.add(it) },
                    { relaysOpened++ },
                    load,
                    existingChat = existing,
                    presentProfile = { presented.add(it) },
                )
            }
        }
    }
}

/** Synthetic metadata only; no identity creation, secret material or native runtime is required. */
internal fun supportTestAccount(
    context: Context,
    label: String,
): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore.forContext(context),
        accountIdHexResolver = { null },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = label,
                    accountIdHex = if (label == "alice") "a".repeat(64) else "b".repeat(64),
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = label,
    )

/** A local canonical-DM fixture for navigation identity assertions; no message content or native publication. */
private fun supportChat(): ChatListItem =
    ChatListItem(
        group =
            AppGroupRecordFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                groupIdHex = "support-group",
                protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
                profilePresent = false,
                endpoint = "support-endpoint",
                name = "",
                description = "",
                admins = emptyList(),
                relays = emptyList(),
                nostrGroupIdHex = "support-nostr",
                avatarUrl = null,
                avatarDim = null,
                avatarThumbhash = null,
                imageHashHex = null,
                encryptedMedia =
                    AppGroupEncryptedMediaComponentFfi(
                        componentId = 0x8008u,
                        component = "marmot.group.encrypted-media.v1",
                        required = true,
                        version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
                        mediaFormat = "encrypted-media-v1",
                        allowedLocatorKinds = listOf("blossom-v1"),
                        defaultBlobEndpoints = listOf(AppBlobEndpointFfi("blossom-v1", "https://example.invalid")),
                    ),
                archived = false,
                pendingConfirmation = false,
                unrecoverable = false,
                welcomerAccountIdHex = null,
                viaWelcomeMessageIdHex = null,
                disappearingMessageSecs = 0uL,
                leaveRequestPending = false,
                leaveRequestedAtMs = null,
                disbanding = false,
                disbanded = false,
                disbandRequest = null,
            ),
        latest = null,
        otherMemberAccount = "c".repeat(64),
        memberCount = 2,
        memberSnapshot = null,
    )
