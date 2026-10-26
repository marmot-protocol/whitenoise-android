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
) {
    // A usable native range proves current timing capability. Historical confirmation
    // controls persistence only; it cannot provide timings for a later silent utterance.
    val wasProven = rangeProbe.hasConfirmedRangeCapability
    rangeProbe.onRangeStart()
    if (!wasProven) {
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
