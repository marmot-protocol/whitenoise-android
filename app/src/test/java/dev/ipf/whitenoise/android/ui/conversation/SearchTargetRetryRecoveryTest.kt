package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SearchTargetRetryRecoveryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun subscriptionErrorClearingReleasesOnlyTheExplicitRetry() {
        val navigation = MessageTargetNavigationOwner()
        val retry = SearchTargetRetryState()
        val failed = mutableStateOf(true)
        composeRule.setContent { SearchTargetRetryRecoveryEffect(retry, failed.value) }
        composeRule.runOnIdle {
            retry.failed(navigation.begin())
            retry.retry(loadFailurePresent = true)
            assertEquals(0L, retry.generation)
            failed.value = false
        }
        composeRule.runOnIdle { assertEquals(1L, retry.generation) }
        composeRule.runOnIdle { failed.value = true }
        composeRule.runOnIdle { failed.value = false }
        composeRule.runOnIdle { assertEquals(1L, retry.generation) }
    }

    @Test
    fun draggingBeforeSubscriptionRecoveryCancelsTheDeferredRetry() {
        val navigation = MessageTargetNavigationOwner()
        val retry = SearchTargetRetryState()
        val failed = mutableStateOf(true)
        composeRule.setContent { SearchTargetRetryRecoveryEffect(retry, failed.value) }
        composeRule.runOnIdle {
            retry.failed(navigation.begin())
            retry.retry(loadFailurePresent = true)
            navigation.cancel()
            failed.value = false
        }
        composeRule.runOnIdle { assertEquals(0L, retry.generation) }
    }
}
