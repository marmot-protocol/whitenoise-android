package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.profile.profileSheetContactPrivateDetailsRowValue
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DirectDetailsContactEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val addLabel = "Add nickname & notes"
    private val notesLabel = "Notes"

    private val accounts =
        listOf(
            AccountSummaryFfi(
                label = ACCOUNT_REF,
                accountIdHex = SELF_HEX,
                localSigning = true,
                externalSigning = false,
                signedOut = false,
                running = true,
            ),
        )

    @Test
    fun targetRequiresDmWithResolvedPeer() {
        assertEquals(
            PEER_HEX,
            directDetailsContactEditorTarget(
                isDm = true,
                readOnlyInvite = false,
                dmPeerAccountIdHex = PEER_HEX,
                dmPeerNpub = "npub1peer",
                activeAccountRef = ACCOUNT_REF,
                accounts = accounts,
            ),
        )
    }

    @Test
    fun targetRejectsNonDmPendingInviteAndUnresolvedPeer() {
        val args =
            directDetailsContactEditorTarget(
                isDm = true,
                readOnlyInvite = false,
                dmPeerAccountIdHex = PEER_HEX,
                dmPeerNpub = "npub1peer",
                activeAccountRef = ACCOUNT_REF,
                accounts = accounts,
            )
        assertNull(
            directDetailsContactEditorTarget(
                isDm = false,
                readOnlyInvite = false,
                dmPeerAccountIdHex = PEER_HEX,
                dmPeerNpub = "npub1peer",
                activeAccountRef = ACCOUNT_REF,
                accounts = accounts,
            ),
        )
        assertNull(
            directDetailsContactEditorTarget(
                isDm = true,
                readOnlyInvite = true,
                dmPeerAccountIdHex = PEER_HEX,
                dmPeerNpub = "npub1peer",
                activeAccountRef = ACCOUNT_REF,
                accounts = accounts,
            ),
        )
        assertNull(
            directDetailsContactEditorTarget(
                isDm = true,
                readOnlyInvite = false,
                dmPeerAccountIdHex = null,
                dmPeerNpub = "npub1peer",
                activeAccountRef = ACCOUNT_REF,
                accounts = accounts,
            ),
        )
        assertNull(
            directDetailsContactEditorTarget(
                isDm = true,
                readOnlyInvite = false,
                dmPeerAccountIdHex = PEER_HEX,
                dmPeerNpub = null,
                activeAccountRef = ACCOUNT_REF,
                accounts = accounts,
            ),
        )
        assertEquals(PEER_HEX, args)
    }

    @Test
    fun targetRejectsActiveAccountAsPeer() {
        assertNull(
            directDetailsContactEditorTarget(
                isDm = true,
                readOnlyInvite = false,
                dmPeerAccountIdHex = SELF_HEX,
                dmPeerNpub = "npub1self",
                activeAccountRef = ACCOUNT_REF,
                accounts = accounts,
            ),
        )
    }

    @Test
    fun saveTargetRejectsAccountSwitchAfterEditorOpens() {
        assertNull(
            directDetailsContactEditorTarget(
                isDm = true,
                readOnlyInvite = false,
                dmPeerAccountIdHex = PEER_HEX,
                dmPeerNpub = "npub1peer",
                activeAccountRef = "account-b",
                accounts = accounts,
                editorAccountRef = ACCOUNT_REF,
            ),
        )
    }

    @Test
    fun dmDetailsHeaderTitleFollowsNicknameSaveAndClear() {
        renderDmGroupDetailsScreen()

        assertVisibleHeaderTitle("Bob Profile")

        openEditor(scrollToRow = true)
        fillNickname("Alice")
        composeRule.onNodeWithText(context.getString(R.string.save), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Alice").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        composeRule.onNodeWithText("Bob Profile").assertDoesNotExist()

        openEditor(scrollToRow = true)
        clearNicknameField()
        composeRule.onNodeWithText(context.getString(R.string.save), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertVisibleHeaderTitle("Bob Profile")
    }

    @Test
    fun rowValueMatchesProfileSheetPrecedence() {
        assertEquals(
            addLabel,
            profileSheetContactPrivateDetailsRowValue(null, null, addLabel, notesLabel),
        )
        assertEquals(
            "Alice",
            profileSheetContactPrivateDetailsRowValue("Alice", "note", addLabel, notesLabel),
        )
        assertEquals(
            notesLabel,
            profileSheetContactPrivateDetailsRowValue(null, "private note", addLabel, notesLabel),
        )
    }

    @Test
    fun absentValuesShowAddNicknameAndNotesAction() {
        val appState = testAppState()
        renderEditor(appState = appState, peerHex = PEER_HEX, profileName = "Bob Profile")

        composeRule
            .onNodeWithText(context.getString(R.string.profile_add_nickname_and_notes))
            .assertIsDisplayed()
    }

    @Test
    fun existingNicknameShowsEditableRowTitleAndValue() {
        val appState = testAppState()
        appState.setContactNickname(PEER_HEX, "Alice")
        renderEditor(appState = appState, peerHex = PEER_HEX, profileName = "Bob Profile")

        composeRule.onNodeWithText(context.getString(R.string.profile_nickname_and_notes)).assertIsDisplayed()
        composeRule.onAllNodesWithText("Alice").fetchSemanticsNodes().let { assertEquals(2, it.size) }
    }

    @Test
    fun noteOnlyShowsNotesValueFallback() {
        val appState = testAppState()
        appState.setContactNotes(PEER_HEX, "remember vacation")
        renderEditor(appState = appState, peerHex = PEER_HEX, profileName = "Bob Profile")

        composeRule.onNodeWithText(context.getString(R.string.profile_add_nickname_and_notes)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.profile_contact_notes_hint)).assertIsDisplayed()
    }

    @Test
    fun savePersistsNicknameAndNotes() {
        val appState = testAppState()
        renderEditor(appState = appState, peerHex = PEER_HEX, profileName = "Bob Profile")

        openEditor()
        fillNickname("Alice")
        fillNotes("private note")
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()

        composeRule.runOnIdle {
            assertEquals("Alice", appState.contactNickname(PEER_HEX))
            assertEquals("private note", appState.contactNotes(PEER_HEX))
        }
    }

    @Test
    fun clearNicknameOnSaveRestoresProfileTitle() {
        val appState = testAppState()
        appState.setContactNickname(PEER_HEX, "Alice")
        var title = ""
        renderEditor(
            appState = appState,
            peerHex = PEER_HEX,
            profileName = "Bob Profile",
            onTitle = { title = it },
        )
        composeRule.runOnIdle { assertEquals("Alice", title) }

        openEditor()
        clearNicknameField()
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()

        composeRule.runOnIdle {
            assertNull(appState.contactNickname(PEER_HEX))
            assertEquals("Bob Profile", title)
        }
    }

    @Test
    fun cancelDismissesWithoutSaving() {
        val appState = testAppState()
        renderEditor(appState = appState, peerHex = PEER_HEX, profileName = "Bob Profile")

        openEditor()
        fillNickname("Alice")
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()

        composeRule.runOnIdle { assertNull(appState.contactNickname(PEER_HEX)) }
        composeRule.onNodeWithText(context.getString(R.string.profile_nickname_and_notes)).assertIsNotDisplayed()
    }

    @Test
    fun backDismissesWithoutSaving() {
        val appState = testAppState()
        renderEditor(appState = appState, peerHex = PEER_HEX, profileName = "Bob Profile")

        openEditor()
        fillNickname("Alice")
        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertNull(appState.contactNickname(PEER_HEX)) }
        composeRule.onNodeWithText(context.getString(R.string.profile_nickname_and_notes)).assertIsNotDisplayed()
    }

    @Test
    fun peerSwitchDoesNotSaveAgainstPriorPeer() {
        val appState = testAppState()
        val peerState = mutableStateOf<String?>(PEER_HEX)
        renderEditor(appState = appState, peerState = peerState, profileName = "Bob Profile")

        openEditor()
        fillNickname("Alice")
        composeRule.runOnIdle { peerState.value = PEER_B_HEX }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertNull(appState.contactNickname(PEER_HEX))
            assertNull(appState.contactNickname(PEER_B_HEX))
        }
    }

    @Test
    fun accountSwitchDiscardsPriorAccountDraft() {
        val appState = testAppState()
        val accountRefState = mutableStateOf<String?>(ACCOUNT_REF)
        renderEditor(
            appState = appState,
            peerHex = PEER_HEX,
            accountRefState = accountRefState,
            profileName = "Bob Profile",
        )

        openEditor()
        fillNickname("Alice")
        composeRule.runOnIdle { accountRefState.value = "account-b" }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertNull(appState.contactNickname(PEER_HEX)) }
        composeRule.onNodeWithText(context.getString(R.string.profile_nickname_and_notes)).assertIsNotDisplayed()
    }

    @Test
    fun unavailablePeerRendersNothing() {
        val appState = testAppState()
        renderEditor(appState = appState, peerHex = null, profileName = "Bob Profile")

        composeRule
            .onNodeWithText(context.getString(R.string.profile_add_nickname_and_notes))
            .assertDoesNotExist()
    }

    private fun renderEditor(
        appState: WhiteNoiseAppState,
        peerHex: String? = null,
        peerState: androidx.compose.runtime.MutableState<String?>? = null,
        accountRefState: androidx.compose.runtime.MutableState<String?>? = null,
        profileName: String,
        onTitle: (String) -> Unit = {},
    ) {
        val initialPeer = peerState?.value ?: peerHex
        composeRule.setContent {
            val peer = peerState?.value ?: peerHex
            val activeAccountRef = accountRefState?.value ?: ACCOUNT_REF
            WhiteNoiseTheme {
                val profileRevision = appState.profileRevisionForCompose
                val title =
                    remember(peer, profileRevision) {
                        peer?.let { appState.chatMemberTitle(it) } ?: profileName
                    }
                Text(title)
                SideEffect { onTitle(title) }
                DirectDetailsContactEditorRow(
                    appState = appState,
                    groupIdHex = GROUP_HEX,
                    peerAccountIdHex = peer,
                    isDm = true,
                    readOnlyInvite = false,
                    dmPeerNpub = if (peer != null) "npub1$peer" else null,
                    activeAccountRef = activeAccountRef,
                    accounts = accounts,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            onTitle(initialPeer?.let { appState.chatMemberTitle(it) } ?: profileName)
        }
    }

    private fun openEditor(scrollToRow: Boolean = false) {
        val labels =
            listOf(
                context.getString(R.string.profile_add_nickname_and_notes),
                context.getString(R.string.profile_nickname_and_notes),
            )
        for (label in labels) {
            try {
                val node = composeRule.onNodeWithText(label)
                if (scrollToRow) node.performScrollTo()
                node.performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText(context.getString(R.string.save), useUnmergedTree = true).assertExists()
                return
            } catch (_: AssertionError) {
            }
        }
        error("contact editor row not found")
    }

    private fun fillNickname(value: String) {
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput(value)
    }

    private fun fillNotes(value: String) {
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[1].performTextInput(value)
    }

    private fun clearNicknameField() {
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextClearance()
    }

    private fun assertVisibleHeaderTitle(title: String) {
        composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
    }

    private fun renderDmGroupDetailsScreen(appState: WhiteNoiseAppState = testAppState()) {
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = dmGroup(),
                initialMemberSnapshot =
                    GroupMemberSnapshot(
                        listOf(
                            dmMember(SELF_HEX, local = true),
                            dmMember(PEER_HEX, local = false),
                        ),
                    ),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsScreen(
                    appState = appState,
                    controller = controller,
                    onBack = {},
                    onLeft = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun dmGroup(): AppGroupRecordFfi =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = GROUP_HEX,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "",
            description = "",
            admins = listOf(SELF_HEX),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-dm",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = dmEncryptedMedia(),
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
        )

    private fun dmMember(
        accountIdHex: String,
        local: Boolean,
    ): AppGroupMemberRecordFfi =
        AppGroupMemberRecordFfi(
            memberIdHex = accountIdHex,
            account = if (local) ACCOUNT_REF else null,
            local = local,
        )

    private fun dmEncryptedMedia(): AppGroupEncryptedMediaComponentFfi =
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
                        baseUrl = "https://blossom.primal.net",
                    ),
                ),
        )

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = ACCOUNT_REF,
            profileReader = { null },
            profileDisplayNameReader = { accountIdHex ->
                if (accountIdHex == PEER_HEX) "Bob Profile" else null
            },
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val SELF_HEX = "self-a"
        const val PEER_HEX = "peer-a"
        const val PEER_B_HEX = "peer-b"
        const val GROUP_HEX = "group-dm"
    }
}
