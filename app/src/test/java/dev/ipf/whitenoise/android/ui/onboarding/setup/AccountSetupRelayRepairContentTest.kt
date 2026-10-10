package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies exact native declaration text stays readable without losing opaque tag fields. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class AccountSetupRelayRepairContentTest {
    @get:Rule val composeRule = createComposeRule()

    /** URL slashes stay literal while quotes, control characters and backslashes still round-trip. */
    @Test fun readableTagJsonPreservesEveryField() {
        val fields = listOf("r", "wss://relay.example/path/", "read", "quote\"\nslash\\/\t雪")
        val text = relayTagJson(fields)
        assertFalse(text.contains("wss:\\/\\/"))
        assertEquals(fields, JSONArray(text).let { json -> (0 until json.length()).map(json::getString) })
    }

    /** Event content is ordinary text, not an invented JSON array around the native content. */
    @Test fun repairContentDisplaysReadableTagsAndPlainContent() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Column { SetupRelayRepairContent(relayRepairFixture()) }
            }
        }
        composeRule.onAllNodesWithText("[\"r\",\"wss://Custom.example/path/\"]").assertCountEquals(2)
        composeRule.onAllNodesWithText("preserved content").assertCountEquals(2)
        composeRule.onNodeWithText("[\"preserved content\"]").assertDoesNotExist()
    }

    /** An empty native content field uses the existing localized empty-value label. */
    @Test fun emptyContentUsesEmptyValueLabel() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    SetupRelayRepairContent(relayRepairFixture().copy(originalContent = "", proposedContent = ""))
                }
            }
        }
        composeRule.onAllNodesWithText("None").assertCountEquals(2)
        composeRule.onNodeWithText("[\"\"]").assertDoesNotExist()
    }
}
