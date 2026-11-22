package dev.ipf.whitenoise.android.ui.navigation

import androidx.lifecycle.SavedStateHandle
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.ui.chats.realChatListMotionRow
import dev.ipf.whitenoise.android.ui.group.ChatDetailsDestination
import dev.ipf.whitenoise.android.ui.settings.settingsDetailParent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Model-based check of the route owner against an independent oracle. The oracle, [MasterShell], keeps the route the
 * way master kept it, as separate fields (the owner, the section and its Settings detail, the selected conversation
 * and the one chat-details screen open over it), and changes those fields by the plan's rules, written out here
 * without calling any production predicate. The production side is driven the way the shell drives it: owner rebinds
 * go through the same order as the process state and the section state (the owner event first, then the section's
 * bind and its one-time restore), Settings-only events go through the section state's owner gate, an account switch
 * is the rebind followed by the switch event, and process death is a fresh navigator and section state over the
 * saved keys. After every step the navigator's stack must equal the oracle's fields laid out as a stack, and the
 * saved section must agree with it. Seeds are fixed, so a failure names a sequence that reproduces exactly.
 */
class ShellNavigatorModelTest {
    /** Every seeded production-shaped sequence matches the oracle after every step, and reaches the rare cases. */
    @Test
    fun productionEventChainsMatchTheMasterFieldOracle() {
        val coverage = Coverage()
        repeat(SEEDS) { seed ->
            val random = Random(seed)
            val shell = ShellUnderTest()
            var master = MasterShell()
            val history = mutableListOf<String>()
            repeat(SCENARIOS_PER_SEQUENCE) {
                Scenarios(random, shell).next().forEach { step ->
                    coverage.record(step, shell.navigator.stack)
                    shell.apply(step)
                    master = master.after(step)
                    history += step.name
                    val failure = mismatch(shell, master)
                    if (failure != null) fail("seed $seed after $history: $failure")
                }
            }
            coverage.steps += history.size
        }
        coverage.assertReached()
    }

    /** Back from the root, delivered as the system delivers it, leaves the stack at the root and leaves the app. */
    @Test
    fun backFromTheRootLeavesTheApp() {
        val navigator = ShellNavigator()
        navigator.dispatch(ShellNavEvent.BackFrom(ShellPane.Content))
        assertEquals(CHATS_ROOT, navigator.stack)
        assertTrue(backLeavesApp(navigator.stack))
    }

    /** What differs between the shell under test and the oracle, or null when they agree. */
    private fun mismatch(
        shell: ShellUnderTest,
        master: MasterShell,
    ): String? {
        val expected = master.stack()
        val actual = shell.navigator.stack
        val persistence = shell.section.persistenceProblem()
        return when {
            actual != expected -> "stack $actual, master's fields give $expected"
            persistence != null -> "saved section disagrees: $persistence"
            else -> null
        }
    }

    /** How often the sequences reached the cases a uniform draw would almost never produce. */
    private class Coverage {
        var steps = 0
        private var landings = 0
        private var screenAcrossSwitch = 0
        private var foreignReconciles = 0
        private var restores = 0
        private var listPaneBacks = 0
        private var ownerlessSectionEvents = 0

        /** Counts [step] taken from [stack] when it is one of the rare cases. */
        fun record(
            step: Step,
            stack: List<ShellRoute>,
        ) {
            val root = conversationRoot(stack)
            val event = (step as? Step.Send)?.event
            when {
                event is ShellNavEvent.AccountSwitched ->
                    if (root?.open?.notificationRouteTraceRequestId == event.earlyOpenRequestId) landings += 1
                step is Step.Rebind && stack.last() is ShellRoute.ChatDetailsScreen -> screenAcrossSwitch += 1
                event is ShellNavEvent.ConversationRowReconciled ->
                    if (root != null && root.groupIdHex != event.groupIdHex) foreignReconciles += 1
                step is Step.ProcessDeath && step.restore != null -> restores += 1
                event == ShellNavEvent.BackFrom(ShellPane.List) -> if (root != null) listPaneBacks += 1
                event is ShellNavEvent.SectionOnly && step.ownerless -> ownerlessSectionEvents += 1
            }
        }

