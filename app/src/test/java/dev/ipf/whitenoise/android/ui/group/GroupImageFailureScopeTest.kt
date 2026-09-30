package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ToastMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Attempt ownership for group-image failures and unrelated app notices. */
class GroupImageFailureScopeTest {
    private var current: ToastMessage? = null
    private val scope =
        GroupImageFailureScope(
            currentNotice = { current },
            retireNotice = { expected -> if (current === expected) current = null },
        )

    /** A new image choice clears the previous attempt's notice. */
    @Test
    fun replacementClearsItsFailure() {
        val attempt = scope.begin()
        current = notice("image failed")
        scope.captureFailure(attempt)

        scope.begin()

        assertNull(current)
        assertFalse(scope.isCurrent(attempt))
    }

    /** A later unrelated error survives cleanup of an older image attempt. */
    @Test
    fun unrelatedNewerNoticeSurvivesLeavingImageFlow() {
        val attempt = scope.begin()
        current = notice("image failed")
        scope.captureFailure(attempt)
        val unrelated = notice("newer unrelated failure")
        current = unrelated

        scope.dispose()

        assertSame(unrelated, current)
    }

    /** An in-flight image attempt cannot present after the screen leaves. */
    @Test
    fun leavingInvalidatesPendingAttemptAndClearsOwnedNotice() {
        val attempt = scope.begin()
        current = notice("image failed")
        scope.captureFailure(attempt)

        scope.dispose()

        assertNull(current)
        assertFalse(scope.isCurrent(attempt))
    }

    /** Only the latest attempt may capture an error after a retry begins. */
    @Test
    fun staleAttemptCannotCaptureNewFailure() {
        val first = scope.begin()
        val latest = scope.begin()
        current = notice("latest error")
        scope.captureFailure(first)

        assertFalse(scope.isCurrent(first))
        assertTrue(scope.isCurrent(latest))
        scope.dispose()
        assertEquals("latest error", (current?.title as AppText.Plain).value)
    }

    private fun notice(title: String): ToastMessage = ToastMessage(AppText.Plain(title))
}
