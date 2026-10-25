package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationMotionInterruptionTest {
    @get:Rule val rule = createComposeRule()
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val originalScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    @After fun restoreScale() {
        setScale(originalScale)
    }

    @Test fun mountedConversationObservesSystemMotionDisableAndReenable() {
        setScale(1f)
        var enabled = false
        rule.setContent { enabled = rememberConversationMotionEnabled() }
        rule.waitForIdle()
        assertTrue(enabled)
        rule.runOnIdle { setScale(0f) }
        rule.waitForIdle()
        assertFalse(enabled)
        rule.runOnIdle { setScale(0.5f) }
        rule.waitForIdle()
        assertTrue(enabled)
    }

    private fun setScale(scale: Float) {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }
}
