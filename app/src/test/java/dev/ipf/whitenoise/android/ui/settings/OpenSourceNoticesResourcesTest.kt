package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenSourceNoticesResourcesTest {
    @Test
    fun generatedDependencyNoticesAndLocalAttributionAreReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notices = readOpenSourceNotices(context.resources)
        assertTrue(notices.any { it.id == "local-material-icons" })
        assertTrue(notices.any { it.id != "local-material-icons" })
        assertTrue(notices.all { it.name.isNotBlank() && it.text.isNotBlank() })
    }
}
