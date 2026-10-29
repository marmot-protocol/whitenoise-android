package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalReactionMotionLifecycleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun inheritedRowOwnerDoesNotEvaluateTheSystemObserverFallback() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val before = shadowOf(resolver).getContentObservers(uri).size
        rule.setContent {
            val state = rememberLocalReactionMotion("row", enabled = true)
            ReactionMotionRow(state) {
                rememberLocalReactionMotion("nested-unused-owner")
                Text("Row")
            }
        }
        rule.waitForIdle()
        assertEquals(before, shadowOf(resolver).getContentObservers(uri).size)
    }

    @Test fun queuedPreSuppressionCallbackCannotReplaceANewerEffectAfterReenable() {
        var enabled by mutableStateOf(true)
        lateinit var current: LocalReactionMotion
        rule.mainClock.autoAdvance = false
        rule.setContent { current = rememberLocalReactionMotion("conversation", enabled) }
        rule.waitForIdle()
        val lateCallbackOwner = current
        rule.runOnIdle {
            enabled = false
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { current !== lateCallbackOwner }
        rule.waitForIdle()
        val suppressedOwner = current
        rule.runOnIdle {
            assertNotSame(lateCallbackOwner, suppressedOwner)
            assertNull(lateCallbackOwner.effect)
            enabled = true
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { current !== suppressedOwner }
        rule.waitForIdle()
        rule.runOnIdle {
            assertNotSame(lateCallbackOwner, current)
            current.accept("❤", true)
            lateCallbackOwner.accept("👍", true)
            assertEquals("❤", current.effect?.emoji)
            assertNull(lateCallbackOwner.effect)
        }
    }

    @Test fun ownerReplacementDropsPendingDecorativeTextBeforeNewAcceptance() {
        var owner by mutableStateOf("first-account")
        lateinit var current: LocalReactionMotion
        rule.mainClock.autoAdvance = false
        rule.setContent { current = rememberLocalReactionMotion(owner, enabled = true) }
        rule.waitForIdle()
        val oldOwner = current
        rule.runOnIdle {
            oldOwner.accept("👍", true)
            owner = "second-account"
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeUntil(timeoutMillis = 1_000) { current !== oldOwner }
        rule.waitForIdle()
        rule.runOnIdle {
            assertNotSame(oldOwner, current)
            assertNull(oldOwner.effect)
            oldOwner.accept("👍", true)
            assertNull(oldOwner.effect)
            assertNull(current.effect)
        }
    }
}
