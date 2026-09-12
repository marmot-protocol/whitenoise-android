package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.NoticeTier
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction

/** Presentation lifetime only: existing AppState mutations still own accepted identity work after this view closes. */
@Suppress("TooManyFunctions") // One short-lived presentation owns its key, action, feedback and dismissal fences.
internal class AddProfileSession(
    private val ownerAvailable: () -> Boolean,
    private val onDismiss: () -> Unit,
) {
    val key = TextFieldState()
    var action by mutableStateOf(OnboardingAction.Idle)
        private set
    var errorRes by mutableStateOf<Int?>(null)
        private set
    var feedback by mutableStateOf<ToastMessage?>(null)
        private set
    private var alive = true
    private var dismissed = false

    /** Atomically claims one visible action before launching the existing native operation. */
    fun begin(requested: OnboardingAction): Boolean {
        if (!ownsEntry() || action != OnboardingAction.Idle || requested == OnboardingAction.Idle) return false
        action = requested
        errorRes = null
        feedback = null
        return true
    }

    /** Native entry is blocked after dismissal, owner replacement, teardown or a competing account reactivation. */
    fun ownsEntry(): Boolean = alive && ownerAvailable()

    /** A late result cannot reset the controls of a disposed or replaced presentation. */
    fun finish(requested: OnboardingAction) {
        if (alive && action == requested) action = OnboardingAction.Idle
    }

    /** Field edits clear only this presentation's validation state and cannot alter a busy submitted request. */
    fun edit(value: String) {
        if (!ownsEntry() || action != OnboardingAction.Idle) return
        if (key.text.toString() != value) key.setTextAndPlaceCursorAtEnd(value)
        errorRes = null
    }

    /** Form errors belong only to the still-current entry and never include raw native exceptions. */
    fun error(value: Int?) {
        if (ownsEntry()) errorRes = value
    }

    /** Explicit local navigation clears key material without changing any native identity/checkpoint. */
    fun clearDraft() {
        key.setTextAndPlaceCursorAtEnd("")
        errorRes = null
    }

    /** Mirrors only a fresh native record for this operation so full-screen chrome cannot hide its result. */
    @Suppress("LongParameterList")
    fun recordNativeFeedback(
        requested: OnboardingAction,
        previousToast: ToastMessage?,
        currentToast: ToastMessage?,
        previousNotice: TransientNotice?,
        currentNotice: TransientNotice?,
    ) {
        if (!ownsEntry() || action != requested) return
        val errorTitle =
            when (requested) {
                OnboardingAction.Creating -> AppText.Resource(R.string.toast_couldnt_create_identity)
                OnboardingAction.AmberLogin -> AppText.Resource(R.string.toast_couldnt_login_amber)
                else -> return
            }
        if (currentToast !== previousToast && currentToast?.title == errorTitle) {
            feedback = currentToast
        } else if (requested == OnboardingAction.AmberLogin &&
            currentNotice !== previousNotice &&
            currentNotice?.title == AppText.Resource(R.string.toast_amber_sign_in_cancelled)
        ) {
            feedback = ToastMessage(currentNotice.title, currentNotice.detail, tier = NoticeTier.Confirmation)
        }
    }

    /** Dismissing local feedback does not consume or replace the app's global report owner. */
    fun dismissFeedback() {
        feedback = null
    }

    /** Coalesces acceptance, owner-change and user-dismiss callbacks while clearing the sensitive buffer. */
    fun dismiss() {
        if (dismissed) return
        dismissed = true
        dispose()
        onDismiss()
    }

    /** Removes sensitive presentation state; intentionally does not cancel process-owned AppState work. */
    fun dispose() {
        dismissed = true
        alive = false
        feedback = null
        clearDraft()
    }
}
