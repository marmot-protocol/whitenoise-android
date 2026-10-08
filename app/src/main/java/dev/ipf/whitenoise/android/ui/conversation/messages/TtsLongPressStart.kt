package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.audio.tts.resolveTtsSpeakableSource
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Frozen UI intent; it contains no independently persisted message or sentence state. */
internal data class TtsLongPressStart(
    val renderedHit: RenderedTextHit?,
    val sourceText: String?,
    val accountRef: String?,
    val groupIdHex: String,
    val runtimeGeneration: Int,
) {
    fun isCurrent(
        text: String?,
        account: String?,
        group: String,
        generation: Int,
    ): Boolean {
        val sameOwner = accountRef == account && groupIdHex == group && runtimeGeneration == generation
        return sameOwner && sourceText == text
    }
}

internal fun captureTtsLongPressStart(
    hit: RenderedTextHit?,
    sourceText: String?,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): TtsLongPressStart =
    TtsLongPressStart(
        hit,
        sourceText,
        controller.boundAccountRef,
        controller.group.groupIdHex,
        appState.runtimeGeneration,
    )

/** Re-read the existing timeline/edit projection without introducing another source store. */
internal fun isTtsLongPressStartCurrent(
    owner: TtsLongPressStart,
    record: AppMessageRecordFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
): Boolean {
    val live = controller.timeline.firstOrNull { it.record.messageIdHex == record.messageIdHex }
    val source =
        resolveTtsSpeakableSource(
            live?.record ?: record,
            controller.editsByTarget[record.messageIdHex]?.latestText,
        )
    val unavailable =
        record.messageIdHex in controller.deletedMessageIds ||
            live?.projected?.deleted == true ||
            live?.projected?.invalidationStatus != null
    return !unavailable &&
        owner.isCurrent(
            source?.text,
            appState.activeAccountRef,
            controller.group.groupIdHex,
            appState.runtimeGeneration,
        )
}
