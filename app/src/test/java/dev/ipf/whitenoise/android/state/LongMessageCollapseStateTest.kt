package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LongMessageCollapseStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val preferences
        get() =
            RuntimeEnvironment
                .getApplication()
                .applicationContext
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun firstUpdateInvalidatesCompositionAfterPreferenceMiss() {
        val registry = ScopedCacheRegistry()
        val state = LongMessageCollapseState(registry, preferences, maxEntries = 4)
        var renderedValue = false

        composeRule.setContent {
            renderedValue = state.collapseLongMessages(ACCOUNT, GROUP)
        }
        composeRule.waitForIdle()
        assertTrue(renderedValue)

        composeRule.runOnUiThread {
            state.updateCollapseLongMessages(ACCOUNT, GROUP, enabled = false)
        }
        composeRule.waitForIdle()

        assertFalse(renderedValue)
    }

    @Test
    fun evictionDoesNotDetachCompositionFromLaterUpdates() {
        val registry = ScopedCacheRegistry()
        val state = LongMessageCollapseState(registry, preferences, maxEntries = 1)
        var renderedValue = false

        composeRule.setContent {
            renderedValue = state.collapseLongMessages(ACCOUNT, GROUP)
        }
        composeRule.waitForIdle()
        assertTrue(renderedValue)

        composeRule.runOnUiThread {
            state.collapseLongMessages(ACCOUNT, "group-b")
            state.updateCollapseLongMessages(ACCOUNT, GROUP, enabled = false)
        }
        composeRule.waitForIdle()

        assertFalse(renderedValue)
    }

    @Test
    fun accountBoundaryClearDropsCachedStateAndRereadsPreference() {
        val registry = ScopedCacheRegistry()
        val state = LongMessageCollapseState(registry, preferences, maxEntries = 4)
        state.updateCollapseLongMessages(ACCOUNT, GROUP, enabled = false)
        assertFalse(state.collapseLongMessages(ACCOUNT, GROUP))
        LongMessageCollapsePreferences.writeCollapseLongMessages(preferences, ACCOUNT, GROUP, true)

        registry.clearAll()

        assertTrue(state.collapseLongMessages(ACCOUNT, GROUP))
    }

    private companion object {
        const val ACCOUNT = "account-a"
        const val GROUP = "group-a"
    }
}
