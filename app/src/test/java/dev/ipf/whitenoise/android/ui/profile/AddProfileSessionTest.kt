package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation fences do not replace native identity/checkpoint ownership. */
class AddProfileSessionTest {
    /** A second tap cannot queue any other native entry while the first claim is active. */
    @Test fun duplicateAndCompetingActionsAreRejected() {
        val session = AddProfileSession({ true }, {})
        assertTrue(session.begin(OnboardingAction.Creating))
        assertFalse(session.begin(OnboardingAction.Creating))
        assertFalse(session.begin(OnboardingAction.Importing))
        assertFalse(session.begin(OnboardingAction.AmberLogin))
        session.finish(OnboardingAction.Creating)
        assertTrue(session.begin(OnboardingAction.Importing))
    }

    /** Owner/teardown changes are checked again before entering a queued native mutation. */
    @Test fun ownerChangeRevokesEntryAndLateErrors() {
        var owned = true
        val session = AddProfileSession({ owned }, {})
        session.edit("local private draft")
        owned = false
        assertFalse(session.begin(OnboardingAction.Importing))
        assertFalse(session.ownsEntry())
        session.error(42)
        assertEquals(null, session.errorRes)
    }

    /** Disposed sessions cannot dismiss their replacement when a process-owned native callback arrives late. */
    @Test fun disposalClearsSecretAndSuppressesLateDismissal() {
        var dismissals = 0
        val session = AddProfileSession({ true }, { dismissals++ })
        session.edit("local private draft")
        session.dispose()
        session.dismiss()
        session.error(42)
        assertEquals("", session.key.text.toString())
        assertEquals(null, session.errorRes)
        assertEquals(0, dismissals)
        assertFalse(session.begin(OnboardingAction.Creating))
    }

    /** Acceptance, close and account-change signals coalesce into one callback. */
    @Test fun dismissalIsDeliveredOnlyOnce() {
        var dismissals = 0
        val session = AddProfileSession({ true }, { dismissals++ })
        session.dismiss()
        session.dismiss()
        assertEquals(1, dismissals)
    }

    /** Editing controls cannot alter the submitted secret while a native entry is active. */
    @Test fun busyEntryRejectsDraftEdits() {
        val session = AddProfileSession({ true }, {})
        session.edit("submitted private draft")
        assertTrue(session.begin(OnboardingAction.Importing))
        session.edit("other draft")
        assertEquals("submitted private draft", session.key.text.toString())
    }

    /** Returning from Sign In clears draft/validation only, with no native completion or external dismiss. */
    @Test fun localBackClearsDraftWithoutDismissingTheFlow() {
        var dismissals = 0
        val session = AddProfileSession({ true }, { dismissals++ })
        session.edit("local private draft")
        session.error(42)
        session.clearDraft()
        assertEquals("", session.key.text.toString())
        assertEquals(null, session.errorRes)
        assertEquals(0, dismissals)
        assertTrue(session.ownsEntry())
    }

    /** Unrelated global feedback and a prior identical record cannot be attributed to this create attempt. */
    @Test fun nativeFeedbackRequiresFreshOperationSpecificRecord() {
        val session = AddProfileSession({ true }, {})
        session.begin(OnboardingAction.Creating)
        val old = ToastMessage(AppText.Resource(R.string.toast_couldnt_create_identity))
        session.recordNativeFeedback(OnboardingAction.Creating, old, old, null, null)
        assertEquals(null, session.feedback)
        session.recordNativeFeedback(
            OnboardingAction.Creating,
            old,
            ToastMessage(AppText.Plain("unrelated")),
            null,
            null,
        )
        assertEquals(null, session.feedback)
        val current = old.copy(diagnosticReport = "operation=IDENTITY_CREATE", copyable = true)
        session.recordNativeFeedback(OnboardingAction.Creating, old, current, null, null)
        assertEquals(current, session.feedback)
    }

    /** The native Amber cancellation notice stays a cancellation, without fabricated login or recovery success. */
    @Test fun nativeAmberCancellationRetainsActualFeedback() {
        val session = AddProfileSession({ true }, {})
        session.begin(OnboardingAction.AmberLogin)
        val notice = TransientNotice(1L, AppText.Resource(R.string.toast_amber_sign_in_cancelled))
        session.recordNativeFeedback(OnboardingAction.AmberLogin, null, null, null, notice)
        assertEquals(notice.title, session.feedback?.title)
        assertEquals(false, session.feedback?.copyable)
    }
}
