package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.FolderCommitRecordingPreferences
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.replaceActiveAccountForTest
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real per-account folder persistence and UI ownership; no protocol cache or synthetic save callback replaces it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1400dp-mdpi")
class ChatFolderOwnershipTest {
    @get:Rule val composeRule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** Isolate only the host-test folder preference store. */
    @Before fun clearFolders() {
        app
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    /** A renamed default renders the stored name in the row and the actual editor prefill. */
    @Test fun renamedDefaultIsVisibleInListAndEditor() {
        val state = appState()
        val store = state.chatFolderPreferences
        store.foldersFor(A)
        store.renameFolder(A, UNREAD, "Catch up")
        showList(state)
        composeRule.onNodeWithText("Catch up").performClick()
        composeRule.onNodeWithTag("folder.name").assertExists()
        composeRule.onNodeWithText("Catch up").assertExists()
        composeRule.onNodeWithTag("folder.save").performClick()
        assertEquals("Catch up", store.foldersFor(A).first { it.id == UNREAD }.name)
    }

    /** An account switch discards a delete confirmation, including its captured same-frame callback. */
    @Test fun staleDeleteCannotRemoveEitherAccountsDefault() {
        val state = appState()
        state.chatFolderPreferences.foldersFor(A)
        state.chatFolderPreferences.foldersFor(B)
        showList(state)
        composeRule
            .onNodeWithContentDescription(
                app.getString(
                    R.string.actions_for,
                    app.getString(R.string.chat_list_filter_unread),
                ),
            ).performClick()
        composeRule.onNodeWithText(app.getString(R.string.delete)).performClick()
        val confirm =
            composeRule
                .onNodeWithTag("folder.delete_confirm")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            state.replaceActiveAccountForTest(B)
            confirm()
        }
        composeRule.onNodeWithTag("folder.delete_dialog").assertDoesNotExist()
        assertTrue(state.chatFolderPreferences.foldersFor(A).any { it.id == UNREAD })
        assertTrue(state.chatFolderPreferences.foldersFor(B).any { it.id == UNREAD })
    }

    /** Editor composition and unsaved fields are removed when the folder-list account changes. */
    @Test fun accountSwitchClosesOldDraftBeforeCreatingNewFolder() {
        val state = appState()
        showList(state)
        composeRule.onNodeWithContentDescription(app.getString(R.string.folder_new_title)).performClick()
        composeRule.onNodeWithTag("folder.name").performTextReplacement("A draft")
        composeRule.runOnIdle { state.replaceActiveAccountForTest(B) }
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(app.getString(R.string.folder_new_title)).performClick()
        composeRule.onNodeWithTag("folder.save").assertIsNotEnabled()
        composeRule.onNodeWithText("A draft").assertDoesNotExist()
        assertFalse(state.chatFolderPreferences.foldersFor(B).any { it.name == "A draft" })
    }

    /** Save checks the current account inside the callback, before a recomposition can hide the old editor. */
    @Test fun sameFrameSaveAfterAccountSwitchIsIgnored() {
        val state = appState()
        showEditor(state)
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Old account draft")
        val save = saveAction()
        composeRule.runOnIdle {
            state.replaceActiveAccountForTest(B)
            save()
        }
        assertFalse(state.chatFolderPreferences.foldersFor(A).any { it.systemKind == null })
        assertFalse(state.chatFolderPreferences.foldersFor(B).any { it.systemKind == null })
    }

    /** Duplicate activation before the caller removes this editor creates only one persisted folder. */
    @Test fun duplicateSaveCreatesOneFolder() {
        val state = appState()
        var closed = 0
        showEditor(state, onClose = { closed++ })
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Work")
        val save = saveAction()
        composeRule.runOnIdle {
            save()
            save()
        }
        assertEquals(1, closed)
        assertEquals(1, state.chatFolderPreferences.foldersFor(A).count { it.name == "Work" })
    }

    /** A removed folder cannot fall through ignored store failures and report a successful save. */
    @Test fun sameFrameDeletionBeforeSaveKeepsFailureAndDraft() {
        val state = appState()
        val folder = state.chatFolderPreferences.createFolder(A, "Work")!!
        var closed = false
        showEditor(state, folder.id) { closed = true }
        composeRule.onNodeWithTag("folder.description").performTextReplacement("Keep this draft")
        val save = saveAction()
        composeRule.runOnIdle {
            state.chatFolderPreferences.deleteFolder(A, folder.id)
            save()
        }
        composeRule.onNodeWithText(app.getString(R.string.folder_unavailable)).assertExists()
        composeRule.onNodeWithText("Keep this draft").assertExists()
        assertFalse(closed)
        assertFalse(state.chatFolderPreferences.foldersFor(A).any { it.id == folder.id })
    }