        /** Asserts every rare case was reached often enough for the comparison to mean something. */
        fun assertReached() {
            val reached =
                mapOf(
                    "early-open landings" to landings,
                    "rebinds with a chat-details screen open" to screenAcrossSwitch,
                    "reconciles naming another case or group" to foreignReconciles,
                    "process deaths restoring a conversation" to restores,
                    "list-pane Backs beside a conversation" to listPaneBacks,
                    "Settings events with no owner" to ownerlessSectionEvents,
                )
            println("shell-navigator-model steps=$steps $reached")
            reached.forEach { (case, count) -> assertTrue("$case reached only $count times", count >= MIN_RARE_CASES) }
            assertTrue(steps >= SEEDS * SCENARIOS_PER_SEQUENCE)
        }
    }

    private companion object {
        const val SEEDS = 2_000
        const val SCENARIOS_PER_SEQUENCE = 40
        const val MIN_RARE_CASES = 200
    }
}

/** One production action the generator takes: an event, an owner rebind, process death or a holder release. */
private sealed interface Step {
    /** The step's name in a failure's history. */
    val name: String

    /** True for a Settings-only event drawn while no account owns the section. */
    val ownerless: Boolean
        get() = false

    /** One event as the shell sends it. */
    data class Send(
        val event: ShellNavEvent,
        override val ownerless: Boolean = false,
    ) : Step {
        override val name: String get() = event.eventName
    }

    /** The process state's owner moves to [accountRef] on [runtime], and the section state binds to it. */
    data class Rebind(
        val accountRef: String?,
        val runtime: Int,
    ) : Step {
        override val name: String get() = "Rebind(${accountRef ?: "none"}, $runtime)"
    }

    /** The process dies and the same owner comes back, then [restore] reopens when the saved route names it. */
    data class ProcessDeath(
        val accountRef: String,
        val runtime: Int,
        val restore: ShellRoute.Conversation?,
    ) : Step {
        override val name: String get() = "ProcessDeath(restore=${restore != null})"
    }

    /** The holder is released, as onboarding or an isolated test does. */
    data object Release : Step {
        override val name: String get() = "Release"
    }
}

/** The production route owner wired the way the shell wires it, without Compose. */
private class ShellUnderTest {
    private var saved = SavedStateHandle()
    var navigator = ShellNavigator()
        private set
    var section = MainShellSectionState(saved, navigator)
        private set
    private var owner: Pair<String?, Int>? = null

    /** The account the process state is bound to, or null. */
    val ownerAccount: String?
        get() = owner?.first

    /** The runtime the process state is bound to, or 0 before the first bind. */
    val ownerRuntime: Int
        get() = owner?.second ?: 0

    /** Applies [step] through the same seams and in the same order as the shell. */
    fun apply(step: Step) {
        when (step) {
            is Step.Send -> send(step.event)
            is Step.Rebind -> rebind(step.accountRef, step.runtime)
            is Step.ProcessDeath -> {
                saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                navigator = ShellNavigator()
                section = MainShellSectionState(saved, navigator)
                owner = null
                rebind(step.accountRef, step.runtime)
                step.restore?.let { navigator.dispatch(ShellNavEvent.RestoreSavedConversation(it)) }
            }
            Step.Release -> {
                section.clear()
                navigator.dispatch(ShellNavEvent.ShellReleased)
                owner = null
            }
        }
    }

    /** Settings-only events pass the section state's owner gate, every other event goes straight to the owner. */
    private fun send(event: ShellNavEvent) {
        if (event is ShellNavEvent.SectionOnly) section.dispatchSection(event) else navigator.dispatch(event)
    }

    /** The process state's rebind: the owner event first, then the section state's bind, as the holder orders them. */
    private fun rebind(
        accountRef: String?,
        runtime: Int,
    ) {
        val previous = owner
        if (previous == null || previous != accountRef to runtime) {
            navigator.dispatch(
                if (accountRef == null) {
                    ShellNavEvent.OwnerLost(runtime)
                } else {
                    ShellNavEvent.OwnerReplaced(previous?.first, previous?.second, accountRef, runtime)
                },
            )
            owner = accountRef to runtime
        }
        section.bind(accountRef, runtime)
    }
}

/**
 * The oracle: the route as master kept it, in separate fields, changed by the plan's rules. [owner] and [runtime] are
 * the process owner, [sectionOwner] and [sectionRuntime] what the section was saved for, and [screen] the one
 * chat-details screen open over [chat]. Nothing here calls a production rule.
 */
