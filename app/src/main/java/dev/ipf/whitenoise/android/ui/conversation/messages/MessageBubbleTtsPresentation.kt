package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableSource
import dev.ipf.whitenoise.android.audio.tts.resolveTtsSpeakableDocument
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.ui.SpeakableTextProjection
import dev.ipf.whitenoise.android.ui.TtsLeafHighlight
import dev.ipf.whitenoise.android.ui.TtsSentenceLayoutReporter
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsFollowTarget
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsSentenceLayoutReport
import dev.ipf.whitenoise.android.ui.conversation.ConversationTtsSentenceLayoutSink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What one bubble composition resolved for read-aloud: the speakable projection of its body, the highlight and
 * sentence-layout resolvers built on it, the queued presentation and progress its leaves render, and whether the
 * body expands for the spoken sentence.
 */
@Suppress("LongParameterList") // One field per value the bubble reads from the old inline block.
internal class MessageBubbleTtsPresentation(
    val speakableProjection: SpeakableTextProjection?,
    val ttsProjectionResolver: TtsHighlightProjectionResolver?,
    val queuedTtsPresentation: TtsSpeakableEntry?,
    val ttsLeafHighlightResolver: ((String, String) -> TtsLeafHighlight?)?,
    val ttsSentenceLayoutReporter: TtsSentenceLayoutReporter?,
    val effectiveTtsReadAloudProgress: TtsReadAloudProgress?,
    val ttsBodyExpanded: Boolean,
)

