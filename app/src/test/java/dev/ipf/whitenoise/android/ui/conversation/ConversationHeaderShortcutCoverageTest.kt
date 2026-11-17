package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/** Header shortcuts and the header timer reuse the chat details owners instead of copying their commands. */
class ConversationHeaderShortcutCoverageTest {
    private val conversation = appModuleSource("$UI/conversation/ConversationScreen.kt")
    private val details = appModuleSource("$UI/group/GroupDetailsScreen.kt")

    /** Header and details mount the same mute control, duration host and timer editor. */
    @Test
    fun headerAndDetailsShareMuteAndTimerOwners() {
        for (source in listOf(conversation, details)) {
            assertContains(source, "rememberConversationMuteControl(appState, controller)")
            assertContains(source, "MuteDurationHost(")
            assertContains(source, "DisappearingTimerEditor(")
            assertContains(source, "disappearingTimerEditable(")
        }
        assertContains(details, "onClick = muteControl::toggle")
    }

    /** The header search button and the details search row open search through one handler. */
    @Test
    fun headerAndDetailsShareTheSearchHandler() {
        assertContains(conversation, "onOpenSearch = openConversationSearch")
        assertContains(conversation, "onSearch = openConversationSearch")
    }

    /**
     * The timer shortcut opens the same picker as the timer badge, and Members/Add members open the details
     * pages through the auto-open routes the empty-group prompt already uses.
     */
    @Test
    fun groupShortcutsReuseTheDetailsRoutes() {
        assertContains(conversation, "onTimer = if (headerTimerEditable) ({ showHeaderTimerPicker = true }) else null")
        assertContains(conversation, "onTimerClick = if (headerTimerEditable) ({ showHeaderTimerPicker = true }) else null")
        assertContains(conversation, "autoOpenMembers = openMembersOnDetails")
        assertContains(conversation, "autoOpenAddMember = openAddMemberOnDetails")
        val addMembers = conversation.substringAfter("onAddMembers =").substringBefore("onTimerClick")
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

    private companion object {
        const val UI = "src/main/java/dev/ipf/whitenoise/android/ui"
    }
}
