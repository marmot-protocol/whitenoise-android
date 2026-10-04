package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.state.StalenessGuard
import dev.ipf.whitenoise.android.state.ToastMessage

/**
 * Owns one group-image failure across retries without retiring another screen's newer notice.
 * The main thread owns notice changes; attempt ownership can also be checked at native IO entry.
 */
internal class GroupImageFailureScope(
    private val currentNotice: () -> ToastMessage?,
    private val retireNotice: (ToastMessage) -> Unit,
) {
    private val attempts = StalenessGuard()

    @Volatile
    private var active = true
    private var failureNotice: ToastMessage? = null

    /** Start a replacement attempt and retire only this scope's previous failure. */
    fun begin(): Long {
        clear()
        return attempts.advance()
    }

    /** Ignore completions from a replaced attempt or a screen that was left. */
    fun isCurrent(attempt: Long): Boolean = active && attempts.isCurrent(attempt)

    /** Capture the synchronous failure presentation emitted by the current attempt. */
    fun captureFailure(attempt: Long) {
        if (isCurrent(attempt)) failureNotice = currentNotice()
    }

    /** Clear the owned notice after removal, a successful retry, or a new image choice. */
    fun clear() {
        failureNotice?.let(retireNotice)
        failureNotice = null
    }

    /** Leaving the image flow invalidates in-flight completions and its visible failure. */
    fun dispose() {
        active = false
        attempts.advance()
        clear()
    }
}