    /** A failed real preference transaction keeps the draft and re-enables Save for an explicit retry. */
    @Test fun atomicSaveFailureRetainsDraftForRetry() {
        val state = appState()
        val preferences =
            FolderCommitRecordingPreferences(
                app.getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE),
            )
        val store = ChatFolderPreferences(app, preferences)
        store.foldersFor(A)
        state.javaClass
            .getDeclaredField("chatFolderPreferences")
            .apply { isAccessible = true }
            .set(state, store)
        var closed = 0
        showEditor(state, onClose = { closed++ })
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Retry this draft")
        composeRule.runOnIdle { preferences.failNext = true }
        composeRule.onNodeWithTag("folder.save").performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_save_failed)).assertExists()
        composeRule.onNodeWithText("Retry this draft").assertExists()
        assertEquals(0, closed)
        assertFalse(store.foldersFor(A).any { it.name == "Retry this draft" })
        composeRule.onNodeWithTag("folder.save").performClick()
        assertEquals(1, closed)
        assertEquals(1, store.foldersFor(A).count { it.name == "Retry this draft" })
    }

    /** Move Up persists existing IDs/order without losing the folder's membership, rule or description. */
    @Test fun menuReorderPersistsExactFolderDefinitionAcrossReload() {
        val state = appState()
        val store = state.chatFolderPreferences
        val folder = store.createFolder(A, "Work", "Team chats")!!
        store.setChatInFolder(A, folder.id, "g1", true)
        val rule = ChatFolderRule(keyword = "team", includeMuted = true)
        store.setFolderRule(A, folder.id, rule)
        val before = store.foldersFor(A).map { it.id }
        showList(state)
        composeRule.onNodeWithContentDescription(app.getString(R.string.actions_for, "Work")).performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_move_up)).performClick()
        val reloaded = ChatFolderPreferences(app)
        val after = reloaded.foldersFor(A)
        val expected = before.toMutableList().apply { add(size - 2, removeAt(lastIndex)) }
        assertEquals(expected, after.map { it.id })
        assertEquals("Team chats", after.first { it.id == folder.id }.description)
        assertEquals(setOf("g1"), reloaded.membershipFor(A, folder.id))
        assertEquals(rule, reloaded.folderRule(A, folder.id))
    }

    /** Restore adds only missing defaults, retaining edited default and custom folder definitions. */
    @Test fun restorePreservesExistingDefaultAndCustomChanges() {
        val state = appState()
        val store = state.chatFolderPreferences
        val defaults = store.foldersFor(A)
        store.renameFolder(A, UNREAD, "Catch up")
        val rule = ChatFolderRule(unreadOnly = true, includeMuted = true)
        store.setFolderRule(A, UNREAD, rule)
        store.deleteFolder(A, defaults.first { it.systemKind == SystemFolderKind.ARCHIVED }.id)
        val custom = store.createFolder(A, "Work", "Keep me")!!
        showList(state)
        composeRule.onNodeWithText(app.getString(R.string.folder_restore_defaults)).performClick()
        assertEquals(SystemFolderKind.entries.toSet(), store.foldersFor(A).mapNotNull { it.systemKind }.toSet())
        assertEquals("Catch up", store.foldersFor(A).first { it.id == UNREAD }.name)
        assertEquals(rule, store.folderRule(A, UNREAD))
        assertEquals(custom, store.foldersFor(A).first { it.id == custom.id })
    }

    /** A real picker selection survives saved-state restoration, and only explicit Save persists it. */
    @Test fun unsavedMembershipSurvivesRotationAndSavesOnce() {
        val state = appState()
        val controller = ChatsController(state)
        state.attachChatsController(controller)
        val setter =
            ChatsController::class.java.getDeclaredMethod("setItems", List::class.java).apply {
                isAccessible = true
            }
        setter.invoke(controller, listOf(chatItem("g1")))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { WhiteNoiseTheme { ChatFolderEditScreen(state, A, null, {}) } }
        composeRule.onNodeWithTag("folder.name").performTextReplacement("Rotated")
        composeRule.onNodeWithText(app.getString(R.string.folder_included_chats)).performClick()
        composeRule.onNode(isToggleable() and hasAnyAncestor(hasTestTag("folder.choice.g1"))).performClick()
        composeRule.onNodeWithText(app.getString(R.string.done)).performClick()
        assertFalse(state.chatFolderPreferences.foldersFor(A).any { it.name == "Rotated" })
        restoration.emulateSavedInstanceStateRestore()
        composeRule
            .onNode(hasText(app.getString(R.string.folder_included_chats)) and hasText("1") and hasClickAction())
            .assertExists()
        composeRule.onNodeWithTag("folder.save").performClick()
        val folder = state.chatFolderPreferences.foldersFor(A).single { it.name == "Rotated" }
        assertEquals(setOf("g1"), state.chatFolderPreferences.membershipFor(A, folder.id))
    }

    /** Render the real account-scoped list owner. */
    private fun showList(state: WhiteNoiseAppState) {
        composeRule.setContent { WhiteNoiseTheme { ChatFoldersScreen(state, {}) } }
    }

    /** Render the real editor owner without removing it on a synthetic completion. */
    private fun showEditor(
        state: WhiteNoiseAppState,
        folder: String? = null,
        onClose: () -> Unit = {},
    ) {
        composeRule.setContent { WhiteNoiseTheme { ChatFolderEditScreen(state, A, folder, onClose) } }
    }

    /** Captured save cannot recreate private preferences during signout teardown. */
    @Test fun sameFrameSaveDuringSignOutDoesNotWrite() = teardownAction("Save", "SignOut")

    /** Captured save cannot recreate private preferences during wipe teardown. */
    @Test fun sameFrameSaveDuringWipeDoesNotWrite() = teardownAction("Save", "Wipe")

    /** Captured save cannot recreate private preferences during runtimereplacement teardown. */
    @Test fun sameFrameSaveDuringRuntimeReplacementDoesNotWrite() = teardownAction("Save", "RuntimeReplacement")

    /** Captured move cannot recreate private preferences during signout teardown. */
    @Test fun sameFrameMoveDuringSignOutDoesNotWrite() = teardownAction("Move", "SignOut")

    /** Captured move cannot recreate private preferences during wipe teardown. */
    @Test fun sameFrameMoveDuringWipeDoesNotWrite() = teardownAction("Move", "Wipe")

    /** Captured move cannot recreate private preferences during runtimereplacement teardown. */
    @Test fun sameFrameMoveDuringRuntimeReplacementDoesNotWrite() = teardownAction("Move", "RuntimeReplacement")

    /** Captured restore cannot recreate private preferences during signout teardown. */
    @Test fun sameFrameRestoreDuringSignOutDoesNotWrite() = teardownAction("Restore", "SignOut")

    /** Captured restore cannot recreate private preferences during wipe teardown. */
    @Test fun sameFrameRestoreDuringWipeDoesNotWrite() = teardownAction("Restore", "Wipe")

    /** Captured restore cannot recreate private preferences during runtimereplacement teardown. */
    @Test fun sameFrameRestoreDuringRuntimeReplacementDoesNotWrite() = teardownAction("Restore", "RuntimeReplacement")

    /** Captured delete cannot recreate private preferences during signout teardown. */
    @Test fun sameFrameDeleteDuringSignOutDoesNotWrite() = teardownAction("Delete", "SignOut")

    /** Captured delete cannot recreate private preferences during wipe teardown. */
    @Test fun sameFrameDeleteDuringWipeDoesNotWrite() = teardownAction("Delete", "Wipe")

    /** Captured delete cannot recreate private preferences during runtimereplacement teardown. */
    @Test fun sameFrameDeleteDuringRuntimeReplacementDoesNotWrite() = teardownAction("Delete", "RuntimeReplacement")

    /** Exercise real semantic callbacks after live teardown flags/generation and actual store cleanup. */
    @Suppress("LongMethod")
    private fun teardownAction(
        action: String,
        teardown: String,
    ) {
        val state = appState()
        val store = state.chatFolderPreferences
        val folder = store.foldersFor(A).first()
        if (action == "Restore") store.deleteFolder(A, folder.id)
        val visible = mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible.value) {
                    if (action == "Save") {
                        ChatFolderEditScreen(state, A, null, {})
                    } else {
                        ChatFoldersScreen(state, {})
                    }
                }
            }
        }
        val callback =
            when (action) {
                "Save" -> {
                    composeRule.onNodeWithTag("folder.name").performTextReplacement("Teardown draft")
                    saveAction()
                }
                "Restore" ->
                    composeRule
                        .onNodeWithText(app.getString(R.string.folder_restore_defaults))
                        .fetchSemanticsNode()
                        .config[SemanticsActions.OnClick]
                        .action!!
                else -> {
                    composeRule
                        .onNodeWithContentDescription(
                            app.getString(
                                R.string.actions_for,
                                app.getString(R.string.chat_list_filter_unread),
                            ),
                        ).performClick()
                    if (action == "Move") {
                        composeRule
                            .onNodeWithText(app.getString(R.string.folder_move_down))
                            .fetchSemanticsNode()
                            .config[SemanticsActions.OnClick]
                            .action!!
                    } else {
                        composeRule.onNodeWithText(app.getString(R.string.delete)).performClick()
                        composeRule
                            .onNodeWithTag("folder.delete_confirm")
                            .fetchSemanticsNode()
                            .config[SemanticsActions.OnClick]
                            .action!!
                    }
                }
            }
        composeRule.runOnIdle {
            when (teardown) {
                "SignOut" -> state.signOutInProgress = true
                "Wipe" -> state.wipeInProgress = true
                else -> {
                    val field = WhiteNoiseAppState::class.java.getDeclaredField("runtimeGeneration\$delegate")
                    field.isAccessible = true
                    val generation = field.get(state) as MutableIntState
                    generation.intValue++
                }
            }
            store.clearAllForAccount(A)
            val disk = app.getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE).all.toMap()
            callback()
            assertFalse(store.state.value.containsKey(A))
            assertEquals(disk, app.getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE).all)
            visible.value = false
        }
    }

    /** Capture the installed semantic callback to test before recomposition disables/removes it. */
    private fun saveAction(): () -> Boolean =
        composeRule
            .onNodeWithTag("folder.save")
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action!!

    /** Existing native AppState and preference store with two user-owned synthetic test identities. */
    private fun appState() =
        WhiteNoiseAppState(
            context = app,
            draftStore = DraftStore(InMemoryDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = listOf(account(A, "a"), account(B, "b")),
            activeAccountRef = A,
        )

    private fun account(
        ref: String,
        hex: String,
    ) = AccountSummaryFfi(
        label = ref,
        accountIdHex = hex.repeat(64),
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    private fun chatItem(groupIdHex: String): ChatListItem =
        ChatListItem(
            group = group(groupIdHex),
            latest = null,
            otherMemberAccount = null,
            memberCount = 2,
            memberSnapshot = null,
            projection =
                ChatListRowFfi(
                    selfMembership = SelfMembershipFfi.MEMBER,
                    unreadMentionCount = 0uL,
                    unreadMention = false,
                    groupIdHex = groupIdHex,
                    archived = false,
                    pendingConfirmation = false,
                    title = "Group $groupIdHex",
                    groupName = "",
                    avatarUrl = null,
                    avatar = null,
                    lastMessage = null,
                    unreadCount = 0uL,
                    hasUnread = false,
                    firstUnreadMessageIdHex = null,
                    lastReadMessageIdHex = null,
                    lastReadTimelineAt = null,
                    conversationCreatedAt = 0uL,
                    activitySortAt = 0uL,
                    updatedAt = 1uL,
                    leaveRequestPending = false,
                    leaveRequestedAtMs = null,
                    manuallyMarkedUnread = false,
                    conversationKind = ChatConversationKindFfi.UNKNOWN,
                    muted = false,
                    mutedUntilMs = null,
                    pinned = false,
                    pinnedPosition = null,
                    lifecycleState = dev.ipf.marmotkit.GroupLifecycleStateFfi.STABLE,
                    disbanding = false,
                    disbandRequest = null,
                ),
        )

    private fun group(id: String) =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = id,
            protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint-$id",
            name = "",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-$id",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia = encryptedMedia(),
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

    private fun encryptedMedia() =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.primal.net"),
                ),
        )

    private class InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val A = "folder-account-a"
        const val B = "folder-account-b"
        const val UNREAD = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID
    }
}
