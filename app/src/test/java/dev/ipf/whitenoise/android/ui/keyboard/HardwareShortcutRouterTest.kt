package dev.ipf.whitenoise.android.ui.keyboard

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Key mapping, once-per-press dispatch, blocking and pane rules of the messaging shortcuts (#4005). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class HardwareShortcutRouterTest {
    private var now = 1_000L
    private var appLocked = false
    private val afterFrame = mutableListOf<() -> Unit>()
    private val router =
        HardwareShortcutRouter(inputBlocked = { appLocked }, uptimeMillis = { now }, afterFrame = { afterFrame += it })

    /** Only Ctrl with the exact Shift state names a command, so Alt, Meta and bare letters never match. */
    @Test
    fun onlyCtrlWithTheExactShiftStateNamesACommand() {
        assertEquals(HardwareShortcutCommand.GlobalSearch, command(KeyEvent.KEYCODE_F, CTRL))
        assertEquals(HardwareShortcutCommand.ConversationSearch, command(KeyEvent.KEYCODE_F, CTRL or SHIFT))
        assertEquals(HardwareShortcutCommand.NewMessage, command(KeyEvent.KEYCODE_N, CTRL))
        assertNull(command(KeyEvent.KEYCODE_F, 0))
        assertNull(command(KeyEvent.KEYCODE_F, SHIFT))
        assertNull(command(KeyEvent.KEYCODE_N, CTRL or SHIFT))
        assertNull(command(KeyEvent.KEYCODE_F, CTRL or KeyEvent.META_ALT_ON))
        assertNull(command(KeyEvent.KEYCODE_F, CTRL or KeyEvent.META_META_ON))
    }

    /** Ctrl+A, C, V, X and Z, Enter and Shift+Enter pass through even while every command has an owner. */
    @Test
    fun editingKeysAndEnterPassThroughWithEveryCommandOwned() {
        val runs = registerAll()
        val passThrough =
            listOf(
                KeyEvent.KEYCODE_A to CTRL,
                KeyEvent.KEYCODE_C to CTRL,
                KeyEvent.KEYCODE_V to CTRL,
                KeyEvent.KEYCODE_X to CTRL,
                KeyEvent.KEYCODE_Z to CTRL,
                KeyEvent.KEYCODE_Z to (CTRL or SHIFT),
                KeyEvent.KEYCODE_ENTER to 0,
                KeyEvent.KEYCODE_ENTER to SHIFT,
                KeyEvent.KEYCODE_NUMPAD_ENTER to 0,
                KeyEvent.KEYCODE_F to 0,
                KeyEvent.KEYCODE_N to SHIFT,
            )
        passThrough.forEach { (keyCode, meta) ->
            assertFalse("$keyCode/$meta", router.handle(down(keyCode, meta)))
            assertFalse("$keyCode/$meta", router.handle(up(keyCode, meta)))
        }
        assertEquals(emptyMap<HardwareShortcutCommand, Int>(), runs.filterValues { it > 0 })
    }

    /** A held shortcut runs once: auto-repeats and the key-up are consumed, and the next press runs it again. */
    @Test
    fun eachCommandRunsOncePerPress() {
        val runs = registerAll()

        assertTrue(router.handle(down(KeyEvent.KEYCODE_N, CTRL)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_N, CTRL, repeat = 1)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_N, CTRL, repeat = 2)))
        assertTrue(router.handle(up(KeyEvent.KEYCODE_N, CTRL)))
        assertEquals(1, runs.getValue(HardwareShortcutCommand.NewMessage))

        assertTrue(router.handle(down(KeyEvent.KEYCODE_N, CTRL)))
        assertEquals(2, runs.getValue(HardwareShortcutCommand.NewMessage))
        assertEquals(0, runs.getValue(HardwareShortcutCommand.GlobalSearch))
    }

    /**
     * Releasing Ctrl before F keeps F's auto-repeats and key-up consumed, so a held key never types into the field
     * the command focused. The next bare press of F, and a lost key-up replaced by a new press, pass through.
     */
    @Test
    fun heldShortcutKeyStaysConsumedAfterItsModifierIsReleasedFirst() {
        val runs = registerAll()

        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, CTRL)))
        assertFalse(router.handle(up(KeyEvent.KEYCODE_CTRL_LEFT, 0)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, 0, repeat = 1)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, 0, repeat = 2)))
        assertTrue(router.handle(up(KeyEvent.KEYCODE_F, 0)))
        assertFalse(router.handle(down(KeyEvent.KEYCODE_F, 0)))
        assertFalse(router.handle(up(KeyEvent.KEYCODE_F, 0)))

        // Shift released first works the same way, and an unrelated key in between is left alone.
        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, CTRL or SHIFT)))
        assertFalse(router.handle(up(KeyEvent.KEYCODE_SHIFT_LEFT, CTRL)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, CTRL, repeat = 1)))
        assertFalse(router.handle(down(KeyEvent.KEYCODE_A, 0)))
        // Its key-up was lost: a new bare press of F ends the old press and passes through, and so do its repeats.
        assertFalse(router.handle(down(KeyEvent.KEYCODE_F, 0)))
        assertFalse(router.handle(down(KeyEvent.KEYCODE_F, 0, repeat = 1)))
        assertFalse(router.handle(up(KeyEvent.KEYCODE_F, 0)))

        assertEquals(1, runs.getValue(HardwareShortcutCommand.GlobalSearch))
        assertEquals(1, runs.getValue(HardwareShortcutCommand.ConversationSearch))
    }

    /** Overlapping shortcuts each keep their own physical key consumed until that key comes up, in either order. */
    @Test
    fun overlappingShortcutKeysEachStayConsumedUntilTheirOwnKeyUp() {
        val runs = registerAll()

        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, CTRL)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_N, CTRL)))
        assertFalse(router.handle(up(KeyEvent.KEYCODE_CTRL_LEFT, 0)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, 0, repeat = 1)))
        assertTrue(router.handle(up(KeyEvent.KEYCODE_N, 0)))
        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, 0, repeat = 2)))
        assertTrue(router.handle(up(KeyEvent.KEYCODE_F, 0)))
        assertFalse(router.handle(down(KeyEvent.KEYCODE_N, 0, repeat = 1)))
        assertFalse(router.handle(up(KeyEvent.KEYCODE_N, 0)))

        assertEquals(1, runs.getValue(HardwareShortcutCommand.GlobalSearch))
        assertEquals(1, runs.getValue(HardwareShortcutCommand.NewMessage))
    }

    /** A command without an available owner leaves the key, its repeats and its key-up to the focused view. */
    @Test
    fun unavailableCommandsAreNotConsumed() {
        var available = false
        var runs = 0
        router.register(HardwareShortcutHandler(HardwareShortcutCommand.GlobalSearch, { available }) { runs++ })

        assertFalse(router.handle(down(KeyEvent.KEYCODE_F, CTRL)))
        assertFalse(router.handle(down(KeyEvent.KEYCODE_F, CTRL, repeat = 1)))
        assertFalse(router.handle(up(KeyEvent.KEYCODE_F, CTRL)))
        available = true
        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, CTRL)))
        assertEquals(1, runs)
    }

    /** App Lock and in-window blockers make every command unavailable, and a released blocker cannot release twice. */
    @Test
    fun appLockAndBlockersMakeEveryCommandUnavailable() {
        val runs = registerAll()

        appLocked = true
        assertFalse(router.handle(down(KeyEvent.KEYCODE_F, CTRL)))
        assertEquals(emptyList<HardwareShortcutCommand>(), router.availableCommands())
        appLocked = false

        val releaseFirst = router.block()
        val releaseSecond = router.block()
        releaseFirst()
        releaseFirst()
        assertFalse(router.handle(down(KeyEvent.KEYCODE_N, CTRL)))
        releaseSecond()
        assertTrue(router.handle(down(KeyEvent.KEYCODE_N, CTRL)))
        assertEquals(0, runs.getValue(HardwareShortcutCommand.GlobalSearch))
        assertEquals(1, runs.getValue(HardwareShortcutCommand.NewMessage))
    }

    /** The newest available owner takes a command, and a removed owner no longer runs. */
    @Test
    fun newestAvailableOwnerTakesTheCommand() {
        val order = mutableListOf<String>()
        var newerAvailable = true
        router.register(HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) { order += "older" })
        val unregister =
            router.register(
                HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { newerAvailable }) { order += "newer" },
            )

        router.dispatch(HardwareShortcutCommand.NewMessage)
        newerAvailable = false
        router.dispatch(HardwareShortcutCommand.NewMessage)
        newerAvailable = true
        unregister()
        router.dispatch(HardwareShortcutCommand.NewMessage)

        assertEquals(listOf("newer", "older", "older"), order)
    }

    /** Side by side, in-chat search belongs to the conversation pane, while a compact conversation always owns it. */
    @Test
    fun inChatSearchFollowsThePaneThatOwnsTheKeys() {
        val runs = registerAll()
        var split = true
        var conversationOwnsKeys = false
        router.attachPanes(split = { split }, conversationOwnsKeys = { conversationOwnsKeys })

        assertFalse(router.dispatch(HardwareShortcutCommand.ConversationSearch))
        assertTrue(HardwareShortcutCommand.ConversationSearch !in router.availableCommands())
        assertTrue(router.dispatch(HardwareShortcutCommand.GlobalSearch))
        conversationOwnsKeys = true
        assertTrue(router.dispatch(HardwareShortcutCommand.ConversationSearch))
        conversationOwnsKeys = false
        split = false
        assertTrue(router.dispatch(HardwareShortcutCommand.ConversationSearch))

        assertEquals(2, runs.getValue(HardwareShortcutCommand.ConversationSearch))
        assertEquals(1, runs.getValue(HardwareShortcutCommand.GlobalSearch))
    }

    /** A compact conversation leaves for Chats, which runs the carried command once the conversation is gone. */
    @Test
    fun compactConversationCarriesChatsCommandsOnce() {
        var exits = 0
        val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) { exits++ })
        var searches = 0
        val chatsSearch = HardwareShortcutHandler(HardwareShortcutCommand.GlobalSearch, { true }) { searches++ }
        router.register(chatsSearch)

        assertTrue(router.handle(down(KeyEvent.KEYCODE_F, CTRL)))
        assertEquals(1, exits)
        // The outgoing conversation still covers Chats, and another command's owner can never take Ctrl+F.
        assertEquals(CarriedFate.Wait, router.carriedFate(chatsSearch))
        removeConversation()
        val otherOwner = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) {}
        assertEquals(CarriedFate.None, router.carriedFate(otherOwner))
        assertEquals(CarriedFate.Run, router.carriedFate(chatsSearch))
        router.settleCarried(chatsSearch)
        router.settleCarried(chatsSearch)

        assertEquals(1, searches)
        assertEquals(1, exits)
    }

    /** While a compact conversation is composed, a retained Chats screen behind it never takes Chats commands. */
    @Test
    fun composedCompactConversationCoversChats() {
        var conversationSettled = false
        var exits = 0
        router.registerChatsReturn(ChatsReturnRoute({ conversationSettled }) { exits++ })
        val runs = registerAll()

        assertFalse(router.dispatch(HardwareShortcutCommand.GlobalSearch))
        assertFalse(router.dispatch(HardwareShortcutCommand.NewMessage))
        assertEquals(listOf(HardwareShortcutCommand.ConversationSearch), router.availableCommands())
        conversationSettled = true
        assertTrue(router.dispatch(HardwareShortcutCommand.NewMessage))

        assertEquals(1, exits)
        assertEquals(0, runs.getValue(HardwareShortcutCommand.GlobalSearch))
        assertEquals(0, runs.getValue(HardwareShortcutCommand.NewMessage))
    }

    /** An in-place Chats flow holds only Chats commands, so in-chat search beside it still works. */
    @Test
    fun chatsOnlyBlockerLeavesInChatSearch() {
        val runs = registerAll()
        router.attachPanes(split = { true }, conversationOwnsKeys = { true })
        val release = router.block(chatsOnly = true)

        assertFalse(router.dispatch(HardwareShortcutCommand.GlobalSearch))
        assertFalse(router.dispatch(HardwareShortcutCommand.NewMessage))
        assertTrue(router.dispatch(HardwareShortcutCommand.ConversationSearch))
        release()
        assertTrue(router.dispatch(HardwareShortcutCommand.NewMessage))

        assertEquals(0, runs.getValue(HardwareShortcutCommand.GlobalSearch))
        assertEquals(1, runs.getValue(HardwareShortcutCommand.NewMessage))
    }

    /**
     * When Chats is uncovered, an owner composed in the same frame still takes the carried command, but without
     * one by the end of that frame (its control is hidden) the command lapses and a later owner never runs it.
     */
    @Test
    fun carriedCommandNeedsAnOwnerByTheEndOfTheUncoveringFrame() {
        var runs = 0
        val button = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) { runs++ }

        var removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
        router.dispatch(HardwareShortcutCommand.NewMessage)
        removeConversation()
        val removeButton = router.register(button)
        runAfterFrame()
        router.settleCarried(button)
        assertEquals(1, runs)

        removeButton()
        removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
        router.dispatch(HardwareShortcutCommand.NewMessage)
        removeConversation()
        runAfterFrame()
        router.register(button)
        assertEquals(CarriedFate.None, router.carriedFate(button))
        router.settleCarried(button)
        assertEquals(1, runs)
    }

    /**
     * Once Chats is uncovered, a carried command waits only while its owner is still finding out. An owner that is
     * registered but hidden or refusing lets it lapse, so it never runs when that owner later becomes available.
     */
    @Test
    fun carriedCommandWaitsOnlyForAnOwnerThatIsStillFindingOut() {
        var available = false
        var checking = true
        var runs = 0
        val button = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { available }, { checking }) { runs++ }
        router.register(button)
        val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
        router.dispatch(HardwareShortcutCommand.NewMessage)
        removeConversation()
        runAfterFrame()

        assertEquals(CarriedFate.Wait, router.carriedFate(button))
        checking = false
        assertEquals(CarriedFate.Lapse, router.carriedFate(button))
        router.settleCarried(button)
        available = true
        assertEquals(CarriedFate.None, router.carriedFate(button))
        router.settleCarried(button)

        assertEquals(0, runs)
    }

    /**
     * A carried command whose waiting owner leaves lapses, unless a replacement owner arrives in the same frame, so a
     * Chats screen composed again later never runs the old press.
     */
    @Test
    fun carriedCommandLapsesWhenItsLastOwnerLeaves() {
        var runs = 0
        val checking = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { false }, { true }) {}
        val ready = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) { runs++ }
        val carryNewMessage = {
            val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
            router.dispatch(HardwareShortcutCommand.NewMessage)
            removeConversation()
            runAfterFrame()
        }

        var removeChecking = router.register(checking)
        carryNewMessage()
        removeChecking()
        val removeReady = router.register(ready)
        runAfterFrame()
        router.settleCarried(ready)
        assertEquals(1, runs)

        removeReady()
        removeChecking = router.register(checking)
        carryNewMessage()
        removeChecking()
        runAfterFrame()
        router.register(ready)
        assertEquals(CarriedFate.None, router.carriedFate(ready))
        router.settleCarried(ready)
        assertEquals(1, runs)
    }

    /**
     * With several owners, a carried command waits only while one of them can still take it, and it runs only
     * through the newest owner that can, whichever owner settles first.
     */
    @Test
    fun carriedCommandFollowsTheNewestOwnerAndLapsesWhenNoneRemainsHopeful() {
        val runs = mutableListOf<String>()
        val older = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) { runs += "older" }
        val newer = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) { runs += "newer" }
        val carryNewMessage = {
            val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
            router.dispatch(HardwareShortcutCommand.NewMessage)
            removeConversation()
            runAfterFrame()
        }
        val removeOlder = router.register(older)
        val removeNewer = router.register(newer)

        carryNewMessage()
        assertEquals(CarriedFate.Wait, router.carriedFate(older))
        router.settleCarried(older)
        router.settleCarried(newer)
        assertEquals(listOf("newer"), runs)
        removeOlder()
        removeNewer()

        var refusingAvailable = false
        val checking = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { false }, { true }) {}
        val refusing = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { refusingAvailable }) {}
        router.register(refusing)
        val removeChecking = router.register(checking)
        carryNewMessage()
        assertEquals(CarriedFate.Wait, router.carriedFate(refusing))
        removeChecking()
        assertEquals(CarriedFate.Lapse, router.carriedFate(refusing))
        router.settleCarried(refusing)
        refusingAvailable = true
        assertEquals(CarriedFate.None, router.carriedFate(refusing))
        assertEquals(listOf("newer"), runs)
    }

    /**
     * A surface that takes input while a carried command waits for its owner cancels it: a focusable sheet taking
     * window focus, or an in-window popup blocking. The owner becoming available afterwards runs nothing, and a fresh
     * press once input returns works as usual.
     */
    @Test
    fun carriedCommandIsCancelledWhenAModalTakesInput() {
        var available = false
        var runs = 0
        val button =
            HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { available }, { !available }) { runs++ }
        router.register(button)
        val carryNewMessage = {
            val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
            router.dispatch(HardwareShortcutCommand.NewMessage)
            removeConversation()
            runAfterFrame()
        }

        carryNewMessage()
        assertEquals(CarriedFate.Wait, router.carriedFate(button))
        router.onWindowFocusChanged(hasFocus = false)
        available = true
        assertEquals(CarriedFate.None, router.carriedFate(button))
        router.onWindowFocusChanged(hasFocus = true)
        router.settleCarried(button)
        assertEquals(0, runs)

        available = false
        carryNewMessage()
        val release = router.block()
        available = true
        release()
        assertEquals(CarriedFate.None, router.carriedFate(button))
        router.settleCarried(button)
        assertEquals(0, runs)

        assertTrue(router.dispatch(HardwareShortcutCommand.NewMessage))
        assertEquals(1, runs)
    }

    /** A carried command's window starts when Chats is uncovered, not when the key was pressed. */
    @Test
    fun carriedWindowStartsWhenChatsIsUncovered() {
        var runs = 0
        val chatsSearch = HardwareShortcutHandler(HardwareShortcutCommand.GlobalSearch, { true }) { runs++ }
        router.register(chatsSearch)
        val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})

        router.dispatch(HardwareShortcutCommand.GlobalSearch)
        now += CHATS_RETURN_COMMAND_WINDOW_MS * 2
        removeConversation()
        now += CHATS_RETURN_COMMAND_WINDOW_MS
        router.settleCarried(chatsSearch)

        assertEquals(1, runs)
    }

    /** A carried command waits while its owner is still checking, runs once it can, and lapses after its window. */
    @Test
    fun carriedCommandsWaitForChatsAndLapseAfterTheirWindow() {
        var available = false
        var runs = 0
        // The owner is still checking until it becomes available, like New Message during its relay check.
        val chatsNewMessage =
            HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { available }, { !available }) { runs++ }
        router.register(chatsNewMessage)
        val carryNewMessage = {
            val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
            router.dispatch(HardwareShortcutCommand.NewMessage)
            removeConversation()
        }

        carryNewMessage()
        router.settleCarried(chatsNewMessage)
        assertEquals(0, runs)
        available = true
        now += CHATS_RETURN_COMMAND_WINDOW_MS
        router.settleCarried(chatsNewMessage)
        assertEquals(1, runs)

        carryNewMessage()
        now += CHATS_RETURN_COMMAND_WINDOW_MS + 1
        router.settleCarried(chatsNewMessage)
        router.settleCarried(chatsNewMessage)
        assertEquals(1, runs)
    }

    /** Side by side, Chats is already on screen, so a conversation never leaves to run a Chats command. */
    @Test
    fun splitPanesNeverLeaveTheConversation() {
        var exits = 0
        router.registerChatsReturn(ChatsReturnRoute({ true }) { exits++ })
        router.attachPanes(split = { true }, conversationOwnsKeys = { true })

        assertFalse(router.dispatch(HardwareShortcutCommand.GlobalSearch))
        assertEquals(0, exits)
    }

    /** A detached frame stops reporting panes, but a stale detach cannot clear a newer frame's report. */
    @Test
    fun staleFrameDetachKeepsTheNewerFrame() {
        registerAll()
        val detachOld = router.attachPanes(split = { true }, conversationOwnsKeys = { false })
        router.attachPanes(split = { true }, conversationOwnsKeys = { false })
        detachOld()

        assertFalse(router.dispatch(HardwareShortcutCommand.ConversationSearch))
    }

    /** The helper lists only available commands, with the on-screen controls' localized names and keys. */
    @Test
    fun helperListsAvailableCommandsWithLocalizedNames() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        router.register(HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) {})
        router.register(HardwareShortcutHandler(HardwareShortcutCommand.ConversationSearch, { true }) {})

        val group = requireNotNull(hardwareShortcutGroup(context, router.availableCommands()))
        val shortcuts = group.items.map { Triple(it.label.toString(), it.keycode, it.modifiers) }

        assertEquals(context.getString(R.string.chats), group.label.toString())
        assertEquals(
            listOf(
                Triple(context.getString(R.string.header_shortcut_search), KeyEvent.KEYCODE_F, CTRL_SHIFT_HELPER),
                Triple(context.getString(R.string.new_message), KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
            ),
            shortcuts,
        )
        assertNull(hardwareShortcutGroup(context, emptyList()))
    }

    /** Runs the work the router deferred until after the current frame. */
    private fun runAfterFrame() {
        val pending = afterFrame.toList()
        afterFrame.clear()
        pending.forEach { it() }
    }

    /** Registers an always-available owner for every command and returns how often each one ran. */
    private fun registerAll(): MutableMap<HardwareShortcutCommand, Int> {
        val runs = HardwareShortcutCommand.entries.associateWith { 0 }.toMutableMap()
        HardwareShortcutCommand.entries.forEach { command ->
            router.register(HardwareShortcutHandler(command, { true }) { runs[command] = runs.getValue(command) + 1 })
        }
        return runs
    }

    /** Maps [keyCode] with [meta] the way the router reads a key event. */
    private fun command(
        keyCode: Int,
        meta: Int,
    ) = hardwareShortcutCommandFor(keyCode, meta)

    private companion object {
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        const val CTRL_SHIFT_HELPER = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON

        /** A hardware key-down for [keyCode] with [meta], the [repeat]th auto-repeat when above zero. */
        fun down(
            keyCode: Int,
            meta: Int,
            repeat: Int = 0,
        ) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, repeat, meta)

        /** The hardware key-up for [keyCode] with [meta]. */
        fun up(
            keyCode: Int,
            meta: Int,
        ) = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, meta)
    }
}
