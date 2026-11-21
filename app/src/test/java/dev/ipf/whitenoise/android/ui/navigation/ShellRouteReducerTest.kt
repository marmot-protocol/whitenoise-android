package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.state.nextNavAccountRef
import dev.ipf.whitenoise.android.state.shouldResetNavOnAccountChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure route reducer of the shadow navigator, event by event and through the paired account cases. */
class ShellRouteReducerTest {
    /** Opening a conversation from the list puts it above the root and Close takes it away again. */
    @Test
    fun openAndCloseAConversation() {
        val opened = reduce(CHATS_ROOT, ShellNavEvent.OpenConversation(conversationA))
        assertEquals(listOf(ShellRoute.Chats, conversationA), opened)
        assertEquals(CHATS_ROOT, reduce(opened, ShellNavEvent.CloseConversation))
    }

    /** A conversation opened over Settings sits above the Settings chain, and closing it returns to that screen. */
    @Test
    fun aConversationOpenedOverSettingsReturnsToIt() {
        val settings = reduce(CHATS_ROOT, ShellNavEvent.OpenSettings(SettingsDetail.Appearance))
        val opened = reduce(settings, ShellNavEvent.OpenConversation(conversationA))
        assertEquals(settings + conversationA, opened)
        assertEquals(settings, reduce(opened, ShellNavEvent.CloseConversation))
        assertEquals(settings, reduce(opened, ShellNavEvent.Back))
    }

    /** A jump, a notification commit and a restore replace the conversation, and the jumps also leave Settings. */
    @Test
    fun jumpsAndNotificationCommitsLeaveSettings() {
        val settingsWithChat =
            reduce(reduce(CHATS_ROOT, ShellNavEvent.OpenSettings(null)), ShellNavEvent.OpenConversation(conversationA))
        assertEquals(
            listOf(ShellRoute.Chats, conversationB),
            reduce(settingsWithChat, ShellNavEvent.JumpToConversation(conversationB)),
        )
        assertEquals(
            listOf(ShellRoute.Chats, conversationB),
            reduce(settingsWithChat, ShellNavEvent.CommitNotificationRoute(conversationB)),
        )
        assertEquals(
            listOf(ShellRoute.Chats, ShellRoute.Settings(null), conversationB),
            reduce(settingsWithChat, ShellNavEvent.RestoreSavedConversation(conversationB)),
        )
    }

    /** A same-chat re-land keeps the conversation and takes the fresh open context with its new request id. */
    @Test
    fun aReLandKeepsTheConversationWithTheFreshOpenContext() {
        val fresh = ConversationOpenContext(notificationOpenRequestId = 9L, notificationRouteTraceRequestId = 9L)
        val stack = listOf(ShellRoute.Chats, ShellRoute.Settings(null), conversationA)
        assertEquals(
            listOf(ShellRoute.Chats, conversationA.copy(open = fresh)),
            reduce(stack, ShellNavEvent.JumpWithin(fresh)),
        )
        assertEquals(CHATS_ROOT, reduce(CHATS_ROOT, ShellNavEvent.JumpWithin(fresh)))
    }

    /** Reconciling the provisional row keeps the route, and without a conversation it changes nothing. */
    @Test
    fun aReconciledRowKeepsTheRoute() {
        val stack = listOf(ShellRoute.Chats, conversationA)
        assertEquals(stack, reduce(stack, ShellNavEvent.ConversationRowReconciled(GROUP_ONE)))
        assertEquals(CHATS_ROOT, reduce(CHATS_ROOT, ShellNavEvent.ConversationRowReconciled(GROUP_ONE)))
    }

    /** A system return to the chat list drops Settings and the conversation together. */
    @Test
    fun returnToChatsClearsEverything() {
        val stack = listOf(ShellRoute.Chats, ShellRoute.Settings(null), conversationA)
        assertEquals(CHATS_ROOT, reduce(stack, ShellNavEvent.ReturnToChats))
    }

