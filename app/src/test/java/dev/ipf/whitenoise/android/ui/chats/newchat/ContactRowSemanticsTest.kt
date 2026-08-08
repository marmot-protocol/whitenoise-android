package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
    fun rowContentDescriptionIsExportedOnClickableRow() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ContactRow(
                        title = "Alice",
                        subtitle = "npub1alice",
                        avatarSeed = "alice",
                        avatarUrl = null,
                        onClick = {},
                        rowContentDescription = MEMBER_LIST_MARKER,
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(MEMBER_LIST_MARKER)
            .assertHasClickAction()
    }

    private companion object {
        const val MEMBER_LIST_MARKER = "performance.member_list"
    }
}
