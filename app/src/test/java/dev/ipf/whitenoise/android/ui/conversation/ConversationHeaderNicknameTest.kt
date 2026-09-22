package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A DM header shows the viewer's own private nickname for the peer, and only for that account
 * (#2766). Groups keep the title MDK prepared for them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationHeaderNicknameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A nickname outranks the prepared network name in a direct-message header. */
    @Test
    fun directMessageHeaderShowsTheContactNickname() {
        val appState = appState()
        appState.setContactNickname(PEER_ID, "Alex Cousin")
        val controller = directMessageController(appState)
        controller.window.install(MarmotWindowTestFakes.conversationFrame(NETWORK_NAME))
        render(appState, controller)

        composeRule.onNodeWithText("Alex Cousin").assertExists()
        composeRule.onNodeWithText(NETWORK_NAME).assertDoesNotExist()
    }

    /** Editing and then clearing the nickname moves the header back to the prepared name in place. */
    @Test
    fun editingAndClearingTheNicknameUpdatesTheHeaderImmediately() {
        val appState = appState()
        val controller = directMessageController(appState)
        controller.window.install(MarmotWindowTestFakes.conversationFrame(NETWORK_NAME))
        render(appState, controller)

        composeRule.onNodeWithText(NETWORK_NAME).assertExists()
        composeRule.runOnIdle { appState.setContactNickname(PEER_ID, "Alex Cousin") }
        composeRule.onNodeWithText("Alex Cousin").assertExists()
        composeRule.runOnIdle { appState.setContactNickname(PEER_ID, "") }
        composeRule.onNodeWithText(NETWORK_NAME).assertExists()
        composeRule.onNodeWithText("Alex Cousin").assertDoesNotExist()
    }

    /** A group keeps its prepared title even when a member carries a private nickname. */
    @Test
    fun groupHeaderKeepsThePreparedTitle() {
        val appState = appState()
        appState.setContactNickname(PEER_ID, "Alex Cousin")
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group().copy(name = "Book club"),
                initialMemberSnapshot = GroupMemberSnapshot(listOf(member(ACCOUNT_ID, local = true), member(PEER_ID))),
            )
        controller.window.install(MarmotWindowTestFakes.conversationFrame("Book club"))
        render(appState, controller)

        composeRule.onNodeWithText("Book club").assertExists()
        composeRule.onNodeWithText("Alex Cousin").assertDoesNotExist()
    }

    /**
     * Nicknames stay account-scoped: the other signed-in account, reading the same preference
     * store, sees the prepared network name rather than the first account's private label.
     */
    @Test
    fun nicknameDoesNotLeakToAnotherAccount() {
        appState().setContactNickname(PEER_ID, "Alex Cousin")
        val otherAccountState = appState(activeAccountRef = OTHER_ACCOUNT_REF)
        val controller = directMessageController(otherAccountState)
        controller.window.install(MarmotWindowTestFakes.conversationFrame(NETWORK_NAME))
        render(otherAccountState, controller)

        composeRule.onNodeWithText(NETWORK_NAME).assertExists()
        composeRule.onNodeWithText("Alex Cousin").assertDoesNotExist()
    }

    /** A frozen route and a later live-window replacement both keep the nickname on screen. */
    @Test
    fun frozenRouteAndLiveWindowReplacementKeepTheNickname() {
        val appState = appState()
        appState.setContactNickname(PEER_ID, "Alex Cousin")
        val controller = directMessageController(appState)
        val frozen = mutableStateOf(true)
        render(appState, controller, frozen = frozen)

        composeRule.onNodeWithText("Alex Cousin").assertExists()
        composeRule.runOnIdle { controller.window.install(MarmotWindowTestFakes.conversationFrame(NETWORK_NAME)) }
        composeRule.onNodeWithText("Alex Cousin").assertExists()
        composeRule.runOnIdle { frozen.value = false }
        composeRule.onNodeWithText("Alex Cousin").assertExists()
        composeRule.onNodeWithText(NETWORK_NAME).assertDoesNotExist()
    }

    /** An unnamed two-person conversation between this account and [PEER_ID]. */
    private fun directMessageController(appState: WhiteNoiseAppState) =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = GroupMemberSnapshot(listOf(member(ACCOUNT_ID, local = true), member(PEER_ID))),
        )

    /** Renders the real top bar with inert navigation callbacks. */
    private fun render(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        frozen: State<Boolean> = mutableStateOf(false),
    ) {
        val searchFocusRequester = FocusRequester()
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ConversationTopBar(
                        selectionMode = false,
                        selectedCount = 0,
                        onCloseSelection = {},
                        searchOpen = false,
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onClearSearch = {},
                        onCloseSearch = {},
                        onSearchAction = {},
                        searchFocusRequester = searchFocusRequester,
                        appState = appState,
                        controller = controller,
                        groupTitleCopy = GroupTitleCopy.Default,
                        openedAsDmHint = true,
                        freezeRoutePresentation = frozen.value,
                        openDetailsDescription = "Open details",
                        onOpenDetails = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    /** Two signed-in accounts so account scoping can be exercised without a runtime. */
    private fun appState(activeAccountRef: String = ACCOUNT_REF) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    account(ACCOUNT_REF, ACCOUNT_ID),
                    account(OTHER_ACCOUNT_REF, OTHER_ACCOUNT_ID),
                ),
            activeAccountRef = activeAccountRef,
        )

    /** A signed-in local account summary. */
    private fun account(
        label: String,
        accountIdHex: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** One roster entry; [local] marks the viewing account's own membership. */
    private fun member(
        accountIdHex: String,
        local: Boolean = false,
    ) = AppGroupMemberRecordFfi(
        memberIdHex = accountIdHex,
        account = if (local) accountIdHex else null,
        local = local,
    )

    /** An unnamed group record, which is how a direct conversation is stored. */
    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    /** Drafts are irrelevant here; persistence is a no-op. */
    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val OTHER_ACCOUNT_REF = "work"
        const val NETWORK_NAME = "alex@relay.example"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val OTHER_ACCOUNT_ID = "05" + "00".repeat(31)
        val PEER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