    /** A deep Settings open synthesizes every parent, and Back walks them one by one to the chat list. */
    @Test
    fun deepSettingsSynthesizesItsParents() {
        val relays = reduce(CHATS_ROOT, ShellNavEvent.OpenSettings(SettingsDetail.Relays))
        assertEquals(
            listOf(
                ShellRoute.Chats,
                ShellRoute.Settings(null),
                ShellRoute.Settings(SettingsDetail.Account),
                ShellRoute.Settings(SettingsDetail.Relays),
            ),
            relays,
        )
        val account = reduce(relays, ShellNavEvent.Back)
        assertEquals(ShellRoute.Settings(SettingsDetail.Account), account.last())
        val home = reduce(account, ShellNavEvent.Back)
        assertEquals(listOf(ShellRoute.Chats, ShellRoute.Settings(null)), home)
        val root = reduce(home, ShellNavEvent.Back)
        assertEquals(CHATS_ROOT, root)
        assertTrue(backLeavesApp(root))
        assertEquals(CHATS_ROOT, reduce(root, ShellNavEvent.Back))
    }

    /** A detail change re-synthesizes the chain, so a jump that skips a parent is expressed by the change itself. */
    @Test
    fun aDetailChangeReplacesTheChain() {
        val profile = reduce(CHATS_ROOT, ShellNavEvent.OpenSettings(SettingsDetail.Profile))
        assertEquals(
            listOf(ShellRoute.Chats, ShellRoute.Settings(null)),
            reduce(profile, ShellNavEvent.ChangeSettingsDetail(null)),
        )
        assertEquals(CHATS_ROOT, reduce(CHATS_ROOT, ShellNavEvent.ChangeSettingsDetail(SettingsDetail.Appearance)))
        assertEquals(CHATS_ROOT, reduce(profile, ShellNavEvent.ExitSettings))
    }

    /** Diagnostics opened from Developer sits above Developer's chain and Back returns to Developer. */
    @Test
    fun diagnosticsFromDeveloperReturnsToDeveloper() {
        val developer = reduce(CHATS_ROOT, ShellNavEvent.OpenSettings(SettingsDetail.Developer))
        val diagnostics = reduce(developer, ShellNavEvent.OpenDiagnostics)
        assertEquals(
            listOf(
                ShellRoute.Chats,
                ShellRoute.Settings(null),
                ShellRoute.Settings(SettingsDetail.HelpSupport),
                ShellRoute.Settings(SettingsDetail.Developer),
                ShellRoute.Diagnostics,
            ),
            diagnostics,
        )
        assertEquals(developer, reduce(diagnostics, ShellNavEvent.Back))
        assertEquals(
            listOf(ShellRoute.Chats, ShellRoute.Settings(null), ShellRoute.Diagnostics),
            reduce(CHATS_ROOT, ShellNavEvent.OpenDiagnostics),
        )
    }

    /** A restored section replaces the Settings chain and keeps the process-owned conversation. */
    @Test
    fun aRestoredSectionKeepsTheConversation() {
        val stack = listOf(ShellRoute.Chats, conversationA)
        assertEquals(
            CHATS_ROOT + settingsChain(SettingsDetail.Developer) + ShellRoute.Diagnostics + conversationA,
            reduce(stack, ShellNavEvent.SectionRestored(MainSection.Diagnostics, SettingsDetail.Developer)),
        )
        assertEquals(stack, reduce(stack, ShellNavEvent.SectionRestored(MainSection.Chats, SettingsDetail.Account)))
    }

    /** A removed task drops only the conversation, and a released shell drops everything. */
    @Test
    fun taskRemovalAndReleaseClearTheRoute() {
        val stack = listOf(ShellRoute.Chats, ShellRoute.Settings(null), conversationA)
        assertEquals(listOf(ShellRoute.Chats, ShellRoute.Settings(null)), reduce(stack, ShellNavEvent.TaskRemoved))
        assertEquals(CHATS_ROOT, reduce(stack, ShellNavEvent.ShellReleased))
    }

    /** null → A sends no switch, so a restored Settings screen and conversation stay. */
    @Test
    fun aFirstAccountKeepsTheRestoredRoute() {
        val restored =
            reduce(
                reduce(CHATS_ROOT, ShellNavEvent.SectionRestored(MainSection.Settings, SettingsDetail.Appearance)),
                ShellNavEvent.RestoreSavedConversation(conversationA),
            )
        assertFalse(shouldResetNavOnAccountChange(null, ACCOUNT_A))
        assertEquals(CHATS_ROOT + settingsChain(SettingsDetail.Appearance) + conversationA, restored)
    }

