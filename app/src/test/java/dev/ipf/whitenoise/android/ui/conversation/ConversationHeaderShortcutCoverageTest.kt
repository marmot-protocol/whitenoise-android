package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Header shortcuts reuse the chat details owners instead of copying their commands; timer editing stays in Chat Info.
 */
class ConversationHeaderShortcutCoverageTest {
    private val conversation = appModuleSource("$UI/conversation/ConversationScreen.kt")
    private val topBar = appModuleSource("$UI/conversation/ConversationTopBar.kt")
    private val headerShortcuts = appModuleSource("$UI/conversation/ConversationHeaderShortcuts.kt")
    private val details = appModuleSource("$UI/group/GroupDetailsScreen.kt")
    private val overview = appModuleSource("$UI/group/ChatInfoOverview.kt")

    /** Header and details mount the same mute control and duration host; only Chat Info hosts the timer editor. */
    @Test
    fun headerAndDetailsShareMuteOwnerAndTimerLivesInChatInfo() {
        for (source in listOf(conversation, details)) {
            assertContains(source, "rememberConversationMuteControl(appState, controller)")
            assertContains(source, "MuteDurationHost(")
        }
        assertContains(details, "muteControl = muteControl")
        assertContains(overview, "onClick = muteControl::toggle")
        assertContains(details, "DisappearingTimerEditor(")
        assertContains(details, "disappearingTimerEditable(")
        assertContains(details, "controller.updateMessageRetention(")
        assertNotContains(conversation, "DisappearingTimerEditor(")
        assertNotContains(conversation, "disappearingTimerEditable(")
        assertNotContains(conversation, "updateMessageRetention(")
        assertNotContains(conversation, "onTimer =")
        assertNotContains(conversation, "onTimerClick =")
        assertNotContains(topBar, "onTimerClick")
        assertNotContains(headerShortcuts, "onTimer")
        assertNotContains(headerShortcuts, "DisappearingTimer")
    }

    /** The header search button and the details search row open search through one handler. */
    @Test
    fun headerAndDetailsShareTheSearchHandler() {
        assertContains(conversation, "onOpenSearch = openConversationSearch")
        assertContains(conversation, "onSearch = openConversationSearch")
    }

    /**
     * Members and Add members open the details pages through the auto-open routes the empty-group prompt already uses.
     */
    @Test
    fun groupShortcutsReuseTheDetailsRoutes() {
        assertContains(conversation, "autoOpenMembers = openMembersOnDetails")
        assertContains(conversation, "autoOpenAddMember = openAddMemberOnDetails")
        val addMembers = conversation.substringAfter("onAddMembers =").substringBefore("playbackTransport =")
        assertContains(addMembers, "openAddMemberOnDetails = true")
        assertContains(addMembers, "openDetailsFromHeader()")
        assertContains(details, "LaunchedEffect(autoOpenMembers)")
    }

    /** Read aloud in the header and in details read and write the same per-chat override. */
    @Test
    fun headerAndDetailsShareTheReadAloudOverride() {
        for (source in listOf(conversation, details)) {
            assertContains(source, "appState.ttsHasUsableEngine")
            assertContains(source, "appState.setConversationAutoReadOverride(")
        }
        assertContains(conversation, "resolveConversationAutoRead(")
    }

    /**
     * Mute and Read aloud write through the active account, so a notification-routed open must not offer them
     * before the switch to the chat's account lands, and the read-aloud write rechecks at tap time.
     */
    @Test
    fun headerAccountActionsWaitForTheChatsAccount() {
        val ownerCheck = "controller.isCurrentGroupMutationOwner(appState, appState.runtimeGeneration)"
        assertContains(conversation, "val headerSettingsOwner = $ownerCheck")
        assertContains(conversation, "if (headerSettingsOwner && HeaderShortcut.ReadAloud in headerShortcuts")
        assertContains(conversation, "if (headerSettingsOwner && HeaderShortcut.MuteUnmute in headerShortcuts)")
        val toggle = conversation.substringAfter("fun toggleHeaderReadAloud(").substringBefore("\n    }")
        assertContains(toggle, "if (!$ownerCheck) return")
    }

    /** Fails with the missing fragment so a moved owner is easy to find. */
    private fun assertContains(
        source: String,
        expected: String,
    ) = assertTrue("Missing `$expected`", source.contains(expected))

    /** Fails when a removed header timer hook reappears in the conversation screen. */
    private fun assertNotContains(
        source: String,
        forbidden: String,
    ) = assertFalse("Unexpected `$forbidden`", source.contains(forbidden))

    private companion object {
        const val UI = "src/main/java/dev/ipf/whitenoise/android/ui"
    }
}
