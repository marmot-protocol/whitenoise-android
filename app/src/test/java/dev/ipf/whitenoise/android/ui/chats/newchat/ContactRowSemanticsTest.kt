package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactRowSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun modifierTestTagIsExportedOnClickableRow() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ContactRow(
                        title = "Alice",
                        subtitle = "npub1alice",
                        avatarSeed = "alice",
                        avatarUrl = null,
                        onClick = {},
                        modifier = Modifier.testTag(MEMBER_LIST_MARKER),
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(MEMBER_LIST_MARKER)
            .assertHasClickAction()
    }

    private companion object {
        const val MEMBER_LIST_MARKER = "performance.member_list"
    }
}