/**
 * Resolves the bubble's read-aloud presentation: parses or reuses the document the spoken text is projected from,
 * projects it, and derives the highlight, sentence-layout reporter and body expansion from that projection.
 *
 * Called by the bubble where this block used to sit, so its single effect keeps its place in the bubble's effect
 * order and every `remember` keeps its keys and scope. The inputs are the values the bubble computed above this
 * point; nothing here reads state the bubble did not already read in the same composition.
 *
 * It takes one explicit input per value the old inline block read from the bubble. The body moved unchanged from
 * the bubble, whose detekt baseline entry covered its length and branching.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun rememberMessageBubbleTtsPresentation(
    appState: WhiteNoiseAppState,
    record: AppMessageRecordFfi,
    ttsSpeakableSource: TtsSpeakableSource?,
    speakableIdentity: MessageBubbleTtsSpeakableIdentity?,
    ttsGateInput: MessageBubbleTtsGateInput,
    effectiveTtsCandidate: Boolean,
    editedMarkdownDocument: MarkdownDocumentFfi?,
    visibleTranslation: String?,
    ttsFollowTarget: ConversationTtsFollowTarget?,
    ttsReadAloudProgress: TtsReadAloudProgress?,
    ttsSentenceLayoutSink: ConversationTtsSentenceLayoutSink?,
    ttsRowInstance: Any?,
    ttsSessionId: Long?,
): MessageBubbleTtsPresentation {
    var activeSpeakableDocument by remember(record.messageIdHex, ttsSpeakableSource, record.contentTokens) {
        mutableStateOf<MarkdownDocumentFfi?>(null)
    }
    LaunchedEffect(
        effectiveTtsCandidate,
        ttsSpeakableSource,
        record.messageIdHex,
        record.contentTokens,
        editedMarkdownDocument,
    ) {
        if (!effectiveTtsCandidate || speakableIdentity == null || ttsSpeakableSource == null) {
            activeSpeakableDocument = null
            return@LaunchedEffect
        }
        // Only an active edit has a rendered document to reuse. Everything else
        // without stored content tokens - a message saved before they existed,
        // or one the parser gave no blocks - has to be parsed here, exactly as
        // projectTtsSpeakableEntry parses it. Substituting a null edited
        // document leaves no projection, and the passage below is then rejected
        // for carrying a projection id the bubble cannot match, so read-aloud
        // speaks with no sentence or word highlight at all.
        if (!ttsSpeakableSource.useStoredContentTokens && editedMarkdownDocument != null) {
            activeSpeakableDocument = editedMarkdownDocument
            return@LaunchedEffect
        }
        val document =
            resolveTtsSpeakableDocument(
                message = record,
                source = ttsSpeakableSource,
                parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
            )
        currentCoroutineContext().ensureActive()
        activeSpeakableDocument = document
    }
    val queuedTtsPresentation =
        ttsFollowTarget?.let { target ->
            appState.ttsController
                .presentationEntryFor(
                    target.sessionId,
                    record.messageIdHex,
                    target.projectionId,
                    ttsSpeakableSource?.text,
                )?.takeIf { effectiveTtsCandidate }
        }
    val speakableProjection =
        remember(
            queuedTtsPresentation,
            activeSpeakableDocument,
            effectiveTtsCandidate,
            speakableIdentity,
        ) {
            if (queuedTtsPresentation != null) {
                queuedTtsPresentation.speakableProjection()
            } else if (!effectiveTtsCandidate || speakableIdentity == null) {
                null
            } else {
                activeSpeakableDocument?.let { document ->
                    messageSpeakableProjection(
                        bodyText = speakableIdentity.bodyText,
                        document = document,
                        mentionDisplayName = appState::mentionSpeechName,
                    )
                }
            }
        }
    val ttsProjectionState =
        resolveMessageBubbleTtsProjectionState(
            gateInput = ttsGateInput,
            projectionId = speakableProjection?.projectionId,
            progress = ttsReadAloudProgress,
        )
    val effectiveTtsPassage = ttsProjectionState.effectivePassage
    val preparedTtsSpeech =
        appState.ttsController.preparedSpeechFor(record.messageIdHex, speakableProjection?.projectionId)
    val ttsProjectionResolver =
        rememberTtsHighlightProjectionResolver(
            speakableProjection.takeIf { visibleTranslation == null },
            preparedTtsSpeech,
        )
    val ttsLeafHighlightResolver =
        remember(effectiveTtsPassage, record.messageIdHex, ttsProjectionResolver) {
            if (effectiveTtsPassage == null || effectiveTtsPassage.messageIdHex != record.messageIdHex) {
                null
            } else {
                ttsProjectionResolver?.resolverFor(effectiveTtsPassage, record.messageIdHex)
            }
        }
    val effectiveTtsFollowTarget =
        ttsFollowTarget?.takeIf { target ->
            target.messageIdHex == record.messageIdHex &&
                target.projectionId == speakableProjection?.projectionId &&
                target.sentenceIndex == effectiveTtsPassage?.sentenceIndex
        }
    val ttsSentenceLayoutReporter: TtsSentenceLayoutReporter? =
        remember(
            effectiveTtsFollowTarget,
            ttsProjectionResolver,
            ttsSentenceLayoutSink,
            ttsRowInstance,
        ) {
            val target = effectiveTtsFollowTarget
            val projectionResolver = ttsProjectionResolver
            val sink = ttsSentenceLayoutSink
            val rowInstance = ttsRowInstance
            when {
                target == null || projectionResolver == null -> null
                sink == null || rowInstance == null -> null
                else ->
                    { leafId: String, text: String, layoutResult: TextLayoutResult?, coordinates: LayoutCoordinates? ->
                        val leafLayout = projectionResolver.sentenceLayoutFor(target.sentenceIndex, leafId, text)
                        val bounds =
                            if (layoutResult != null && coordinates != null && leafLayout != null) {
                                ttsSentenceBoundsInWindow(layoutResult, coordinates, leafLayout.renderedRanges)
                            } else {
                                null
                            }
                        if (leafLayout == null || bounds == null) {
                            sink.clear(target, rowInstance, leafId)
                        } else {
                            sink.report(
                                ConversationTtsSentenceLayoutReport(
                                    target = target,
                                    rowInstance = rowInstance,
                                    renderedLeafId = leafId,
                                    boundsInWindow = bounds,
                                    coverage = leafLayout.coverage,
                                    expectedCoverage = leafLayout.expectedCoverage,
                                ),
                            )
                        }
                    }
            }
        }
    val effectiveTtsReadAloudProgress = ttsProjectionState.effectiveProgress
    val ttsBodyExpanded =
        rememberTtsMessageBodyExpanded(
            messageIdHex = record.messageIdHex,
            sourceText = speakableIdentity?.bodyText,
            sessionId = ttsSessionId,
            verifiedActivePassage = effectiveTtsFollowTarget != null && ttsProjectionResolver != null,
        )
    return MessageBubbleTtsPresentation(
        speakableProjection = speakableProjection,
        ttsProjectionResolver = ttsProjectionResolver,
        queuedTtsPresentation = queuedTtsPresentation,
        ttsLeafHighlightResolver = ttsLeafHighlightResolver,
        ttsSentenceLayoutReporter = ttsSentenceLayoutReporter,
        effectiveTtsReadAloudProgress = effectiveTtsReadAloudProgress,
        ttsBodyExpanded = ttsBodyExpanded,
    )
}