private data class MasterShell(
    val owner: String? = null,
    val runtime: Int? = null,
    val sectionOwner: String? = null,
    val sectionRuntime: Int? = null,
    val section: MainSection = MainSection.Chats,
    val detail: SettingsDetail? = null,
    val chat: ShellRoute.Conversation? = null,
    val screen: ChatDetailsDestination? = null,
) {
    /** The fields laid out as a stack: the list, the Settings chain, the conversation and its screen. */
    fun stack(): List<ShellRoute> {
        val chain =
            generateSequence(detail, ::settingsDetailParent).toList().asReversed().map { ShellRoute.Settings(it) }
        val settings = listOf(ShellRoute.Settings(null)) + chain
        val sectionEntries =
            when (section) {
                MainSection.Chats -> emptyList()
                MainSection.Settings -> settings
                MainSection.Diagnostics -> settings + ShellRoute.Diagnostics
            }
        val above = if (chat == null) emptyList() else listOfNotNull(screen?.let { ShellRoute.ChatDetailsScreen(it) })
        return listOf(ShellRoute.Chats) + sectionEntries + listOfNotNull(chat) + above
    }

    /** The fields after [step]. */
    fun after(step: Step): MasterShell =
        when (step) {
            is Step.Send -> afterEvent(step.event)
            is Step.Rebind -> rebound(step.accountRef, step.runtime)
            is Step.ProcessDeath -> {
                val died = copy(owner = null, runtime = null, chat = null, screen = null)
                val back = died.rebound(step.accountRef, step.runtime)
                step.restore?.let { back.afterEvent(ShellNavEvent.RestoreSavedConversation(it)) } ?: back
            }
            Step.Release -> MasterShell()
        }

    /**
     * An owner rebind. A lost owner drops everything. Another owner drops the conversation unless it is the exact
     * notification route master carries across a same-runtime account replacement (pinned to the new account and
     * carrying a trace), and then only the conversation itself survives. A signed-out moment counts as the previous
     * account of that runtime, as on master. The section survives only for the account
     * and runtime it was saved for, which after process death is the restored section.
     */
    private fun rebound(
        accountRef: String?,
        runtimeGeneration: Int,
    ): MasterShell =
        when {
            owner == accountRef && runtime == runtimeGeneration -> this
            accountRef == null -> MasterShell(runtime = runtimeGeneration)
            else -> reboundTo(accountRef, runtimeGeneration)
        }

    /** A rebind to another account or runtime, see [rebound]. */
    private fun reboundTo(
        accountRef: String,
        runtimeGeneration: Int,
    ): MasterShell {
        val open = chat?.open
        val carried =
            owner != accountRef &&
                runtime == runtimeGeneration &&
                open?.pinnedAccountRef == accountRef &&
                open.notificationRouteTraceRequestId != null
        val sectionKept = sectionOwner == accountRef && sectionRuntime == runtimeGeneration
        return copy(
            owner = accountRef,
            runtime = runtimeGeneration,
            sectionOwner = accountRef,
            sectionRuntime = runtimeGeneration,
            section = if (sectionKept) section else MainSection.Chats,
            chat = chat.takeIf { carried },
            screen = null,
        )
    }

    /** The fields after one event. */
    private fun afterEvent(event: ShellNavEvent): MasterShell =
        when (event) {
            is ShellNavEvent.SectionOnly -> if (sectionOwner == null) this else afterSectionEvent(event)
            is ShellNavEvent.AccountSwitched -> switched(event)
            is ShellNavEvent.BackFrom -> afterBack(event.pane)
            else -> afterConversationEvent(event)
        }

    /**
     * Contract 1: a switch keeps only the conversation that is pinned to the arriving account and carries exactly the
     * early-open request that is landing, which is never 0. Everything else goes.
     */
    private fun switched(event: ShellNavEvent.AccountSwitched): MasterShell {
        val open = chat?.open
        val lands =
            event.earlyOpenRequestId != 0L &&
                open?.pinnedAccountRef == event.current &&
                open.notificationRouteTraceRequestId == event.earlyOpenRequestId
        return copy(section = MainSection.Chats, chat = chat.takeIf { lands }, screen = null)
    }

    /** Settings moves, which master applies only while an account owns the section. */
    private fun afterSectionEvent(event: ShellNavEvent.SectionOnly): MasterShell =
        when (event) {
            is ShellNavEvent.OpenSettings -> copy(section = MainSection.Settings, detail = event.detail)
            is ShellNavEvent.ChangeSettingsDetail ->
                if (section == MainSection.Chats) this else copy(detail = event.detail)
            ShellNavEvent.ExitSettings -> copy(section = MainSection.Chats)
            ShellNavEvent.OpenDiagnostics -> copy(section = MainSection.Diagnostics)
        }

    /** The conversation's own events: opens, jumps, re-lands, reconciles, its screens, closes and boundaries. */
    private fun afterConversationEvent(event: ShellNavEvent): MasterShell =
        when (event) {
            is ShellNavEvent.OpenConversation -> selecting(event.conversation)
            is ShellNavEvent.RestoreSavedConversation -> selecting(event.conversation)
            is ShellNavEvent.JumpToConversation -> selecting(event.conversation).copy(section = MainSection.Chats)
            is ShellNavEvent.CommitNotificationRoute -> selecting(event.conversation).copy(section = MainSection.Chats)
            is ShellNavEvent.JumpWithin -> relanded(event)
            is ShellNavEvent.ConversationRowReconciled ->
                chat
                    ?.takeIf { sameChat(it, event.accountRef, event.groupIdHex) }
                    ?.let { copy(chat = it.copy(item = event.item ?: it.item)) } ?: this
            is ShellNavEvent.OpenChatDetailsScreen -> if (chat == null) this else copy(screen = event.destination)
            else -> afterClosingEvent(event)
        }

    /** A re-land in the open chat: a fresh open context and row, and Settings left, or nothing without a chat. */
    private fun relanded(event: ShellNavEvent.JumpWithin): MasterShell {
        val open = chat ?: return this
        val next = open.copy(open = event.open, item = event.item ?: open.item)
        return copy(chat = next, section = MainSection.Chats)
    }

    /** Events that close the conversation, its screen or everything. */
    private fun afterClosingEvent(event: ShellNavEvent): MasterShell =
        when (event) {
            ShellNavEvent.CloseChatDetailsScreens -> copy(screen = null)
            ShellNavEvent.CloseConversation, ShellNavEvent.TaskRemoved -> copy(chat = null, screen = null)
            ShellNavEvent.ReturnToChats -> copy(section = MainSection.Chats, chat = null, screen = null)
            is ShellNavEvent.OwnerLost, ShellNavEvent.ShellReleased -> MasterShell()
            else -> error("the generator never draws ${event.eventName} as a conversation event")
        }

    /** Selects [next]. The same chat keeps its open screen, any other chat starts with none. */
    private fun selecting(next: ShellRoute.Conversation): MasterShell {
        val same = chat?.let { sameChat(it, next.accountRef, next.groupIdHex) } ?: false
        return copy(chat = next, screen = screen.takeIf { same })
    }

    /**
     * System Back in [pane]. In the content pane, or with no conversation, it closes the screen, else the conversation,
     * else steps the section back. In the list pane beside a conversation it steps the section back and keeps the chat.
     */
    private fun afterBack(pane: ShellPane): MasterShell =
        when {
            pane == ShellPane.List && chat != null -> sectionBack()
            screen != null && chat != null -> copy(screen = null)
            chat != null -> copy(chat = null, screen = null)
            else -> sectionBack()
        }

    /** One step back in the section: Diagnostics to its Settings screen, a detail to its parent, home to the list. */
    private fun sectionBack(): MasterShell =
        when {
            section == MainSection.Diagnostics -> copy(section = MainSection.Settings)
            section == MainSection.Settings && detail != null -> copy(detail = settingsDetailParent(detail))
            else -> copy(section = MainSection.Chats)
        }

    private companion object {
        /** Master's same-chat rule: the same account and the same group, whatever the hex case. */
        fun sameChat(
            chat: ShellRoute.Conversation,
            accountRef: String?,
            groupIdHex: String,
        ): Boolean = chat.accountRef == accountRef && chat.groupIdHex.lowercase() == groupIdHex.lowercase()
    }
}

