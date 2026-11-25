package dev.ipf.whitenoise.android.ui.keyboard

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

/**
 * The hardware keyboard commands the messaging interface publishes (#4005). Each one only routes to an action
 * that already has an on-screen entry point: the Chats search field, the New Message button and in-chat search.
 * Their helper labels reuse those controls' localized names.
 */
internal enum class HardwareShortcutCommand(
    val keyCode: Int,
    val shift: Boolean,
    @param:StringRes val labelRes: Int,
) {
    GlobalSearch(KeyEvent.KEYCODE_F, shift = false, R.string.chat_list_search_open),
    ConversationSearch(KeyEvent.KEYCODE_F, shift = true, R.string.header_shortcut_search),
    NewMessage(KeyEvent.KEYCODE_N, shift = false, R.string.new_message),
    ;

    /** The `KeyEvent.META_*` modifiers the Keyboard Shortcuts Helper shows beside the key. */
    val modifiers: Int get() = KeyEvent.META_CTRL_ON or (if (shift) KeyEvent.META_SHIFT_ON else 0)

    /** Whether a compact conversation may leave for Chats so the Chats screen can run this command. */
    val runsFromChats: Boolean get() = this != ConversationSearch
}

private const val OTHER_COMMAND_MODIFIERS =
    KeyEvent.META_ALT_ON or KeyEvent.META_META_ON or KeyEvent.META_SYM_ON or KeyEvent.META_FUNCTION_ON

/** How long a command carried from a compact conversation waits for Chats to compose and take it. */
internal const val CHATS_RETURN_COMMAND_WINDOW_MS = 3_000L

/**
 * Maps a key and its modifier state to a command. Only Ctrl with the exact Shift state qualifies, so Alt, Meta
 * and the editing shortcuts (Ctrl+A, C, V, X, Z) never match.
 */
internal fun hardwareShortcutCommandFor(
    keyCode: Int,
    metaState: Int,
): HardwareShortcutCommand? {
    val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
    if (!ctrl || metaState and OTHER_COMMAND_MODIFIERS != 0) return null
    val shift = metaState and KeyEvent.META_SHIFT_ON != 0
    return HardwareShortcutCommand.entries.firstOrNull { it.keyCode == keyCode && it.shift == shift }
}

/** The Keyboard Shortcuts Helper group listing [commands], or null when none is available right now. */
internal fun hardwareShortcutGroup(
    context: Context,
    commands: List<HardwareShortcutCommand>,
): KeyboardShortcutGroup? =
    commands.takeIf { it.isNotEmpty() }?.let { available ->
        KeyboardShortcutGroup(
            context.getString(R.string.chats),
            available.map { KeyboardShortcutInfo(context.getString(it.labelRes), it.keyCode, it.modifiers) },
        )
    }

/**
 * One registered owner of a command. [available] is read when a key arrives or the shortcut helper opens, so it
 * always reflects the owner's latest composition.
 */
internal class HardwareShortcutHandler(
    val command: HardwareShortcutCommand,
    val available: () -> Boolean,
    val invoke: () -> Unit,
)

/** A compact conversation's way back to Chats, used to carry Chats commands out of it. */
internal class ChatsReturnRoute(
    val available: () -> Boolean,
    val exit: () -> Unit,
)

private class CarriedCommand(
    val command: HardwareShortcutCommand,
    val atMillis: Long,
)

/**
 * Activity-owned dispatcher for the messaging shortcuts. The activity hands every key event to [handle] before
 * its views, and composition registers which screen owns each command. Every call runs on the main thread.
 *
 * Dialogs, sheets and menus are separate windows, so their keys never reach the activity. [inputBlocked] (App
 * Lock) and [block] (in-window flows) cover the remaining surfaces that own input.
 */
