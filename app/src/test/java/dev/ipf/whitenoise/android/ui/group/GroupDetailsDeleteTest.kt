package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupDetailsDeleteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun localDeleteOnlyAppearsForVerifiedNonMembers() {
        val readOnlyInvite = mutableStateOf(false)
        val selfMember = mutableStateOf(false)
        val membersVerified = mutableStateOf(false)
        val deleteLabel = context.getString(R.string.chat_row_action_delete_group)

        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsLocalDeleteControl(
                    isDm = false,
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
        val deleteLabel = context.getString(R.string.chat_row_action_delete_group)
        val dialogTitle = context.getString(R.string.delete_group_dialog_title)
        val confirmLabel = context.getString(R.string.delete_group_confirm)
        var confirmed = false

        renderDeleteControl(isDm = false) { confirmed = true }

        composeRule.onNodeWithText(deleteLabel).performClick()
        composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()
        composeRule.onNodeWithText(confirmLabel).performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun groupLocalDeleteUsesGroupWording() {
        renderDeleteControl(isDm = false)

        composeRule.onNodeWithText(context.getString(R.string.chat_row_action_delete_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_row_action_delete_chat)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.chat_row_action_delete_group)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.delete_group_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.delete_group_dialog_message)).assertIsDisplayed()
    }

    @Test
    fun directMessageLocalDeleteUsesChatWording() {
        renderDeleteControl(isDm = true)

        composeRule.onNodeWithText(context.getString(R.string.chat_row_action_delete_chat)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_row_action_delete_group)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.chat_row_action_delete_chat)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.delete_chat_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.delete_chat_dialog_message)).assertIsDisplayed()
    }

    @Test
    fun directMessageDeleteCopyNeverSaysGroup() {
        val chatCopy =
            listOf(
                R.string.chat_row_action_delete_chat,
                R.string.delete_chat_dialog_title,
                R.string.delete_chat_dialog_message,
                R.string.leave_chat,
                // The DM dialog reuses this confirm verb, so it has to stay generic.
                R.string.delete_group_confirm,
            ).map { context.getString(it) }

        chatCopy.forEach { copy ->
            assertTrue(copy, !copy.contains("group", ignoreCase = true))
        }
    }

    @Test
    fun leaveCopyDiffersBetweenChatsAndGroups() {
        assertNotEquals(
            context.getString(R.string.leave_chat),
            context.getString(R.string.leave_group),
        )
    }

    @Test
    fun overflowAndDangerButtonPickTheLeaveLabelTheSameWay() {
        val source = groupDetailsSource().readText()

        assertTrue(
            "the overflow leave item must pick its label from isDm, not always say chat",
            "isDm -> R.string.leave_chat" in source && "else -> R.string.leave_group" in source,
        )
        assertTrue(
            "the danger leave button must keep the same isDm split",
            "stringResource(if (isDm) R.string.leave_chat else R.string.leave_group)" in source,
        )
        assertEquals(
            "every leave affordance must resolve through the same two resources",
            2,
            Regex("R\\.string\\.leave_chat").findAll(source).count(),
        )
        assertEquals(2, Regex("R\\.string\\.leave_group").findAll(source).count())
    }

    private fun groupDetailsSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing GroupDetailsScreen.kt source file")

    private fun renderDeleteControl(
        isDm: Boolean,
        onDeleteConfirmed: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                GroupDetailsLocalDeleteControl(
                    isDm = isDm,
                    readOnlyInvite = false,
                    isSelfMember = false,
                    membersVerified = true,
                    enabled = true,
                    inProgress = false,
                    onDeleteConfirmed = onDeleteConfirmed,
                )
            }
        }
    }
}