/**
 * Draws one production-shaped scenario from the current [shell], a short chain of steps the app actually takes, with
 * weights that favour the account boundary, the early open, the screens above a conversation and process death. It
 * reads only what the shell itself knows, never the oracle.
 */
private class Scenarios(
    private val random: Random,
    private val shell: ShellUnderTest,
) {
    private val root = conversationRoot(shell.navigator.stack)
    private val owner = shell.ownerAccount
    private val runtime = shell.ownerRuntime

    /** The next scenario's steps. Without an owner most scenarios first bind one. */
    fun next(): List<Step> {
        val bindFirst = owner == null && random.nextInt(OWNERLESS_ODDS) != 0
        if (bindFirst) return listOf(Step.Rebind(ACCOUNTS.random(random), runtime))
        return when (random.nextInt(SCENARIO_KINDS)) {
            in 0..2 -> listOf(Step.Send(conversationEvent()))
            in 3..4 -> listOf(Step.Send(screenEvent()))
            in 5..6 -> listOf(Step.Send(sectionEvent(), ownerless = owner == null))
            7 -> listOf(Step.Send(backEvent()))
            in 8..9 -> earlyOpenAndLanding()
            else -> boundaryScenario()
        }
    }

    /** An account switch, a runtime replacement, process death, a signed-out moment, task removal or a release. */
    private fun boundaryScenario(): List<Step> =
        when (random.nextInt(BOUNDARY_KINDS)) {
            0 -> accountSwitch(ACCOUNTS.filterNot { it == owner }.random(random), randomEarlyOpenId())
            1 -> listOf(Step.Rebind(owner, runtime + 1))
            2 -> processDeath()
            3 -> listOf(Step.Rebind(null, runtime))
            4 -> listOf(Step.Send(ShellNavEvent.TaskRemoved))
            5 -> diagnosticsBesideTheChat()
            else -> listOf(Step.Release)
        }

    /** A list tap, a same-account notification, a jump, a re-land, a reconcile, a close or a return to the list. */
    private fun conversationEvent(): ShellNavEvent =
        when (random.nextInt(CONVERSATION_KINDS)) {
            0, 1 -> ShellNavEvent.OpenConversation(ownChat(ConversationOpenContext()))
            2 -> ShellNavEvent.CommitNotificationRoute(ownChat(notificationOpen(pinned = null)))
            3 -> ShellNavEvent.JumpToConversation(ownChat(ConversationOpenContext()))
            4 -> ShellNavEvent.JumpWithin(notificationOpen(root?.open?.pinnedAccountRef), ITEMS.random(random))
            5 -> ShellNavEvent.ConversationRowReconciled(owner, reconciledGroup(), ITEMS.random(random))
            6 -> ShellNavEvent.CloseConversation
            else -> ShellNavEvent.ReturnToChats
        }

    /** A chat-details screen opening, mostly, or details closing. */
    private fun screenEvent(): ShellNavEvent =
        if (random.nextInt(SCREEN_CLOSE_ODDS) == 0) {
            ShellNavEvent.CloseChatDetailsScreens
        } else {
            ShellNavEvent.OpenChatDetailsScreen(ChatDetailsDestination.entries.random(random))
        }

    /** A Settings move the open section allows: open from anywhere, and from Settings a detail, exit or Diagnostics. */
    private fun sectionEvent(): ShellNavEvent.SectionOnly {
        val (section, detail) = sectionProjection(shell.navigator.stack)
        return when {
            section != MainSection.Settings || random.nextInt(SETTINGS_REOPEN_ODDS) == 0 ->
                ShellNavEvent.OpenSettings(randomDetail())
            random.nextBoolean() -> ShellNavEvent.ChangeSettingsDetail(randomDetail())
            random.nextBoolean() -> ShellNavEvent.OpenDiagnostics
            detail != null -> ShellNavEvent.ChangeSettingsDetail(settingsDetailParent(detail))
            else -> ShellNavEvent.ExitSettings
        }
    }

    /** System Back: from the list pane only beside Diagnostics, as the shell sends it, else in the content pane. */
    private fun backEvent(): ShellNavEvent {
        val diagnostics = sectionProjection(shell.navigator.stack).first == MainSection.Diagnostics
        val pane = if (diagnostics && root != null && random.nextBoolean()) ShellPane.List else ShellPane.Content
        return ShellNavEvent.BackFrom(pane)
    }

    /**
     * At two-pane width Settings opens beside the open chat at Developer, Diagnostics opens above it, and Back in the
     * list pane steps back through the section while the chat stays open.
     */
    private fun diagnosticsBesideTheChat(): List<Step> {
        val walk =
            listOf(
                ShellNavEvent.OpenSettings(SettingsDetail.Developer),
                ShellNavEvent.OpenDiagnostics,
                ShellNavEvent.BackFrom(ShellPane.List),
            )
        return walk.map { Step.Send(it, ownerless = owner == null) }
    }

    /**
     * A notification for another account opens its conversation early, pinned to that account, often with a screen
     * opened above it, and then the account switch lands, usually carrying that same request.
     */
    private fun earlyOpenAndLanding(): List<Step> {
        val target = ACCOUNTS.filterNot { it == owner }.random(random)
        val requestId = REQUEST_IDS.random(random)
        val open = ConversationOpenContext(notificationRouteTraceRequestId = requestId, pinnedAccountRef = target)
        val early = ShellRoute.Conversation(target, randomGroup(), open)
        val screen = ShellNavEvent.OpenChatDetailsScreen(ChatDetailsDestination.entries.random(random))
        val opened =
            listOf(Step.Send(ShellNavEvent.CommitNotificationRoute(early))) +
                if (random.nextBoolean()) listOf(Step.Send(screen)) else emptyList()
        val landing = if (random.nextInt(STALE_LANDING_ODDS) == 0) randomEarlyOpenId() else requestId
        return opened + accountSwitch(target, landing)
    }

    /** The shell's account switch: the owner rebinds in composition, then the switch effect sends its event. */
    private fun accountSwitch(
        target: String,
        earlyOpenRequestId: Long,
    ): List<Step> {
        val rebind = Step.Rebind(target, runtime)
        val previous = owner ?: return listOf(rebind)
        return listOf(rebind, Step.Send(ShellNavEvent.AccountSwitched(previous, target, earlyOpenRequestId)))
    }

    /** Process death with the same owner coming back, reopening the saved conversation when it was the owner's. */
    private fun processDeath(): List<Step> {
        val account = owner ?: return listOf(Step.Rebind(ACCOUNTS.random(random), runtime))
        val restore =
            root?.takeIf { it.accountRef == account }?.let {
                ShellRoute.Conversation(account, it.groupIdHex, ConversationOpenContext(), ITEMS.random(random))
            }
        return listOf(Step.ProcessDeath(account, runtime, restore))
    }

    /** A chat of the owner's account opened with [open], or of a random account while there is no owner. */
    private fun ownChat(open: ConversationOpenContext): ShellRoute.Conversation =
        ShellRoute.Conversation(owner ?: ACCOUNTS.random(random), randomGroup(), open, ITEMS.random(random))

    /** A notification open context with a fresh trace, pinned to [pinned]. */
    private fun notificationOpen(pinned: String?): ConversationOpenContext {
        val requestId = REQUEST_IDS.random(random)
        return ConversationOpenContext(
            notificationOpenRequestId = requestId,
            notificationRouteTraceRequestId = requestId,
            pinnedAccountRef = pinned,
        )
    }

    /** The open chat's group, sometimes in another case, and now and then another group. */
    private fun reconciledGroup(): String {
        val group = root?.groupIdHex ?: return randomGroup()
        return when (random.nextInt(RECONCILE_KINDS)) {
            0 -> group.uppercase()
            1 -> GROUPS.first { !it.equals(group, ignoreCase = true) }
            else -> group
        }
    }

    /** An early-open id that may or may not be the landing one: 0 (none) or any request id. */
    private fun randomEarlyOpenId(): Long = (REQUEST_IDS + 0L).random(random)

    /** One of the groups, the shared one in another case now and then. */
    private fun randomGroup(): String {
        val group = GROUPS.random(random)
        return if (random.nextInt(CASE_ODDS) == 0) group.uppercase() else group
    }

    /** A random Settings detail, or Settings home. */
    private fun randomDetail(): SettingsDetail? {
        val home = random.nextInt(DETAIL_HOME_ODDS) == 0
        return if (home) null else SettingsDetail.entries.random(random)
    }

    private companion object {
        const val SCENARIO_KINDS = 17
        const val BOUNDARY_KINDS = 7
        const val CONVERSATION_KINDS = 8
        const val OWNERLESS_ODDS = 6
        const val SCREEN_CLOSE_ODDS = 4
        const val SETTINGS_REOPEN_ODDS = 5
        const val STALE_LANDING_ODDS = 4
        const val RECONCILE_KINDS = 3
        const val CASE_ODDS = 6
        const val DETAIL_HOME_ODDS = 4

        /** Two accounts share every group, so the account alone tells their chats apart. */
        val ACCOUNTS = listOf("account-a", "account-b", "account-c")
        val GROUPS = listOf("aa01", "bb02")
        val REQUEST_IDS = listOf(1L, 2L, 3L)
        val ITEMS: List<ChatListItem?> = listOf(null, realChatListMotionRow("aa01"), realChatListMotionRow("bb02"))
    }
}
