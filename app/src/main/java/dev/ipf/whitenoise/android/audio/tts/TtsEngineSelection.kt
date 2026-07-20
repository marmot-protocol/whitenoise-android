package dev.ipf.whitenoise.android.audio.tts

internal data class TtsEngineSelectionSnapshot(
    val resolution: TtsResolutionResult?,
    val selectedOverride: String?,
)

internal sealed interface TtsEngineSelectionResult {
    data class Adopted(
        val resolution: TtsResolutionResult,
        val selectedOverride: String,
        val releasedHandles: List<TtsEngineHandle>,
    ) : TtsEngineSelectionResult

    data class Retained(
        val releasedHandles: List<TtsEngineHandle>,
    ) : TtsEngineSelectionResult
}

internal fun adoptTtsEngineSelection(
    current: TtsEngineSelectionSnapshot,
    candidate: TtsResolutionResult,
    requestedPackage: String,
): TtsEngineSelectionResult {
    val handle =
        candidate.handle
            ?.takeIf { it.enginePackage == requestedPackage }
            ?: return TtsEngineSelectionResult.Retained(releasedHandles = emptyList())
    val discovery = current.resolution
    val merged =
        candidate.copy(
            engines = discovery?.engines?.takeIf { it.isNotEmpty() } ?: candidate.engines,
            defaultEnginePackage = discovery?.defaultEnginePackage ?: candidate.defaultEnginePackage,
        )
    val previousHandle = current.resolution?.handle
    val released =
        buildList {
            if (handle !== previousHandle) {
                previousHandle?.let(::add)
            }
        }
    return TtsEngineSelectionResult.Adopted(
        resolution = merged,
        selectedOverride = requestedPackage,
        releasedHandles = released,
    )
}

internal fun ttsDiscoveryComplete(resolution: TtsResolutionResult?): Boolean = resolution != null

internal fun shouldReportNoTtsEngine(resolution: TtsResolutionResult?): Boolean = ttsDiscoveryComplete(resolution) && resolution?.hasUsableEngine != true

internal fun runtimeTrustForSelectionWarning(
    enginePackage: String,
    adoptedHandle: TtsEngineHandle?,
    selectedOverride: String?,
): EngineTrust =
    if (
        adoptedHandle != null &&
        adoptedHandle.enginePackage == enginePackage &&
        selectedOverride == enginePackage
    ) {
        adoptedHandle.trust
    } else {
        EngineTrust.Unknown
    }