internal class HardwareShortcutRouter(
    private val inputBlocked: () -> Boolean = { false },
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private val handlers = mutableListOf<HardwareShortcutHandler>()
    private val chatsReturns = mutableListOf<ChatsReturnRoute>()
    private var blockers = 0
    private var carried: CarriedCommand? = null
    private var heldCommand: HardwareShortcutCommand? = null
    private var splitPanes: () -> Boolean = { false }
    private var listPaneOwnsKeys: () -> Boolean = { false }
    private val blocked: Boolean get() = blockers > 0 || inputBlocked()

    /** Adds [handler] as the newest owner of its command and returns the call that removes it. */
    fun register(handler: HardwareShortcutHandler): () -> Unit {
        handlers += handler
        return { handlers.remove(handler) }
    }

    /** Adds [route] as the newest way back to Chats and returns the call that removes it. */
    fun registerChatsReturn(route: ChatsReturnRoute): () -> Unit {
        chatsReturns += route
        return { chatsReturns.remove(route) }
    }

    /** Makes every command unavailable until the returned call runs; running it again has no effect. */
    fun block(): () -> Unit {
        blockers += 1
        var released = false
        return {
            if (!released) {
                released = true
                blockers -= 1
            }
        }
    }

    /** Lets the two-pane frame report whether panes sit side by side and whether the list pane owns the keys. */
    fun attachPanes(
        split: () -> Boolean,
        listOwnsKeys: () -> Boolean,
    ): () -> Unit {
        splitPanes = split
        listPaneOwnsKeys = listOwnsKeys
        return {
            if (splitPanes === split) {
                splitPanes = { false }
                listPaneOwnsKeys = { false }
            }
        }
    }

    /**
     * Runs the command [event] names and reports whether the event is consumed. A command runs on its first
     * key-down only, auto-repeats and the matching key-up are consumed without running it again, and every
     * other key passes through untouched.
     */
    fun handle(event: KeyEvent): Boolean {
        val command = hardwareShortcutCommandFor(event.keyCode, event.metaState) ?: return false
        return when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                val handled = dispatch(command)
                heldCommand = command.takeIf { handled }
                handled
            }
            event.action == KeyEvent.ACTION_DOWN -> heldCommand == command
            event.action == KeyEvent.ACTION_UP && heldCommand == command -> {
                heldCommand = null
                true
            }
            else -> false
        }
    }

    /** Runs [command] through its newest available owner, or carries it to Chats from a compact conversation. */
    fun dispatch(command: HardwareShortcutCommand): Boolean {
        carried = null
        val owner = if (blocked) null else ownerOf(command)
        val chatsReturn = if (blocked || owner != null) null else chatsReturnFor(command)
        when {
            owner != null -> owner.invoke()
            chatsReturn != null -> {
                carried = CarriedCommand(command, uptimeMillis())
                chatsReturn.exit()
            }
        }
        return owner != null || chatsReturn != null
    }

    /**
     * Runs a command carried from a compact conversation once [handler] can take it. A carried command runs at
     * most once and lapses after [CHATS_RETURN_COMMAND_WINDOW_MS], so a late Chats screen never acts on it.
     */
    fun claimCarried(handler: HardwareShortcutHandler) {
        val pending = carried?.takeIf { it.command == handler.command } ?: return
        val expired = uptimeMillis() - pending.atMillis > CHATS_RETURN_COMMAND_WINDOW_MS
        if (expired) {
            carried = null
        } else if (!blocked && handler.available() && paneAllows(handler.command)) {
            carried = null
            handler.invoke()
        }
    }

    /** The commands a key press would run right now, in helper order. */
    fun availableCommands(): List<HardwareShortcutCommand> =
        if (blocked) {
            emptyList()
        } else {
            HardwareShortcutCommand.entries.filter { ownerOf(it) != null || chatsReturnFor(it) != null }
        }

    private fun paneAllows(command: HardwareShortcutCommand): Boolean =
        command != HardwareShortcutCommand.ConversationSearch || !(splitPanes() && listPaneOwnsKeys())

    private fun ownerOf(command: HardwareShortcutCommand): HardwareShortcutHandler? =
        if (paneAllows(command)) handlers.lastOrNull { it.command == command && it.available() } else null

    private fun chatsReturnFor(command: HardwareShortcutCommand): ChatsReturnRoute? =
        if (command.runsFromChats && !splitPanes()) chatsReturns.lastOrNull { it.available() } else null
}
