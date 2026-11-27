@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.endsVoiceTranscription
import kotlinx.coroutines.sync.Mutex

/**
 * Received voice-message transcripts for this process. Results are derived on this device, never enter the
 * message store. The foreground cache spares a recording from being transcribed again after it scrolls away.
 * Entries are scoped by account and chat and evicted oldest first past [capacity].
 */
@Stable
internal class ReceivedVoiceTranscripts(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val results = mutableStateMapOf<String, AudioTranscriptUi>()
    private val resultOrder = ArrayDeque<String>()
    private val shown = mutableStateMapOf<String, Boolean>()
    private val shownOrder = ArrayDeque<String>()
    private val accountEpochs = mutableStateMapOf<String, Int>()
    private val groupEpochs = mutableStateMapOf<String, Int>()
    var generation by mutableIntStateOf(0)
        private set

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

    fun groupEpoch(
        account: String,
        group: String,
    ): Int = groupEpochs["$account|$group|"] ?: 0

    fun forgetAccount(account: String) {
        accountEpochs[account] = accountEpoch(account) + 1
        val prefix = "$account|"
        groupEpochs.keys.removeAll { it.startsWith(prefix) }
        forgetPrefix(prefix)
    }

    fun forgetGroup(
        account: String,
        group: String,
    ) {
        val prefix = "$account|$group|"
        groupEpochs[prefix] = groupEpoch(account, group) + 1
        forgetPrefix(prefix)
    }

    fun clear() {
        generation++
        groupEpochs.clear()
        results.clear()
        resultOrder.clear()
        shown.clear()
        shownOrder.clear()
    }

    private fun forgetPrefix(prefix: String) {
        results.keys.removeAll { it.startsWith(prefix) }
        resultOrder.removeAll { it.startsWith(prefix) }
        shown.keys.removeAll { it.startsWith(prefix) }
        shownOrder.removeAll { it.startsWith(prefix) }
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
 * One conversation's view of [ReceivedVoiceTranscripts], scoped to its account, chat and foreground generation.
 * [automatic] mirrors the Dictation setting.
 */
@Stable
internal class VoiceTranscriptionScope(
    val transcripts: ReceivedVoiceTranscripts,
    private val account: String,
    val automatic: Boolean,
    private val group: String = "",
    private val recognitionAllowed: () -> Boolean = { true },
) {
    private val accountEpoch = transcripts.accountEpoch(account)
    private val generation = transcripts.generation
    private val groupEpoch = transcripts.groupEpoch(account, group)
    private val keyPrefix = "$account|$group|"

    val active: Boolean
        get() {
            if (transcripts.generation != generation) return false
            return transcripts.accountEpoch(account) == accountEpoch &&
                transcripts.groupEpoch(account, group) == groupEpoch &&
                recognitionAllowed()
        }

    fun shown(messageIdHex: String): Boolean {
        if (!active) return false
        return transcripts.shownOverride(messageKey(messageIdHex)) ?: automatic
    }

    /** True only when the person asked for this message's transcript, rather than the automatic setting. */
    fun requestedExplicitly(messageIdHex: String): Boolean {
        if (!active) return false
        return transcripts.shownOverride(messageKey(messageIdHex)) == true
    }

    fun setShown(
        messageIdHex: String,
        value: Boolean,
    ) {
        if (active) transcripts.setShown(messageKey(messageIdHex), value)
    }

    fun result(attachmentKey: String): AudioTranscriptUi? {
        if (!active) return null
        return transcripts.result(attachmentKey(attachmentKey))
    }

    fun storeResult(
        attachmentKey: String,
        value: AudioTranscriptUi,
    ) {
        if (!active) return
        transcripts.storeResult(attachmentKey(attachmentKey), value)
    }

    private fun messageKey(messageIdHex: String) = "${keyPrefix}m|$messageIdHex"

    private fun attachmentKey(key: String) = "${keyPrefix}a|$key"
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
    val group = controller.group.groupIdHex
    val transcripts = appState.receivedVoiceTranscripts
    val generation = transcripts.generation
    val accountEpoch = transcripts.accountEpoch(account)
    val groupEpoch = transcripts.groupEpoch(account, group)
    return remember(transcripts, controller, account, group, automatic, generation, accountEpoch, groupEpoch) {
        VoiceTranscriptionScope(transcripts, account, automatic, group) {
            val foreground = appState.appInForeground && !appState.appLock.screenVisible
            val groupRecord = appState.boundChats(account)?.groupRecordsById?.get(group)
            foreground &&
                controller.isSelfMember &&
                !controller.group.disbanded &&
                controller.acceptsConversationActionOwner(account, group) &&
                controller.latestChatListRow?.endsVoiceTranscription() != true &&
                groupRecord?.endsVoiceTranscription() != true
        }
    }
}
