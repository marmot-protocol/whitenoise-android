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
    private val router = HardwareShortcutRouter(inputBlocked = { appLocked }, uptimeMillis = { now })

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

    /** App Lock and in-window blockers make every command unavailable; a released blocker cannot release twice. */
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

    /** Side by side, in-chat search belongs to the conversation pane; a compact conversation always owns it. */
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
        assertFalse(router.canTakeCarried(chatsSearch))
        removeConversation()
        assertFalse(router.canTakeCarried(HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { true }) {}))
        assertTrue(router.canTakeCarried(chatsSearch))
        router.claimCarried(chatsSearch)
        router.claimCarried(chatsSearch)

        assertEquals(1, searches)
        assertEquals(1, exits)
        assertFalse(HardwareShortcutCommand.ConversationSearch.runsFromChats)
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

    /** A carried command waits while Chats is unavailable, runs once it is, and lapses once its window has passed. */
    @Test
    fun carriedCommandsWaitForChatsAndLapseAfterTheirWindow() {
        var available = false
        var runs = 0
        val chatsNewMessage = HardwareShortcutHandler(HardwareShortcutCommand.NewMessage, { available }) { runs++ }
        val carryNewMessage = {
            val removeConversation = router.registerChatsReturn(ChatsReturnRoute({ true }) {})
            router.dispatch(HardwareShortcutCommand.NewMessage)
            removeConversation()
        }

        carryNewMessage()
        router.claimCarried(chatsNewMessage)
        assertEquals(0, runs)
        available = true
        now += CHATS_RETURN_COMMAND_WINDOW_MS
        router.claimCarried(chatsNewMessage)
        assertEquals(1, runs)

        carryNewMessage()
        now += CHATS_RETURN_COMMAND_WINDOW_MS + 1
        router.claimCarried(chatsNewMessage)
        router.claimCarried(chatsNewMessage)
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
