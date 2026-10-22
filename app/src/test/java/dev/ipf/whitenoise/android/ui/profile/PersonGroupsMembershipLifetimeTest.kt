package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.common.captureClickCallbackForReplay
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonGroupsMembershipLifetimeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun disposedVisitCallbacksStayRetiredWhenTheSamePersonIsMountedAgain() {
        val appState = emptyAppState()
        val mounted = mutableStateOf(true)
        var adds = 0
        rule.setContent {
            WhiteNoiseTheme {
                if (mounted.value) {
                    PersonGroupsMembershipContent(
                        appState = appState,
                        account = null,
                        personHex = "cc".repeat(32),
                        personName = "River",
                        groups = emptyList(),
                        ownerIsCurrent = { true },
                        onFeedback = {},
                        onMembershipChanged = {},
                        rows = emptyList(),
                        unresolved = false,
                        onBack = {},
                        onOpen = {},
                        onAdd = { adds++ },
                        onRetry = {},
                        avatar = { null },
                    )
                }
            }
        }
        val oldAdd = rule.onNodeWithTag("groups_in_common.add").captureClickCallbackForReplay()
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        rule.runOnIdle { mounted.value = true }
        rule.waitForIdle()
        rule.runOnIdle {
            oldAdd()
            assertEquals(0, adds)
        }
        rule.onNodeWithTag("groups_in_common.add").performClick()
        rule.runOnIdle { assertEquals(1, adds) }
    }
}
