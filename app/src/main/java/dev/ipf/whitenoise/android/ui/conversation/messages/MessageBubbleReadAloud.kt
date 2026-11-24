package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsSeekResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableSource
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.state.ttsStartFailureMessage
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.markdownHasLinkAnnotationAt

/**
 * One bubble's read-aloud starts and seeks: Speak from the action menu or a text selection, Read code literally,
 * the two-finger swipe start, and the double tap that seeks an owned session or starts reading at the tapped text.
 *
 * An instance lasts as long as the bubble keeps its [appState] and [controller]. Every other value comes from
 * [latest], the bubble's most recent composition. Each entry point reads it once when it runs and uses that one set
 * of values throughout, including inside the mutation and history-session callbacks it hands on, as the local
 * functions it replaces did with the values they captured. [speakFromHere] and [seekSpeakAloudAt] end in
 * [startSpeakAloud], which reads [latest] again within the same main-thread call and therefore sees the same set.
 */
internal class MessageBubbleReadAloud(
    private val appState: WhiteNoiseAppState,
    private val controller: ConversationController,
    private val latest: State<Inputs>,
) {
    /**
     * The values one composition of the bubble computed for read-aloud. A plain class, compared by identity, so
     * updating it never compares the record's content tokens or the speakable projection.
     */
    internal class Inputs(
        val record: AppMessageRecordFfi,
        val deleted: Boolean,
        val selectionMode: Boolean,
        val textSelectionMode: Boolean,
        val canSpeakAloud: Boolean,
        val displayedBody: String,
        val ttsSpeakableSource: TtsSpeakableSource?,
        val speakableProjection: SpeakableTextProjection?,
        val ttsProjectionResolver: TtsHighlightProjectionResolver?,
        val textLayouts: MessageBubbleTextLayouts,
        val pressState: MessageBubblePressState,
        val messageTextSelectionState: SelectionState,
        val ttsLinkTapCoordinator: TtsLinkTapCoordinator,
        val onTtsSentenceSeek: (TtsState) -> Unit,
    )

    /** Captures display names and the conversation size alongside the rendered speech projection. */
    private suspend fun ttsEntry(entryRecord: AppMessageRecordFfi) =
        projectTtsSpeakableEntry(
            message = entryRecord,
            editedText = controller.editsByTarget[entryRecord.messageIdHex]?.latestText,
            senderDisplayName = appState.displayName(entryRecord.sender),
            conversationMemberCount = controller.memberCount.takeIf { controller.membersLoaded },
            parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
            mentionDisplayName = appState::mentionSpeechName,
        )

    // Speak aloud reads from this message onward: catch-up listening is the
    // point of the action, and Stop on the transport bar is one tap. The
    // session takes auto-read ownership so messages arriving while it speaks
    // continue the read. Falls back to just this bubble when its record has
    // left the loaded timeline. When text is selected or the action menu was
    // opened from a text selection, start at the containing visible sentence.
    // A text press retains its rendered hit; genuine gutter/edge presses retain
    // the deterministic message-top fallback without guessing a nearby glyph.

    private suspend fun Inputs.speakFromHereEntries(literalCode: Boolean): List<TtsSpeakableEntry> =
        ttsSpeakFromHereCandidates(timeline = controller.timeline, selected = record)
            .mapNotNull { entryRecord -> ttsEntry(entryRecord) }
            .mapIndexed { index, entry ->
                if (index == 0 && literalCode) {
                    entry.copy(speechMode = dev.ipf.whitenoise.android.audio.tts.speech.SpeechMode.LiteralCode)
                } else {
                    entry
                }
            }

    /** Starts from the selected sentence and preserves a specific start-gate explanation. */
    fun startSpeakAloud(
        startRenderedHit: RenderedTextHit? = null,
        fallbackVisibleText: String? = null,
        fallbackVisibleOffset: Int? = null,
        literalCode: Boolean = false,
        press: TtsLongPressStart? = null,
    ) {
        with(latest.value) {
            if (deleted) return
            val owner =
                press ?: captureTtsLongPressStart(startRenderedHit, ttsSpeakableSource?.text, controller, appState)
            val canCommit = captureTtsLongPressGuard(owner, record, controller, appState)
            if (!canCommit()) return
            val projectionStillCurrent = appState.ttsController.speechStartProjectionGuard()
            val locale = java.util.Locale.getDefault()
            appState.launchMutation {
                val entries = speakFromHereEntries(literalCode)
                if (!canCommit() || !projectionStillCurrent()) {
                    return@launchMutation
                }
                if (entries.firstOrNull()?.messageIdHex != record.messageIdHex) {
                    appState.present(R.string.tts_bar_error)
                    return@launchMutation
                }
                val fallbackSentenceIndex =
                    if (fallbackVisibleText == null || fallbackVisibleOffset == null) {
                        0
                    } else {
                        speakableSentenceIndexAtVisibleOffset(
                            visibleText = fallbackVisibleText,
                            speakableText = entries.first().text,
                            visibleOffset = fallbackVisibleOffset,
                            locale = locale,
                        )
                    }
                if (!appState.confirmTtsPlaybackTrust(canCommit)) return@launchMutation
                val started =
                    appState.speakAloudAutoRead(
                        controller.group.groupIdHex,
                        entries,
                        locale,
                        startSentenceIndex = fallbackSentenceIndex,
                        startRenderedHit =
                            startRenderedHit?.let {
                                preparedHitFromRenderedHit(entries.first(), it)
                            },
                        backgroundPreparation = true,
                        canCommit = canCommit,
                    )
                if (!started && canCommit()) appState.present(appState.ttsStartFailureMessage())
            }
        }
    }

    /** Starts read-aloud at the selected sentence, or at the long-pressed text when nothing is selected. */
    fun speakFromHere() {
        with(latest.value) {
            val layouts = textLayouts.selectableTextLayouts.snapshot()
            val selectionActive = textSelectionMode && messageTextSelectionState.selectedTexts.isNotEmpty()
            if (!selectionActive) {
                startSpeakAloud(pressState.longPressTtsStart?.renderedHit, press = pressState.longPressTtsStart)
                return
            }
            val visibleText = concatenatedVisibleText(layouts).ifBlank { displayedBody }
            val visibleOffset =
                if (record.contentTokens.truncated) {
                    null
                } else {
                    visibleOffsetFromSelection(
                        layouts = layouts,
                        selectedTexts = messageTextSelectionState.selectedTexts,
                        preferredVisibleOffset = pressState.selectionSeedVisibleOffset,
                    )
                }
            val renderedHit =
                if (record.contentTokens.truncated) {
                    null
                } else {
                    renderedTextHitFromSelection(
                        layouts = layouts,
                        selectedTexts = messageTextSelectionState.selectedTexts,
                        preferredVisibleOffset = pressState.selectionSeedVisibleOffset,
                    )
                }
            startSpeakAloud(
                startRenderedHit = renderedHit,
                fallbackVisibleText = visibleText,
                fallbackVisibleOffset = visibleOffset,
            )
        }
    }

    private fun Inputs.seekActiveSentence(sentenceIndex: Int) {
        when (
            appState.ttsController.seekToSentence(
                record.messageIdHex,
                sentenceIndex,
                speakableProjection?.projectionId,
            )
        ) {
            TtsSeekResult.Repositioned,
            TtsSeekResult.RepositionedAcrossMessages,
            -> {
                appState.ttsHistorySession.cancelPendingSeek()
                ttsLinkTapCoordinator.cancelPendingActivation()
                onTtsSentenceSeek(appState.ttsController.state.value)
                return
            }

            TtsSeekResult.MessageNotInWindow -> {
                if (appState.ttsHistorySession.requestSentenceSeek(
                        record.messageIdHex,
                        record.recordedAt,
                        sentenceIndex,
                        speakableProjection?.projectionId.orEmpty(),
                    )
                ) {
                    ttsLinkTapCoordinator.cancelPendingActivation()
                }
            }
            TtsSeekResult.SentenceOutOfRange,
            TtsSeekResult.SessionInactive,
            -> Unit
        }
    }

    /** Seeks read-aloud to a tapped sentence through the history session when this message is not projected. */
    private fun Inputs.seekRenderedSentenceOutsideCurrentProjection(
        hit: RenderedTextHit,
        allowOmittedLinkNeighbor: Boolean,
    ) {
        val account = controller.boundAccountRef
        val owner =
            appState.ttsHistorySession.conversationSource.value?.takeIf {
                it.accountRef == account && it.groupIdHex == controller.group.groupIdHex
            } ?: return
        val runtimeGeneration = appState.runtimeGeneration
        val expectedText = ttsSpeakableSource?.text
        val canCommit = {
            val live = controller.timeline.firstOrNull { it.record.messageIdHex == record.messageIdHex }
            val source =
                dev.ipf.whitenoise.android.audio.tts.resolveTtsSpeakableSource(
                    live?.record ?: record,
                    controller.editsByTarget[record.messageIdHex]?.latestText,
                )
            val sameOwner = appState.activeAccountRef == account && appState.runtimeGeneration == runtimeGeneration
            val unavailable =
                record.messageIdHex in controller.deletedMessageIds ||
                    live?.projected?.deleted == true ||
                    live?.projected?.invalidationStatus != null
            sameOwner && !unavailable && source?.text == expectedText
        }
        if (!canCommit()) return
        val accepted =
            appState.ttsHistorySession.requestRenderedSentenceSeek(
                record.messageIdHex,
                record.recordedAt,
                expectedSource = owner,
                resolveTarget = {
                    if (!canCommit()) return@requestRenderedSentenceSeek null
                    val entry = ttsEntry(record) ?: return@requestRenderedSentenceSeek null
                    val projection = entry.speakableProjection()
                    dev.ipf.whitenoise.android.audio.tts.TtsRenderedSeekRequest(
                        entry = entry,
                        sentenceIndex = { prepared ->
                            TtsHighlightProjectionResolver(projection, prepared)
                                .sentenceIndexAtRenderedOffset(hit, allowOmittedLinkNeighbor)
                        },
                        canCommit = canCommit,
                    )
                },
                onCommitted = { onTtsSentenceSeek(appState.ttsController.state.value) },
            )
        if (accepted) ttsLinkTapCoordinator.cancelPendingActivation()
    }

    /** Whether this conversation's read-aloud session is speaking, paused or preparing right now. */
    fun ownsActiveReadAloud(): Boolean {
        val activeState = appState.ttsController.state.value
        val active =
            activeState is TtsState.Speaking || activeState is TtsState.Paused || activeState is TtsState.Preparing
        return active && appState.ownsTtsAutoReadSession(controller.group.groupIdHex)
    }

    /** Seeks owned read-aloud to the double-tapped sentence, or starts reading there unless the tap hit a link. */
    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
    fun seekSpeakAloudAt(pressInWindow: Offset) {
        with(latest.value) {
            if (deleted || selectionMode || textSelectionMode || !canSpeakAloud) return
            val layouts = textLayouts.selectableTextLayouts.snapshot()
            if (ownsActiveReadAloud()) {
                val hit = renderedTextHitAtWindowPosition(layouts, pressInWindow)
                val sentenceIndex =
                    hit?.let {
                        ttsProjectionResolver?.sentenceIndexAtRenderedOffset(
                            it,
                            // Retain the established link double-tap coordination during playback.
                            allowOmittedLinkNeighbor =
                                markdownHasLinkAnnotationAt(
                                    textLayouts.markdownLinkLayouts.values,
                                    pressInWindow,
                                ),
                        )
                    }
                if (sentenceIndex != null) {
                    seekActiveSentence(sentenceIndex)
                } else if (ttsProjectionResolver == null && hit != null && !record.contentTokens.truncated) {
                    seekRenderedSentenceOutsideCurrentProjection(
                        hit,
                        markdownHasLinkAnnotationAt(textLayouts.markdownLinkLayouts.values, pressInWindow),
                    )
                }
                // An owned session must never be replaced merely because a hit is
                // ambiguous, outside the queue window, or denied audio focus. A
                // pending link remains armed and opens after its tap window.
                return
            }
            // Outside playback, links retain their immediate activation and do
            // not become a read-aloud gesture target.
            if (markdownHasLinkAnnotationAt(textLayouts.markdownLinkLayouts.values, pressInWindow)) return
            val renderedHit =
                if (record.contentTokens.truncated) {
                    null
                } else {
                    renderedTextHitAtWindowPosition(layouts, pressInWindow)
                } ?: return
            startSpeakAloud(renderedHit)
        }
    }
}

/**
 * Remembers [MessageBubbleReadAloud] for the bubble's [appState] and [controller], the only values the old local
 * functions captured that stay fixed for a mounted message. [inputs] is this composition's snapshot of everything
 * else and is only read when an entry point runs, so it adds no snapshot read to composition and a new projection,
 * selection or seek callback never needs a new instance.
 */
@Composable
internal fun rememberMessageBubbleReadAloud(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
    inputs: MessageBubbleReadAloud.Inputs,
): MessageBubbleReadAloud {
    val latest = rememberUpdatedState(inputs)
    return remember(appState, controller) {
        MessageBubbleReadAloud(
            appState = appState,
            controller = controller,
            latest = latest,
        )
    }
}
