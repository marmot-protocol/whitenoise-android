package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPresentationTest {
    @Test
    fun identicalTransientCopyStillProducesDistinctNoticeKeys() {
        val title = AppText.Plain("Saved")

        assertNotEquals(
            TransientNotice(id = 1L, title = title),
            TransientNotice(id = 2L, title = title),
        )
    }

    @Test
    fun presentationSeparatesLocalizedCopyFromDiagnostics() {
        val secret = "nsec1" + "q".repeat(60)
        val presentation =
            privacySafeErrorPresentation(
                operationCode = "group admin update",
                throwable = IllegalStateException("failed with $secret"),
                message = AppText.Plain("Couldn\'t update admin. Try again."),
                appVersion = "test",
                androidVersion = "test",
                occurredAtUtc = "2026-08-10T12:00:00Z",
            )

        assertTrue(presentation.message is AppText.Plain)
        assertTrue(presentation.report.contains("operation=GROUP_ADMIN_UPDATE"))
        assertFalse(presentation.report.contains(secret))
        assertFalse(presentation.report.contains("failed with"))
        assertFalse((presentation.message as AppText.Plain).value.contains("IllegalStateException"))
    }
}
