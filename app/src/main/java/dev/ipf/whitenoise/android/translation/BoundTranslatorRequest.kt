package dev.ipf.whitenoise.android.translation

import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import kotlinx.coroutines.CompletableDeferred

/** Catalog initialization stays on the verified binding and within the caller's overall deadline. */
internal suspend fun translateOnBoundTranslator(
    service: ITranslationService,
    text: String,
    source: String,
    target: String,
    prepareSubmission: suspend (CompletableDeferred<String>) -> Unit,
): String {
    if (target !in offlineTranslatorLanguages || (source.isNotEmpty() && source !in offlineTranslatorLanguages)) {
        throw TranslationException(TranslationFailure.INVALID_REQUEST)
    }
    val ready = CompletableDeferred<String>()
    try {
        prepareSubmission(ready)
        // 0.8.3–0.8.5 resolve language codes before waiting for their catalog. An empty
        // request's callback runs after that wait. Discard it, then resolve the actual
        // requested pair on this still-bound service. No message text reaches the probe.
        runTranslatorBinderCall { service.translate("", "", "", catalogReadyCallback(ready)) }
        ready.await()
    } finally {
        ready.cancel()
    }
    val result = CompletableDeferred<String>()
    try {
        prepareSubmission(result)
        runTranslatorBinderCall { service.translate(text, source, target, translationCallback(result)) }
        return result.await()
    } finally {
        result.cancel()
    }
}

private fun catalogReadyCallback(ready: CompletableDeferred<String>): ITranslationCallback.Stub =
    object : ITranslationCallback.Stub() {
        override fun onTranslationResult(translatedText: String?) {
            if (translatedText == "") {
                ready.complete("")
            } else {
                ready.completeExceptionally(TranslationException(TranslationFailure.ENGINE))
            }
        }

        override fun onTranslationError(error: TranslationError?) {
            // Empty input normally cannot be detected. These callbacks occur only after
            // the engine's language-availability check finishes; neither is user output.
            when (error?.type) {
                ErrorType.COULD_NOT_DETECT_LANGUAGE, ErrorType.DETECTED_BUT_UNAVAILABLE -> ready.complete("")
                else -> ready.completeExceptionally(TranslationException(TranslationFailure.ENGINE))
            }
        }
    }

private fun translationCallback(result: CompletableDeferred<String>): ITranslationCallback.Stub =
    object : ITranslationCallback.Stub() {
        override fun onTranslationResult(translatedText: String?) {
            val text = translatedText?.takeIf { it.isNotBlank() && it.length <= MAX_TRANSLATION_TEXT }
            if (text == null) {
                result.completeExceptionally(TranslationException(TranslationFailure.ENGINE))
            } else {
                result.complete(text)
            }
        }

        override fun onTranslationError(error: TranslationError?) {
            val failure =
                when (error?.type) {
                    ErrorType.COULD_NOT_DETECT_LANGUAGE -> TranslationFailure.DETECTION
                    ErrorType.DETECTED_BUT_UNAVAILABLE -> TranslationFailure.SOURCE_MODEL
                    else -> TranslationFailure.ENGINE
                }
            // Engine message/language payloads are never retained, displayed or logged.
            result.completeExceptionally(TranslationException(failure))
        }
    }
