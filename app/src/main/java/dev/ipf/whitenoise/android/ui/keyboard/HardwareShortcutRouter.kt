package dev.ipf.whitenoise.android.ui.keyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppLockController

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

/**
 * How long a command carried from a compact conversation may wait once Chats is uncovered, for example for the
 * New Message button's relay check. A command Chats cannot take by then lapses instead of firing later.
 */
internal const val CHATS_RETURN_COMMAND_WINDOW_MS = 1_000L

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

/** A router whose commands wait while [appLock] shows its screen or is still deciding whether to show it. */
internal fun appLockShortcutRouter(appLock: () -> AppLockController): HardwareShortcutRouter =
    HardwareShortcutRouter(inputBlocked = { appLock().let { it.screenVisible || it.evaluationPending } })

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
 * always reflects the owner's latest composition. [waiting] marks an owner that is not available yet but is still
 * finding out, such as New Message during its relay check, so a carried command waits for it instead of lapsing.
 */
internal class HardwareShortcutHandler(
    val command: HardwareShortcutCommand,
    val available: () -> Boolean,
    val waiting: () -> Boolean = { false },
    val invoke: () -> Unit,
)

/** What an owner does with a command carried from a compact conversation. */
internal enum class CarriedFate {
    /** Nothing is carried for this owner's command. */
    None,

    /** Chats is still covered or blocked, or an owner is still finding out whether it can run the command. */
    Wait,

    /** This owner runs the command now. */
    Run,

    /** Chats is uncovered and no owner can run the command or is finding out, so it lapses. */
    Lapse,
}

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
 * Dialogs, sheets and focusable menus are separate windows, so their keys never reach the activity. [inputBlocked]
 * (App Lock) and [block] (nonfocusable popups and in-window flows) cover the remaining surfaces that own input.
 * The state a carried command waits on is snapshot state, so its new owner can observe when to take it.
 */
