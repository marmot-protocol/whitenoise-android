package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.ui.group.ChatDetailsDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Model-based check of the shadow navigator: thousands of seeded random event sequences, each event checked against
 * the plan's invariants. Seeds are fixed, so a failure names a sequence that reproduces exactly.
 */
class ShellNavigatorModelTest {
    /** Every seeded sequence keeps every invariant after every event. */
    @Test
    fun randomEventSequencesKeepTheInvariants() {
        var checkedEvents = 0
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val navigator = ShellNavigator()
            val history = mutableListOf<ShellNavEvent>()
            repeat(EVENTS_PER_SEQUENCE) {
                val before = navigator.stack
                val event = randomEvent(random, before)
                history += event
                navigator.dispatch(event)
                val failure = invariantFailure(before, event, navigator.stack)
                if (failure != null) fail("seed $seed after ${history.map { it.eventName }}: $failure")
                checkedEvents += 1
            }
        }
        assertEquals(SEEDS * EVENTS_PER_SEQUENCE, checkedEvents)
    }

    /** Back from the root leaves the stack at the root and signals leaving the app. */
    @Test
    fun backFromTheRootLeavesTheApp() {
        val navigator = ShellNavigator()
        navigator.dispatch(ShellNavEvent.Back)
        assertEquals(CHATS_ROOT, navigator.stack)
        assertTrue(backLeavesApp(navigator.stack))
    }

    /** Returns the first broken invariant for [event] applied to [before] giving [after], or null. */
    private fun invariantFailure(
        before: List<ShellRoute>,
        event: ShellNavEvent,
        after: List<ShellRoute>,
    ): String? =
        shapeFailure(after)
            ?: boundaryFailure(before, event, after)
            ?: conjuredConversationFailure(before, event, after)
            ?: savedSectionFailure(after)

    /**
     * Never empty, rooted at Chats, Chats only at the root, at most one conversation, and above it nothing but at
     * most one of its chat-details screens, which never appear without it.
     */
    private fun shapeFailure(after: List<ShellRoute>): String? {
        val conversations = after.count { it is ShellRoute.Conversation }
        val above = conversationSegment(after).drop(1)
        return when {
            after.isEmpty() -> "empty stack"
            after.first() != ShellRoute.Chats -> "root is ${after.first()}"
            after.drop(1).any { it == ShellRoute.Chats } -> "Chats above the root"
            conversations > 1 -> "$conversations conversations"
            above.any { it !is ShellRoute.ChatDetailsScreen } || above.size > 1 -> "$above above the conversation"
            sectionOf(after).any { it is ShellRoute.ChatDetailsScreen } -> "a chat-details screen without its chat"
            else -> null
        }
    }

    /** A switch leaves exactly the root or the contract-1 landing, an owner loss or release leaves only the root. */
    private fun boundaryFailure(
        before: List<ShellRoute>,
        event: ShellNavEvent,
        after: List<ShellRoute>,
    ): String? {
        val root = conversationRoot(before)
        return when (event) {
            is ShellNavEvent.AccountSwitched -> {
                val lands = root != null && earlyOpenLandsPinnedAccount(root, event.current, event.earlyOpenRequestId)
                val expected = if (lands) listOf(ShellRoute.Chats, root!!) else CHATS_ROOT
                if (after == expected) null else "switch left $after, expected $expected"
            }
            is ShellNavEvent.OwnerLost, ShellNavEvent.ShellReleased, ShellNavEvent.ReturnToChats ->
                if (after == CHATS_ROOT) null else "${event.eventName} left $after"
            is ShellNavEvent.OwnerReplaced ->
                if (after in listOf(CHATS_ROOT, listOfNotNull(ShellRoute.Chats, root))) null else "rebind left $after"
            ShellNavEvent.Back ->
                if (after == if (backLeavesApp(before)) before else before.dropLast(1)) null else "Back left $after"
            else -> null
        }
    }

    /**
     * No event conjures a conversation it did not carry: a conversation after an event that names none was already
     * there with the same identity, so nothing an owner loss removed can come back on its own.
     */
    private fun conjuredConversationFailure(
        before: List<ShellRoute>,
        event: ShellNavEvent,
        after: List<ShellRoute>,
    ): String? {
        val carried =
            when (event) {
                is ShellNavEvent.OpenConversation -> event.conversation
                is ShellNavEvent.JumpToConversation -> event.conversation
                is ShellNavEvent.CommitNotificationRoute -> event.conversation
                is ShellNavEvent.RestoreSavedConversation -> event.conversation
                else -> null
            }
        val previous = conversationRoot(before)
        val next = conversationRoot(after) ?: return null
        return when {
            carried != null -> if (next == carried) null else "opened $next instead of $carried"
            previous == null -> "${event.eventName} conjured $next"
            previous.accountRef != next.accountRef -> "${event.eventName} moved the conversation to another account"
            else -> null
        }
    }

    /**
     * The section between the root and the conversation is exactly what master's saved section and detail restore,
     * so a saved-state round trip through [ShellNavEvent.SectionRestored] gives the same stack back.
     */
    private fun savedSectionFailure(after: List<ShellRoute>): String? {
        val section = sectionOf(after).drop(1)
        val savedSection =
            when {
                section.isEmpty() -> MainSection.Chats
                section.last() == ShellRoute.Diagnostics -> MainSection.Diagnostics
                else -> MainSection.Settings
            }
        val savedDetail = section.filterIsInstance<ShellRoute.Settings>().lastOrNull()?.detail
        val restored = reduceShellRoute(after, ShellNavEvent.SectionRestored(savedSection, savedDetail))
        return if (restored == after) null else "saved section round trip gave $restored for $after"
    }

    /** Draws one event, favouring the conversation and the account boundary that the contract is about. */
    private fun randomEvent(
        random: Random,
        stack: List<ShellRoute>,
    ): ShellNavEvent {
        val kind = random.nextInt(EVENT_KINDS)
        return if (kind < SECTION_KINDS_START) {
            randomConversationEvent(random, kind, conversationRoot(stack))
        } else {
            randomSectionOrBoundaryEvent(random, kind, conversationRoot(stack))
        }
    }

    /** Draws one of the conversation events for [kind]. */
    private fun randomConversationEvent(
        random: Random,
        kind: Int,
        root: ShellRoute.Conversation?,
    ): ShellNavEvent =
        when (kind) {
            0 -> ShellNavEvent.OpenConversation(randomConversation(random))
            1 -> ShellNavEvent.JumpToConversation(randomConversation(random))
            2 -> ShellNavEvent.CommitNotificationRoute(randomConversation(random))
            3 -> ShellNavEvent.JumpWithin(randomOpen(random))
            4 -> ShellNavEvent.ConversationRowReconciled(root?.groupIdHex ?: GROUPS.random(random))
            5 -> ShellNavEvent.RestoreSavedConversation(randomConversation(random))
            6 -> ShellNavEvent.CloseConversation
            7 -> ShellNavEvent.ReturnToChats
            else -> randomBackOrDetailsEvent(random)
        }

    /** A Back, a pane Back, or a chat-details screen opening or closing above the conversation. */
    private fun randomBackOrDetailsEvent(random: Random): ShellNavEvent =
        when (random.nextInt(BACK_OR_DETAILS_KINDS)) {
            0 -> ShellNavEvent.Back
            1 -> ShellNavEvent.BackFrom(ShellPane.entries.random(random))
            2 -> ShellNavEvent.CloseChatDetailsScreens
            else -> ShellNavEvent.OpenChatDetailsScreen(ChatDetailsDestination.entries.random(random))
        }

    /** Draws one of the Settings or boundary events for [kind]. */
    private fun randomSectionOrBoundaryEvent(
        random: Random,
        kind: Int,
        root: ShellRoute.Conversation?,
    ): ShellNavEvent =
        when (kind) {
            9 -> ShellNavEvent.OpenSettings(randomDetail(random))
            10 -> ShellNavEvent.ChangeSettingsDetail(randomDetail(random))
            11 -> ShellNavEvent.ExitSettings
            12 -> ShellNavEvent.OpenDiagnostics
            13 -> ShellNavEvent.Back
            14 -> ShellNavEvent.SectionRestored(MainSection.entries.random(random), randomDetail(random))
            15, 16 -> randomSwitch(random, root)
            17 -> randomRebind(random)
            18 -> ShellNavEvent.OwnerLost(random.nextInt(RUNTIMES))
            19 -> ShellNavEvent.TaskRemoved
            else -> ShellNavEvent.ShellReleased
        }

    /** A switch between two distinct accounts, half the time carrying the open conversation's own trace id. */
    private fun randomSwitch(
        random: Random,
        root: ShellRoute.Conversation?,
    ): ShellNavEvent.AccountSwitched {
        val previous = ACCOUNTS.random(random)
        val current = ACCOUNTS.filterNot { it == previous }.random(random)
        val earlyOpen =
            if (random.nextBoolean()) root?.open?.notificationRouteTraceRequestId ?: 0L else REQUEST_IDS.random(random)
        return ShellNavEvent.AccountSwitched(previous, current, earlyOpen)
    }

    /** An owner rebind with a random previous and next owner. */
    private fun randomRebind(random: Random): ShellNavEvent.OwnerReplaced {
        val previousRuntime = if (random.nextBoolean()) random.nextInt(RUNTIMES) else null
        return ShellNavEvent.OwnerReplaced(
            previousAccountRef = ACCOUNTS.random(random).takeIf { previousRuntime != null },
            previousRuntimeGeneration = previousRuntime,
            accountRef = ACCOUNTS.random(random),
            runtimeGeneration = random.nextInt(RUNTIMES),
        )
    }

    /** A conversation on a random account and group with a random open context. */
    private fun randomConversation(random: Random): ShellRoute.Conversation {
        val open = randomOpen(random)
        return ShellRoute.Conversation(open.pinnedAccountRef ?: ACCOUNTS.random(random), GROUPS.random(random), open)
    }

    /** An open context that is sometimes pinned and sometimes carries a notification trace id. */
    private fun randomOpen(random: Random): ConversationOpenContext {
        val requestId = REQUEST_IDS.random(random)
        return ConversationOpenContext(
            notificationOpenRequestId = requestId,
            notificationRouteTraceRequestId = requestId.takeIf { random.nextBoolean() && it != 0L },
            pinnedAccountRef = ACCOUNTS.random(random).takeIf { random.nextBoolean() },
        )
    }

    /** A random Settings detail, or Settings home. */
    private fun randomDetail(random: Random): SettingsDetail? {
        val home = random.nextInt(DETAIL_HOME_ODDS) == 0
        return if (home) null else SettingsDetail.entries.random(random)
    }

    private companion object {
        const val SEEDS = 4_000
        const val EVENTS_PER_SEQUENCE = 60
        const val EVENT_KINDS = 21
        const val SECTION_KINDS_START = 9
        const val RUNTIMES = 2
        const val DETAIL_HOME_ODDS = 4
        const val BACK_OR_DETAILS_KINDS = 5
        val ACCOUNTS = listOf("account-a", "account-b", "account-c")
        val GROUPS = listOf("aa01", "bb02")
        val REQUEST_IDS = listOf(0L, 1L, 2L, 3L)
    }
}