    /** A → null loses the owner and clears the route at that moment. */
    @Test
    fun losingTheAccountClearsTheRoute() {
        val stack = listOf(ShellRoute.Chats, ShellRoute.Settings(null), conversationA)
        assertEquals(CHATS_ROOT, reduce(stack, ShellNavEvent.OwnerLost(RUNTIME)))
    }

    /** A → null → A stays cleared: the returning account restores nothing and is not a switch. */
    @Test
    fun theSameAccountReturningRestoresNothing() {
        val lost = reduce(listOf(ShellRoute.Chats, conversationA), ShellNavEvent.OwnerLost(RUNTIME))
        val returned = reduce(lost, ShellNavEvent.OwnerReplaced(null, RUNTIME, ACCOUNT_A, RUNTIME))
        val tracker = nextNavAccountRef(ACCOUNT_A, null)
        assertFalse(shouldResetNavOnAccountChange(tracker, ACCOUNT_A))
        assertEquals(CHATS_ROOT, returned)
    }

    /** A → null → B is cleared by the owner loss, and the later switch from A to B changes nothing further. */
    @Test
    fun anotherAccountAfterASignedOutMomentFindsNothingToClear() {
        val lost = reduce(listOf(ShellRoute.Chats, conversationA), ShellNavEvent.OwnerLost(RUNTIME))
        val rebound = reduce(lost, ShellNavEvent.OwnerReplaced(null, RUNTIME, ACCOUNT_B, RUNTIME))
        val tracker = nextNavAccountRef(ACCOUNT_A, null)
        assertTrue(shouldResetNavOnAccountChange(tracker, ACCOUNT_B))
        assertEquals(CHATS_ROOT, reduce(rebound, ShellNavEvent.AccountSwitched(ACCOUNT_A, ACCOUNT_B, 0L)))
    }

    /**
     * A matching early-open landing keeps the root and the conversation with its open context unchanged, and drops
     * any entry that is not the conversation root.
     */
    @Test
    fun aMatchingEarlyOpenLandingKeepsTheConversation() {
        val pinned = pinnedConversation(trace = EARLY_OPEN)
        val withEntryAbove = listOf(ShellRoute.Chats, ShellRoute.Settings(null), pinned, ShellRoute.Diagnostics)
        val landed = reduce(withEntryAbove, ShellNavEvent.AccountSwitched(ACCOUNT_A, ACCOUNT_B, EARLY_OPEN))
        assertEquals(listOf(ShellRoute.Chats, pinned), landed)
    }

    /** A stale request id for the same target account takes the full privacy reset. */
    @Test
    fun aStaleEarlyOpenRequestTakesThePrivacyReset() {
        val stack = listOf(ShellRoute.Chats, pinnedConversation(trace = EARLY_OPEN))
        assertEquals(CHATS_ROOT, reduce(stack, ShellNavEvent.AccountSwitched(ACCOUNT_A, ACCOUNT_B, EARLY_OPEN + 1)))
        assertEquals(CHATS_ROOT, reduce(stack, ShellNavEvent.AccountSwitched(ACCOUNT_A, ACCOUNT_B, 0L)))
        assertEquals(CHATS_ROOT, reduce(stack, ShellNavEvent.AccountSwitched(ACCOUNT_A, ACCOUNT_C, EARLY_OPEN)))
    }

    /** An ordinary switch clears an unpinned conversation and Settings. */
    @Test
    fun anOrdinarySwitchClearsTheRoute() {
        val stack = listOf(ShellRoute.Chats, ShellRoute.Settings(null), conversationA)
        assertEquals(CHATS_ROOT, reduce(stack, ShellNavEvent.AccountSwitched(ACCOUNT_A, ACCOUNT_B, EARLY_OPEN)))
    }

