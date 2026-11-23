@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.sync.Mutex

/**
 * Received voice-message transcripts for this process. Results are derived on this device, never enter the
 * message store and are dropped when the process ends; the cache only spares a recording from being transcribed
 * again after it scrolls away or the app returns from the background. Entries are scoped by account and evicted
 * oldest first past [capacity].
 */
@Stable
internal class ReceivedVoiceTranscripts(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val results = mutableStateMapOf<String, AudioTranscriptUi>()
    private val resultOrder = ArrayDeque<String>()
    private val shown = mutableStateMapOf<String, Boolean>()
    private val shownOrder = ArrayDeque<String>()
    private val accountEpochs = mutableMapOf<String, Int>()

    /** One recognition at a time: OSTT runs a single model, and automatic mode may queue a whole screen of clips. */
    val gate = Mutex()

    fun result(key: String): AudioTranscriptUi? = results[key]

    fun storeResult(
        key: String,
        value: AudioTranscriptUi,
    ) {
        put(results, resultOrder, key, value)
    }

    /** The person's explicit show or hide for one message, or null to follow the automatic setting. */
    fun shownOverride(key: String): Boolean? = shown[key]

    fun setShown(
        key: String,
        value: Boolean,
    ) {
        put(shown, shownOrder, key, value)
    }

    fun accountEpoch(account: String): Int = accountEpochs[account] ?: 0

    fun forgetAccount(account: String) {
        accountEpochs[account] = accountEpoch(account) + 1
        val resultPrefix = "$account|a|"
        val shownPrefix = "$account|m|"
        results.keys.removeAll { it.startsWith(resultPrefix) }
        resultOrder.removeAll { it.startsWith(resultPrefix) }
        shown.keys.removeAll { it.startsWith(shownPrefix) }
        shownOrder.removeAll { it.startsWith(shownPrefix) }
    }

    private fun <T> put(
        map: MutableMap<String, T>,
        order: ArrayDeque<String>,
        key: String,
        value: T,
    ) {
        order.remove(key)
        order.addLast(key)
        map[key] = value
        while (order.size > capacity) map.remove(order.removeFirst())
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

/**
 * One conversation's view of [ReceivedVoiceTranscripts]: keys are scoped to [account] so two signed-in accounts
 * in the same group never share a transcript, and [automatic] mirrors the Dictation setting.
 */
@Stable
internal class VoiceTranscriptionScope(
    val transcripts: ReceivedVoiceTranscripts,
    private val account: String,
    val automatic: Boolean,
) {
    private val accountEpoch = transcripts.accountEpoch(account)

    fun shown(messageIdHex: String): Boolean = transcripts.shownOverride(messageKey(messageIdHex)) ?: automatic

    /** True only when the person asked for this message's transcript, rather than the automatic setting. */
    fun requestedExplicitly(messageIdHex: String): Boolean = transcripts.shownOverride(messageKey(messageIdHex)) == true

    fun setShown(
        messageIdHex: String,
        value: Boolean,
    ) {
        if (transcripts.accountEpoch(account) == accountEpoch) transcripts.setShown(messageKey(messageIdHex), value)
    }

    fun result(attachmentKey: String): AudioTranscriptUi? = transcripts.result(attachmentKey(attachmentKey))

    fun storeResult(
        attachmentKey: String,
        value: AudioTranscriptUi,
    ) {
        if (transcripts.accountEpoch(account) != accountEpoch) return
        transcripts.storeResult(attachmentKey(attachmentKey), value)
    }

    private fun messageKey(messageIdHex: String) = "$account|m|$messageIdHex"

    private fun attachmentKey(key: String) = "$account|a|$key"
}

/** Null outside a conversation; transcription then falls back to the per-message toggle's own scope. */
internal val LocalVoiceTranscription = compositionLocalOf<VoiceTranscriptionScope?> { null }

@Composable
internal fun rememberVoiceTranscription(
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): VoiceTranscriptionScope {
    val preferences by appState.conversationDictationPreferences.state.collectAsState()
    val account = controller.boundAccountRef.orEmpty()
    val automatic = preferences.transcribeVoiceMessages
    return remember(appState, account, automatic) {
        VoiceTranscriptionScope(appState.receivedVoiceTranscripts, account, automatic)
    }
}
