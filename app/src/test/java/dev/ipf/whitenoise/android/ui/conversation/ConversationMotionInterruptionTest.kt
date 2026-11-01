package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.conversation.reactions.ReactionMotionRow
import dev.ipf.whitenoise.android.ui.conversation.reactions.rememberLocalReactionMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationMotionInterruptionTest {
    @get:Rule val rule = createComposeRule()
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val originalScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    /** Restores the system animator duration scale the test started with. */
    @After fun restoreScale() {
        setScale(originalScale)
    }

    /** A mounted conversation follows the system animator scale being disabled and then re-enabled. */
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

    /**
     * Twelve mounted rows share one system-motion observer with no fallback registered, and every owner follows a
     * disable and re-enable.
     */
    @Test fun mountedRowsShareOneObserverAndDoNotEvaluateAnUnusedDefault() {
        setScale(1f)
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val before = shadowOf(resolver).getContentObservers(uri).size
        val owners = arrayOfNulls<dev.ipf.whitenoise.android.ui.conversation.reactions.LocalReactionMotion>(12)
        rule.setContent {
            ComposerSendMotionHost(ComposerSendMotion()) {
                Column {
                    repeat(12) { index ->
                        val state = rememberLocalReactionMotion(index)
                        owners[index] = state
                        ReactionMotionRow(state) {
                            // The default must not register a fallback before returning the row owner.
                            rememberLocalReactionMotion("nested-$index")
                            Text("row-$index")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(before + 1, shadowOf(resolver).getContentObservers(uri).size)
        val retired = checkNotNull(owners[0])
        rule.runOnIdle { setScale(0f) }
        rule.waitForIdle()
        assertTrue(owners.all { it?.enabled == false })
        rule.runOnIdle { setScale(1f) }
        rule.waitForIdle()
        rule.runOnIdle {
            retired.accept("👍", true)
            assertEquals(null, retired.effect)
            assertTrue(owners.all { it?.enabled == true && it.effect == null })
        }
        assertEquals(before + 1, shadowOf(resolver).getContentObservers(uri).size)
    }

    /** Sets the system animator duration scale and notifies its content observers. */
    private fun setScale(scale: Float) {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }
}