internal class HardwareShortcutRouter(
    private val inputBlocked: () -> Boolean = { false },
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
    private val afterFrame: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) {
    private val handlers = mutableListOf<HardwareShortcutHandler>()
    private val chatsReturns = mutableStateListOf<ChatsReturnRoute>()
    private var blockers by mutableIntStateOf(0)
    private var chatsBlockers by mutableIntStateOf(0)
    private var carried by mutableStateOf<CarriedCommand?>(null)
    private var heldCommand: HardwareShortcutCommand? = null
    private var splitPanes: () -> Boolean = { false }
    private var conversationPaneOwnsKeys: () -> Boolean = { false }
    private val blocked: Boolean get() = blockers > 0 || inputBlocked()

    // A compact conversation (its return route registered, usable or not) covers Chats, even while a retained
    // Chats screen is still composed for a route transition. An in-place Chats flow covers it too.
    private val chatsCovered: Boolean get() = chatsBlockers > 0 || (!splitPanes() && chatsReturns.isNotEmpty())

    /** Adds [handler] as the newest owner of its command and returns the call that removes it. */
    fun register(handler: HardwareShortcutHandler): () -> Unit {
        handlers += handler
        return { handlers.remove(handler) }
    }

    /**
     * Adds [route] as the newest way back to Chats and returns the call that removes it. Once the last route goes,
     * Chats is uncovered and a carried command's window restarts, so an owner still checking, such as New Message's
     * relay check, can take it. If Chats has no owner for it once the current frame is applied (its control is
     * hidden), the command lapses, so a control that appears later never runs it.
     */
    fun registerChatsReturn(route: ChatsReturnRoute): () -> Unit {
        chatsReturns += route
        return {
            chatsReturns.remove(route)
            val waiting = carried?.takeIf { chatsReturns.isEmpty() }
            val uncovered = waiting?.let { CarriedCommand(it.command, uptimeMillis()) }
            if (uncovered != null) {
                carried = uncovered
                // Deferred, so an owner composed in the same frame, as when the route change has no motion, counts.
                afterFrame {
                    if (carried === uncovered && handlers.none { it.command == uncovered.command }) carried = null
                }
            }
        }
    }

    /**
     * Makes every command, or with [chatsOnly] just the Chats commands, unavailable until the returned call runs.
     * Running the returned call again has no effect.
     */
    fun block(chatsOnly: Boolean = false): () -> Unit {
        if (chatsOnly) chatsBlockers += 1 else blockers += 1
        var released = false
        return {
            if (!released) {
                released = true
                if (chatsOnly) chatsBlockers -= 1 else blockers -= 1
            }
        }
    }

    /**
     * Lets the two-pane frame report whether panes sit side by side and whether the conversation pane owns the
     * keys there. Returns the call that detaches this report, and a stale detach leaves a newer frame's report alone.
     */
    fun attachPanes(
        split: () -> Boolean,
        conversationOwnsKeys: () -> Boolean,
    ): () -> Unit {
        splitPanes = split
        conversationPaneOwnsKeys = conversationOwnsKeys
        return {
            if (splitPanes === split) {
                splitPanes = { false }
                conversationPaneOwnsKeys = { false }
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
        val owner = if (blocked) null else handlers.lastOrNull { it.command == command && canRun(it) }
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
     * What [handler] should do with a command carried from a compact conversation. It reads only snapshot state
     * and owners' availability, so an owner can observe it and act through [settleCarried] when it changes.
     */
    fun carriedFate(handler: HardwareShortcutHandler): CarriedFate {
        if (carried?.command != handler.command) return CarriedFate.None
        val owners = (handlers + handler).filter { it.command == handler.command }
        return when {
            blocked -> CarriedFate.Wait
            canRun(handler) -> CarriedFate.Run
            handler.command.runsFromChats && chatsCovered -> CarriedFate.Wait
            owners.any { it.waiting() || canRun(it) } -> CarriedFate.Wait
            else -> CarriedFate.Lapse
        }
    }

    /**
     * Applies [carriedFate] for [handler]: runs the carried command through it, or drops a command no owner can take.
     * A carried command runs at most once and lapses after [CHATS_RETURN_COMMAND_WINDOW_MS] of uncovered Chats.
     */
    fun settleCarried(handler: HardwareShortcutHandler) {
        val pending = carried ?: return
        when (carriedFate(handler)) {
            CarriedFate.Run -> {
                carried = null
                if (uptimeMillis() - pending.atMillis <= CHATS_RETURN_COMMAND_WINDOW_MS) handler.invoke()
            }
            CarriedFate.Lapse -> carried = null
            CarriedFate.None, CarriedFate.Wait -> Unit
        }
    }

    /** The commands a key press would run right now, in helper order. */
    fun availableCommands(): List<HardwareShortcutCommand> =
        if (blocked) {
            emptyList()
        } else {
            HardwareShortcutCommand.entries.filter { command ->
                handlers.any { it.command == command && canRun(it) } || chatsReturnFor(command) != null
            }
        }

    /**
     * Whether [handler] may run now: available, and on the side that owns its command. In-chat search needs the
     * conversation pane when panes sit side by side, and Chats commands need Chats uncovered.
     */
    private fun canRun(handler: HardwareShortcutHandler): Boolean {
        val paneAllows =
            when {
                handler.command == HardwareShortcutCommand.ConversationSearch ->
                    !splitPanes() || conversationPaneOwnsKeys()
                handler.command.runsFromChats -> !chatsCovered
                else -> true
            }
        return paneAllows && handler.available()
    }

    /** The compact conversation route that can carry [command] to Chats, or null when none can. */
    private fun chatsReturnFor(command: HardwareShortcutCommand): ChatsReturnRoute? =
        if (command.runsFromChats && !splitPanes() && chatsBlockers == 0) {
            chatsReturns.lastOrNull { it.available() }
        } else {
            null
        }
}