    /**
     * An owner rebind keeps only a notification route pinned to the new account within the same runtime, as the
     * process state's replacement does.
     */
    @Test
    fun anOwnerRebindKeepsOnlyAPinnedNotificationRoute() {
        val pinned = listOf(ShellRoute.Chats, ShellRoute.Settings(null), pinnedConversation(trace = 3L))
        val kept = listOf(ShellRoute.Chats, pinnedConversation(trace = 3L))
        assertEquals(kept, reduce(pinned, ShellNavEvent.OwnerReplaced(ACCOUNT_A, RUNTIME, ACCOUNT_B, RUNTIME)))
        val nextRuntime = ShellNavEvent.OwnerReplaced(ACCOUNT_A, RUNTIME, ACCOUNT_B, RUNTIME + 1)
        assertEquals(CHATS_ROOT, reduce(pinned, nextRuntime))
        assertEquals(CHATS_ROOT, reduce(pinned, ShellNavEvent.OwnerReplaced(ACCOUNT_A, RUNTIME, ACCOUNT_C, RUNTIME)))
        assertEquals(CHATS_ROOT, reduce(pinned, ShellNavEvent.OwnerReplaced(null, null, ACCOUNT_B, RUNTIME)))
        val untraced = listOf(ShellRoute.Chats, pinnedConversation(trace = null))
        assertEquals(CHATS_ROOT, reduce(untraced, ShellNavEvent.OwnerReplaced(ACCOUNT_A, RUNTIME, ACCOUNT_B, RUNTIME)))
    }

    /** The legacy projection puts the selected conversation above the section and ignores a detail under Chats. */
    @Test
    fun theLegacyProjectionFollowsTheCanonicalOrder() {
        val open = ConversationOpenContext(notificationOpenRequestId = 4L)
        assertEquals(
            CHATS_ROOT + settingsChain(SettingsDetail.Relays) + ShellRoute.Conversation(ACCOUNT_A, GROUP_ONE, open),
            legacyShellRoute(MainSection.Settings.name, SettingsDetail.Relays.name, GROUP_ONE, open, ACCOUNT_A),
        )
        assertEquals(
            CHATS_ROOT,
            legacyShellRoute(MainSection.Chats.name, SettingsDetail.Relays.name, null, open, ACCOUNT_A),
        )
        val pinned = ConversationOpenContext(pinnedAccountRef = ACCOUNT_B)
        assertEquals(
            listOf(ShellRoute.Chats, ShellRoute.Conversation(ACCOUNT_B, GROUP_ONE, pinned)),
            legacyShellRoute(MainSection.Chats.name, null, GROUP_ONE, pinned, ACCOUNT_A),
        )
    }

    /** The shadow log counts every comparison and records a disagreement with both whole values. */
    @Test
    fun theShadowLogRecordsDisagreements() {
        ShellShadowLog.reset()
        assertTrue(ShellShadowLog.compare("same", CHATS_ROOT, CHATS_ROOT))
        val differentContext = conversationA.copy(open = ConversationOpenContext(notificationOpenRequestId = 2L))
        assertFalse(
            ShellShadowLog.compare(
                "context",
                listOf(ShellRoute.Chats, conversationA),
                listOf(ShellRoute.Chats, differentContext),
            ),
        )
        assertEquals(2L, ShellShadowLog.comparisons)
        assertEquals(1L, ShellShadowLog.mismatchCount)
        assertEquals("context", ShellShadowLog.mismatches.single().origin)
        ShellShadowLog.reset()
    }

    /** Applies one event, so each test reads as a sequence of intents. */
    private fun reduce(
        stack: List<ShellRoute>,
        event: ShellNavEvent,
    ) = reduceShellRoute(stack, event)

    /** A conversation pinned to account B by a notification route carrying [trace]. */
    private fun pinnedConversation(trace: Long?) =
        ShellRoute.Conversation(
            ACCOUNT_B,
            GROUP_TWO,
            ConversationOpenContext(pinnedAccountRef = ACCOUNT_B, notificationRouteTraceRequestId = trace),
        )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val ACCOUNT_C = "account-c"
        const val GROUP_ONE = "aa01"
        const val GROUP_TWO = "bb02"
        const val RUNTIME = 1
        const val EARLY_OPEN = 7L
        val conversationA = ShellRoute.Conversation(ACCOUNT_A, GROUP_ONE, ConversationOpenContext())
        val conversationB = ShellRoute.Conversation(ACCOUNT_A, GROUP_TWO, ConversationOpenContext())
    }
}
