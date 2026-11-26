package dev.ipf.whitenoise.android.translation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

internal data class TranslationSource(
    val messageId: String,
    val text: String,
    val target: String,
    val revision: String = "",
    val incomingRecordedAt: ULong? = null,
)

internal data class TranslationRendition(
    val text: String,
    val showTranslation: Boolean = true,
    val sourceLanguage: String? = null,
)

internal val LocalConversationTranslation = staticCompositionLocalOf<ConversationTranslationSession?> { null }

/** This object belongs to one mounted account/group/runtime route, never an application singleton. */
internal class ConversationTranslationSession(
    private val scope: CoroutineScope,
    private val engine: suspend () -> TranslationEngine,
    private val ownerCurrent: () -> Boolean,
    private val detectIncoming: suspend (String) -> String? = { null },
) {
    val renditions = mutableStateMapOf<TranslationSource, TranslationRendition>()
    var failure by mutableStateOf<TranslationFailure?>(null)
        private set
    private val pending = mutableStateMapOf<TranslationSource, Job>()
    private val slots = Semaphore(2)
    var epoch by mutableStateOf(0L)
        private set
    var capacityRevision by mutableStateOf(0L)
        private set
    private val renditionOrder = LinkedHashSet<TranslationSource>()
    val replyLanguage = ReplyLanguageEvidence()

    fun request(
        source: TranslationSource,
        from: String = "",
        automatic: Boolean = false,
    ) {
        if (!canRequest(source, automatic)) return
        val requestGeneration = epoch
        val job = scope.launch(start = CoroutineStart.LAZY) { performRequest(source, from, requestGeneration) }
        pending[source] = job
        job.start()
    }

    private fun canRequest(
        source: TranslationSource,
        automatic: Boolean,
    ): Boolean {
        // Automatic window work leaves one bounded queue entry for an explicit user action.
        val pendingLimit = if (automatic) MAX_PENDING_REQUESTS - 1 else MAX_PENDING_REQUESTS
        val occupied = source in pending || source in renditions || pending.size >= pendingLimit
        val paused = automatic && failure != null
        return ownerCurrent() && !occupied && !paused
    }

    private suspend fun currentRequest(
        source: TranslationSource,
        generation: Long,
    ): Boolean = generation == epoch && ownerCurrent() && pending[source] === currentCoroutineContext().job

    /** Insert the current result within the same bounded window as the visible translations. */
    private suspend fun publishRendition(
        source: TranslationSource,
        generation: Long,
        rendition: TranslationRendition,
    ) {
        if (!currentRequest(source, generation)) return
        if (renditions.size >= MAX_RENDITIONS) {
            renditionOrder.firstOrNull()?.let { oldest ->
                renditionOrder.remove(oldest)
                renditions.remove(oldest)?.let { replyLanguage.retain(oldest, it) }
            }
        }
        renditions[source] = rendition
        renditionOrder.add(source)
    }

    /** Translate first and detect source language locally only for a successful incoming rendition. */
    private suspend fun performRequest(
        source: TranslationSource,
        from: String,
        requestGeneration: Long,
    ) {
        try {
            slots.withPermit {
                if (requestGeneration != epoch || !ownerCurrent()) return@withPermit
                val translated = translateWithPrivacyGate(engine(), source.text, from, source.target)
                if (!currentRequest(source, requestGeneration)) return@withPermit
                val language = source.incomingRecordedAt?.let { identifyIncoming(source.text, detectIncoming) }
                publishRendition(source, requestGeneration, TranslationRendition(translated, sourceLanguage = language))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TranslationException) {
            if (currentRequest(source, requestGeneration)) failure = error.failure
        } catch (_: Exception) {
            if (currentRequest(source, requestGeneration)) failure = TranslationFailure.CONNECTION
        } finally {
            if (pending[source] === currentCoroutineContext().job) {
                pending.remove(source)
                capacityRevision += 1L
            }
        }
    }

    fun isCurrent(): Boolean = ownerCurrent()

    fun isPending(source: TranslationSource): Boolean = source in pending

    fun remove(source: TranslationSource) {
        pending.remove(source)?.cancel()
        renditions.remove(source)?.let { replyLanguage.retain(source, it) }
        renditionOrder.remove(source)
        capacityRevision += 1L
    }

    fun toggle(source: TranslationSource) {
        renditions[source]?.let { renditions[source] = it.copy(showTranslation = !it.showTranslation) }
    }

    fun retry() {
        failure = null
    }

    /** Drop all plaintext and suggestions when the route owner changes or resets. */
    fun clear() {
        epoch += 1
        pending.values.toList().forEach(Job::cancel)
        pending.clear()
        renditions.clear()
        renditionOrder.clear()
        replyLanguage.clear()
        failure = null
    }

    private companion object {
        const val MAX_PENDING_REQUESTS = 16
        const val MAX_RENDITIONS = 64
    }
}

/** A detector failure leaves the translated message usable without a language suggestion. */
private suspend fun identifyIncoming(
    text: String,
    detectIncoming: suspend (String) -> String?,
): String? =
    try {
        detectIncoming(text)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A missing suggestion must never replace a successful incoming translation with an error.
        null
    }

/** Retain one selected reply's language across viewport disposal, without retaining its text. */
internal class ReplyLanguageEvidence {
    private var replyId: String? = null
    private var revision: String? = null
    private var language: String? = null

    /** Retire evidence when the user leaves this reply. */
    fun select(messageId: String?) {
        if (messageId != replyId) {
            clear()
            replyId = messageId
        }
    }

    /** Keep only the language and source revision when the selected reply leaves the window. */
    fun retain(
        source: TranslationSource,
        rendition: TranslationRendition,
    ) {
        if (source.messageId != replyId || source.incomingRecordedAt == null || rendition.sourceLanguage == null) return
        revision = source.revision
        language = rendition.sourceLanguage
    }

    /** Invalidate retained evidence if a returning row was edited or deleted. */
    fun sourceChanged(
        messageId: String,
        source: TranslationSource?,
    ) {
        if (messageId == replyId && (source == null || source.revision != revision)) clear()
    }

    /** Offer retained evidence only to the same selected reply. */
    fun forReply(messageId: String?): String? = language.takeIf { messageId == replyId }

    /** Drop the evidence while keeping the currently selected reply. */
    fun clear() {
        language = null
        revision = null
    }
}

/** A reply can name an older message, while chat-wide evidence must be live and recent. */
internal fun ConversationTranslationSession.suggestedTarget(
    replyId: String?,
    nowSeconds: ULong = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()).toULong(),
): String? {
    if (!isCurrent()) return null
    val recent = (nowSeconds - minOf(nowSeconds, SUGGESTION_MAX_AGE_SECONDS))..nowSeconds
    val translatedIncoming = renditions.entries.filter { it.key.incomingRecordedAt != null }
    val replyLanguage =
        translatedIncoming
            .mapNotNull { (source, rendition) -> rendition.sourceLanguage.takeIf { source.messageId == replyId } }
            .distinct()
            .singleOrNull()
    val selectedLanguage = replyLanguage ?: this.replyLanguage.forReply(replyId)
    val recentLanguage =
        translatedIncoming
            .mapNotNull { (source, rendition) ->
                rendition.sourceLanguage.takeIf {
                    source.incomingRecordedAt != null &&
                        source.incomingRecordedAt in recent &&
                        source.messageId != replyId
                }
            }.distinct()
            .singleOrNull()
    return sequenceOf(selectedLanguage, recentLanguage)
        .filterNotNull()
        .firstOrNull(OfflineTranslatorLanguageCatalog::supportsTarget)
}

private const val SUGGESTION_MAX_AGE_SECONDS = 86_400uL
