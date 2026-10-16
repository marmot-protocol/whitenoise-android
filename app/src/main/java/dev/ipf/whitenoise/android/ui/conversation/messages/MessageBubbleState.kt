package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.ui.MarkdownLinkTextLayout
import dev.ipf.whitenoise.android.ui.MarkdownLinkTextLayoutReporter
import dev.ipf.whitenoise.android.ui.SelectableTextLayoutReporter
import kotlinx.coroutines.Job

// MessageBubble groups its per-message state by reset key instead of remembering each value separately (#3150).
// Every separately remembered value added about 100 bytecode units to the bubble, and at 16,383 units
// (UINT16_MAX / 4) or more ART refuses to compile a method at all, so each row composed in the interpreter.

/** Sheet, dialog and in-flight flags for one message, reset together when the message id changes. */
@Stable
internal class MessageBubbleSurfaceState {
    var emojiPickerOpen by mutableStateOf(false)

    /**
     * A long body clips to a few lines with an inline Read More, and opening it routes through a full-screen
     * view rather than expanding in place, so the only state to track is whether that view is showing (#325).
     */
    var expandedFullView by mutableStateOf(false)

    /** Bumped on every Read more open, so a send accepted from an earlier reader cannot close a newer one (#3268). */
    var expandedFullViewSession by mutableIntStateOf(0)
        private set

    /** The reader session whose composer send was accepted and is waiting for [ReaderSendDismissal] to close it. */
    var readerSendAcceptedSession by mutableStateOf<Int?>(null)
        private set
    var infoSheetOpen by mutableStateOf(false)
    var forwardSheetOpen by mutableStateOf(false)
    var editHistoryOpen by mutableStateOf(false)
    var reactionSheetOpen by mutableStateOf(false)
    var customizeReactionsOpen by mutableStateOf(false)
    var configureReactionsDraft by mutableStateOf<List<String>>(emptyList())
    var configureReactionSlot by mutableStateOf<Int?>(null)
    var deleteDialogOpen by mutableStateOf(false)
    var deleteInFlight by mutableStateOf(false)
    var attachmentSaveInFlight by mutableStateOf(false)
    var messageShareInFlight by mutableStateOf(false)
    var messageShareJob by mutableStateOf<Job?>(null)
    var reportSheetOpen by mutableStateOf(false)
    var reportInFlight by mutableStateOf(false)
    var messageReports by mutableStateOf<List<ContentReportFfi>?>(null)
    var reportsRevision by mutableIntStateOf(0)

    /** Puts an emoji picked for configure [slot] in the draft, replacing that slot or appending the next (#3279). */
    fun pickConfigureReaction(
        slot: Int,
        emoji: String,
    ) {
        configureReactionsDraft = RecentEmojiList.withQuickChoiceAt(configureReactionsDraft, slot, emoji)
    }

    /** Opens the Read more reader as a new session. */
    fun openExpandedFullView() {
        expandedFullViewSession += 1
        readerSendAcceptedSession = null
        expandedFullView = true
    }

    /**
     * Marks a send accepted from the composer of reader [session] (#3268), only while that session is still the
     * one showing. [ReaderSendDismissal] closes it once the cleared composer has reached the keyboard.
     */
    fun finishExpandedReaderSend(session: Int) {
        if (expandedFullView && session == expandedFullViewSession) readerSendAcceptedSession = session
    }

    /** Closes reader [session] for its accepted send; returns false when a newer reader or a dismissal won. */
    fun closeReaderAfterAcceptedSend(session: Int): Boolean {
        if (readerSendAcceptedSession == session) readerSendAcceptedSession = null
        val current = expandedFullView && session == expandedFullViewSession
        if (current) expandedFullView = false
        return current
    }
}

/**
 * Closes the Read more reader after a send from its composer was accepted, then runs [onReaderSendAccepted] so
 * the conversation reveals the outgoing row (#3268). The close waits [READER_SEND_DISMISSAL_FRAMES] frames: the
 * accepted send has only just cleared the shared composer text, and disposing the reader's field in that same
 * frame lets the keyboard's unfinished composing word write the sent text back into the main composer.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ReaderSendDismissal(
    surfaces: MessageBubbleSurfaceState,
    onReaderSendAccepted: () -> Unit,
) {
    val session = surfaces.readerSendAcceptedSession ?: return
    val currentOnReaderSendAccepted by rememberUpdatedState(onReaderSendAccepted)
    LaunchedEffect(surfaces, session) {
        repeat(READER_SEND_DISMISSAL_FRAMES) { withFrameNanos { } }
        if (surfaces.closeReaderAfterAcceptedSend(session)) currentOnReaderSendAccepted()
    }
}

/** Frames the reader stays open after an accepted send, so the cleared field reaches the keyboard first. */
private const val READER_SEND_DISMISSAL_FRAMES = 2

/**
 * Press, menu-anchor and focused-media geometry for one rendered row, reset when its presentation id changes.
 *
 * The touch point and the selected message's window bounds are frozen when the menu opens: the point seeds partial
 * text selection, and the bounds keep the action surface visually attached to the selected bubble.
 */
@Stable
internal class MessageBubblePressState {
    var longPressWindowPosition by mutableStateOf<Offset?>(null)
    var longPressTtsStart by mutableStateOf<TtsLongPressStart?>(null)
    var selectionSeedVisibleOffset by mutableStateOf<Int?>(null)
    var actionMenuAnchorBounds by mutableStateOf<IntRect?>(null)
    var initiatingMenuHoldActive by mutableStateOf(false)
    var focusedMediaSize by mutableStateOf(IntSize.Zero)
    val rowCoordinates = arrayOfNulls<LayoutCoordinates>(1)
    val messageBoundsInWindow = arrayOfNulls<IntRect>(1)
}

/** Rendered text-layout registries and selection seeding for one message, reset when the message id changes. */
@Stable
internal class MessageBubbleTextLayouts {
    val selectableTextLayouts = SelectableTextLayoutRegistry()
    val markdownLinkLayouts = mutableStateMapOf<Any, MarkdownLinkTextLayout>()
    val pendingLongPressLinkDestination = arrayOfNulls<String>(1)
    val plainTextLayoutKey = "plain"
    val plainTextLayoutTracker = SelectableTextLayoutTracker()
    var textSelectionSeeded by mutableStateOf(false)
    var textSelectionBoundsInWindow by mutableStateOf<Rect?>(null)

    /** Keeps the latest layout of each rendered link leaf, and drops it when the leaf reports no layout. */
    val markdownLinkLayoutReporter: MarkdownLinkTextLayoutReporter = { key, text, layoutResult, coordinates ->
        if (layoutResult != null && coordinates != null) {
            markdownLinkLayouts[key] = MarkdownLinkTextLayout(text, layoutResult, coordinates)
        } else {
            markdownLinkLayouts.remove(key)
        }
    }

    /** Keeps the latest layout of each selectable text leaf for selection seeding and read-aloud hit tests. */
    val selectableTextLayoutReporter: SelectableTextLayoutReporter = { key, layoutResult, coordinates ->
        selectableTextLayouts.update(key, layoutResult, coordinates)
    }
}
