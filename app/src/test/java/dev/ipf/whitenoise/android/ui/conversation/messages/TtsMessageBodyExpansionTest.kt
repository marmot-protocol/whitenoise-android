package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsMessageBodyExpansionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun revealedRowSurvivesEvictionAndRestorationButRejectsAChangedSourceOrSession() {
        val restoration = StateRestorationTester(composeRule)
        val mounted = mutableStateOf(true)
        val active = mutableStateOf(false)
        val source = mutableStateOf("First sentence. Later sentence.")
        val session = mutableStateOf<Long?>(0L)
        var expanded = false
        restoration.setContent {
            val holder = rememberSaveableStateHolder()
            if (mounted.value) {
                holder.SaveableStateProvider("message") {
                    expanded = rememberTtsMessageBodyExpanded("message", source.value, session.value, active.value)
                }
            }
        }
        composeRule.runOnIdle { assertFalse(expanded) }
        composeRule.runOnIdle { active.value = true }
        composeRule.runOnIdle { assertTrue(expanded) }
        composeRule.runOnIdle { active.value = false }
        composeRule.runOnIdle { mounted.value = false }
        composeRule.runOnIdle { mounted.value = true }
        composeRule.runOnIdle { assertTrue(expanded) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertTrue(expanded) }
        composeRule.runOnIdle { session.value = null }
        composeRule.runOnIdle { assertTrue(expanded) }
        composeRule.runOnIdle { session.value = 1L }
        composeRule.runOnIdle { assertFalse(expanded) }
        composeRule.runOnIdle { active.value = true }
        composeRule.runOnIdle { assertTrue(expanded) }
        composeRule.runOnIdle {
            active.value = false
            source.value = "Edited source."
        }
        composeRule.runOnIdle { assertFalse(expanded) }
    }
}
