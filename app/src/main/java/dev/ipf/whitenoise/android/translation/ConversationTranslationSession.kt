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

    private suspend fun publishRendition(
        source: TranslationSource,
        generation: Long,
        rendition: TranslationRendition,
    ) {
        if (!currentRequest(source, generation)) return
        if (renditions.size >= MAX_RENDITIONS) {
            renditionOrder.firstOrNull()?.let { oldest ->
                renditionOrder.remove(oldest)
                renditions.remove(oldest)
            }
        }
        renditions[source] = rendition
        renditionOrder.add(source)
    }

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
                val language = source.incomingRecordedAt?.let { identifyIncoming(source.text) }
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

    private suspend fun identifyIncoming(text: String): String? =
        try {
            detectIncoming(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A missing suggestion must never replace a successful incoming translation with an error.
            null
        }

    /** Use matching live results only. A reply can name an older message, while chat-wide evidence must be recent. */
    fun suggestedTarget(
        replyId: String?,
        nowSeconds: ULong = (System.currentTimeMillis() / 1_000).toULong(),
    ): String? {
        if (!ownerCurrent()) return null
        val recent = (nowSeconds - minOf(nowSeconds, SUGGESTION_MAX_AGE_SECONDS))..nowSeconds
        val translatedIncoming = renditions.entries.filter { it.key.incomingRecordedAt != null }
        val replyLanguage =
            translatedIncoming.mapNotNull { (source, rendition) ->
                rendition.sourceLanguage.takeIf { source.messageId == replyId }
            }.distinct().singleOrNull()
        val language =
            replyLanguage ?: translatedIncoming.mapNotNull { (source, rendition) ->
                rendition.sourceLanguage.takeIf {
                    source.incomingRecordedAt != null && source.incomingRecordedAt in recent
                }
            }.distinct().singleOrNull()
        return language?.takeIf(OfflineTranslatorLanguageCatalog::supportsTarget)
    }

    fun isPending(source: TranslationSource): Boolean = source in pending

    fun remove(source: TranslationSource) {
        pending.remove(source)?.cancel()
        renditions.remove(source)
        renditionOrder.remove(source)
        capacityRevision += 1L
    }

    fun toggle(source: TranslationSource) {
        renditions[source]?.let { renditions[source] = it.copy(showTranslation = !it.showTranslation) }
    }

    fun retry() {
        failure = null
    }

    fun clear() {
        epoch += 1
        pending.values.toList().forEach(Job::cancel)
        pending.clear()
        renditions.clear()
        renditionOrder.clear()
        failure = null
    }

    private companion object {
        const val MAX_PENDING_REQUESTS = 16
        const val MAX_RENDITIONS = 64
        const val SUGGESTION_MAX_AGE_SECONDS = 24uL * 60uL * 60uL
    }
}
