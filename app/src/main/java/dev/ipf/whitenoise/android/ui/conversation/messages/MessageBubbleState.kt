package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.ipf.marmotkit.ContentReportFfi
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageReportRegistry
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.MarkdownLinkLayoutRegistry
import dev.ipf.whitenoise.android.ui.MarkdownLinkTextLayoutReporter
import dev.ipf.whitenoise.android.ui.SelectableTextLayoutReporter
import kotlinx.coroutines.Job

// MessageBubble groups its per-message state by reset key instead of remembering each value separately (#3150).
// Every separately remembered value added about 100 bytecode units to the bubble, and at 16,383 units
// (UINT16_MAX / 4) or more ART refuses to compile a method at all, so each row composed in the interpreter.

/** One message's surface state, with its owed group report kept app-wide under the account, group and message. */
@Composable
internal fun rememberMessageBubbleSurfaces(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    item: TimelineMessage,
): MessageBubbleSurfaceState {
    val surfaces =
        remember(controller, item.presentationId) {
            val chat = "${controller.conversationAccountRef}|${controller.group.groupIdHex}"
            val reportKey = "$chat|${item.record.messageIdHex}"
            MessageBubbleSurfaceState(appState.runtimeMirrors.messageReports, reportKey)
        }
    CloseReportOnDispose(surfaces)
    return surfaces
}

/**
 * Closes [surfaces]' report sheet when the bubble leaves composition, as on rotation or scrolling away. A report
 * still sending then treats its own sheet as gone and shows a late failure as a notice, instead of writing it into a
 * sheet nobody can see.
 */
@Suppress("FunctionNaming")
@Composable
internal fun CloseReportOnDispose(surfaces: MessageBubbleSurfaceState) {
    DisposableEffect(surfaces) {
        onDispose { if (surfaces.reportSheetOpen) surfaces.closeReport() }
    }
}

/**
 * Sheet, dialog and in-flight flags for one message, reset together when the message id changes. A report still
 * sending, and the group's answer once the group took it, live in [reports] under [reportKey], which outlives this
 * bubble, so scrolling away or recreating the screen cannot make the group receive the same report twice.
 */
@Stable
internal class MessageBubbleSurfaceState(
    private val reports: MessageReportRegistry,
    private val reportKey: String,
) {
    var emojiPickerOpen by mutableStateOf(false)
    var bodyExceedsCollapseLimit by mutableStateOf(false)

    var infoSheetOpen by mutableStateOf(false)
    var forwardSheetOpen by mutableStateOf(false)

    /** The payload captured when the forward sheet opened, so a pending source changing state cannot rewrite it. */
    var forwardSnapshot by mutableStateOf<ForwardMessagePayload?>(null)
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
    var reportInFlight: Boolean
        get() = reports.inFlight[reportKey] == true
        set(value) {
            if (value) reports.inFlight[reportKey] = true else reports.inFlight.remove(reportKey)
        }

    /** Bumped whenever the report sheet closes, so a late answer can tell whether its own sheet is still open. */
    var reportSheetGeneration by mutableIntStateOf(0)

    /** Whether a group report also sends the White Noise team a copy, on unless the reader turns it off. */
    var reportToTeam by mutableStateOf(true)

    /** The group's answer once it has the report, kept until the report finishes so the group never gets it twice. */
    var reportGroupResult: Int?
        get() = reports.groupResults[reportKey]
        set(value) {
            if (value == null) reports.groupResults.remove(reportKey) else reports.groupResults[reportKey] = value
        }

    /** Whether the open sheet is retrying only a failed team copy, with the group's report already sent. */
    var reportCopyPending by mutableStateOf(false)
    var reportError by mutableStateOf<Int?>(null)
    var messageReports by mutableStateOf<List<ContentReportFfi>?>(null)
    var reportsRevision by mutableIntStateOf(0)

    /** Closes the report sheet. A group report already sent is remembered, so reopening offers only the team copy. */
    fun closeReport() {
        reportSheetOpen = false
        reportSheetGeneration += 1
        reportToTeam = true
        reportCopyPending = false
        reportError = null
    }

    /** Closes the report sheet once a report is complete, so the next report starts fresh. */
    fun finishReport() {
        closeReport()
        reportGroupResult = null
    }

    /** Puts an emoji picked for configure [slot] in the draft, replacing that slot or appending the next (#3279). */
    fun pickConfigureReaction(
        slot: Int,
        emoji: String,
    ) {
        configureReactionsDraft = RecentEmojiList.withQuickChoiceAt(configureReactionsDraft, slot, emoji)
    }
}

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
    val markdownLinkLayouts = MarkdownLinkLayoutRegistry(mutableStateMapOf())
    val pendingLongPressLinkDestination = arrayOfNulls<String>(1)
    val plainTextLayoutKey = "plain"
    val plainTextLayoutTracker = SelectableTextLayoutTracker()
    var textSelectionSeeded by mutableStateOf(false)
    var textSelectionBoundsInWindow by mutableStateOf<Rect?>(null)

    /** Keeps the latest layout of each rendered link leaf, and drops it when the leaf reports no layout. */
    val markdownLinkLayoutReporter: MarkdownLinkTextLayoutReporter = markdownLinkLayouts.report

    /** Keeps the latest layout of each selectable text leaf for selection seeding and read-aloud hit tests. */
    val selectableTextLayoutReporter: SelectableTextLayoutReporter = { key, layoutResult, coordinates ->
        selectableTextLayouts.update(key, layoutResult, coordinates)
    }
}
