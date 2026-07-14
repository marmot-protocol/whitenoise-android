package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupDetailsDeleteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localDeleteOnlyAppearsForVerifiedNonMembers() {
        val readOnlyInvite = mutableStateOf(false)
        val selfMember = mutableStateOf(false)
        val membersVerified = mutableStateOf(false)
        val deleteLabel = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.chat_row_action_delete_group)

        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsLocalDeleteControl(
                    readOnlyInvite = readOnlyInvite.value,
                    isSelfMember = selfMember.value,
                    membersVerified = membersVerified.value,
                    enabled = true,
                    inProgress = false,
                    onDeleteConfirmed = {},
                )
            }
        }

        composeRule.onNodeWithText(deleteLabel).assertDoesNotExist()
        composeRule.runOnIdle { membersVerified.value = true }
        composeRule.onNodeWithText(deleteLabel).assertIsDisplayed()
        composeRule.runOnIdle { selfMember.value = true }
        composeRule.onNodeWithText(deleteLabel).assertDoesNotExist()
        composeRule.runOnIdle {
            selfMember.value = false
            readOnlyInvite.value = true
        }
        composeRule.onNodeWithText(deleteLabel).assertDoesNotExist()
    }

    @Test
    fun confirmingLocalDeleteInvokesTheDeleteRequest() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val deleteLabel = context.getString(R.string.chat_row_action_delete_group)
        val dialogTitle = context.getString(R.string.delete_group_dialog_title)
        val confirmLabel = context.getString(R.string.delete_group_confirm)
        var confirmed = false

        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsLocalDeleteControl(
                    readOnlyInvite = false,
                    isSelfMember = false,
                    membersVerified = true,
                    enabled = true,
                    inProgress = false,
                    onDeleteConfirmed = { confirmed = true },
                )
            }
        }

        composeRule.onNodeWithText(deleteLabel).performClick()
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()
        composeRule.onNodeWithText(confirmLabel).performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }
}
