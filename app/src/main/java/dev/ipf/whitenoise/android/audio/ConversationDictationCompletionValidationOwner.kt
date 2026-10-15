package dev.ipf.whitenoise.android.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Owns a pending target-validation answer; late completions cannot outlive its token. */
internal class ConversationDictationCompletionValidationOwner {
    var validatingSessionId: Long? = null
        private set
    private var validationToken: Any? = null

    fun beginValidation(sessionId: Long): Any? {
        if (validatingSessionId == sessionId) return null
        validatingSessionId = sessionId
        return Any().also { validationToken = it }
    }

    fun validationInProgress(sessionId: Long): Boolean = validatingSessionId == sessionId

    fun validationStillOwned(token: Any): Boolean = validationToken === token

    fun finishValidation(token: Any): Boolean {
        if (validationToken !== token) return false
        validationToken = null
        validatingSessionId = null
        return true
    }

    fun reset() {
        validatingSessionId = null
        validationToken = null
    }

    /** An unavailable MDK answer remains indeterminate, preserving the original target. */
    suspend fun validate(
        timeoutMillis: Long,
        validator: suspend (String, String) -> ConversationDictationTargetValidation,
        target: ConversationDictationTarget,
    ): ConversationDictationTargetValidation =
        try {
            withTimeoutOrNull(timeoutMillis) { validator(target.accountRef, target.groupIdHex) }
                ?: ConversationDictationTargetValidation.Indeterminate
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ConversationDictationTargetValidation.Indeterminate
        }
}
