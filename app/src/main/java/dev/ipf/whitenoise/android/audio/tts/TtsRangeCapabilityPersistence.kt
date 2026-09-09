package dev.ipf.whitenoise.android.audio.tts

import java.util.Locale

internal fun restoreTtsRangeCapability(
    rangeProbe: TtsRangeCapabilityProbe,
    timingStore: TtsTimingStore?,
    engineKey: String,
    locale: Locale,
): String {
    val rangeVerdictKey = ttsRangeVerdictKey(engineKey, locale)
    val scopedVerdict = timingStore?.rangeVerdict(rangeVerdictKey)
    // Older versions persisted one verdict per engine. Use it only as
    // provisional fallback evidence; the first conclusion in this
    // locale migrates it to the scoped key without deleting the legacy
    // value needed by locales that have not yet been observed.
    val legacyVerdict = if (scopedVerdict == null) timingStore?.rangeVerdict(engineKey) else null
    rangeProbe.restore(scopedVerdict ?: legacyVerdict)
    return rangeVerdictKey
}

internal fun confirmTtsRangeCapability(
    rangeProbe: TtsRangeCapabilityProbe,
    timingStore: TtsTimingStore?,
    rangeVerdictKey: String,
    onConfirmed: () -> Unit,
) {
    // Confirm on EVERY usable range, not only the first: a verdict restored
    // from storage is provisional, and confirmation is what stops it being
    // obeyed for the life of the process after the engine has stopped
    // earning it. The snapshots are read before confirming, because
    // onRangeStart sets reportsRanges itself - guards evaluated afterwards
    // would always be false. A first proof retires the estimate and persists
    // a newly learned verdict. A legacy engine-only true verdict is written
    // once to this locale's key when the callback confirms it.
    val wasProven = rangeProbe.hasConfirmedRangeCapability
    rangeProbe.onRangeStart()
    if (!wasProven) {
        onConfirmed()
        if (timingStore?.rangeVerdict(rangeVerdictKey) != true) {
            timingStore?.setRangeVerdict(rangeVerdictKey, true)
        }
    }
}

internal data class TtsStartFocus(
    val requestedMode: TtsAudioFocusMode,
    val previousMode: TtsAudioFocusMode,
    val wasSpeaking: Boolean,
)
