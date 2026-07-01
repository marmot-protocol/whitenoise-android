package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AvatarBindingIdentityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupMemberRowsKeepRememberedStateWithMemberIdentityAfterReorder() {
        val alice = member("alice")
        val bob = member("bob")
        val carol = member("carol")
        val members = mutableStateOf(listOf(alice, bob, carol))

        composeRule.setContent {
            Column {
                GroupMemberIdentityRows(members.value) { _, member ->
                    val rememberedMemberId = remember { member.memberIdHex }

                    Text(
                        text = "${member.memberIdHex}:$rememberedMemberId",
                        modifier = Modifier.testTag(member.memberIdHex),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("alice").assertTextEquals("alice:alice")
        composeRule.onNodeWithTag("bob").assertTextEquals("bob:bob")
        composeRule.onNodeWithTag("carol").assertTextEquals("carol:carol")

        members.value = listOf(carol, bob, alice)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("carol").assertTextEquals("carol:carol")
        composeRule.onNodeWithTag("bob").assertTextEquals("bob:bob")
        composeRule.onNodeWithTag("alice").assertTextEquals("alice:alice")
    }

    private fun member(memberIdHex: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = memberIdHex,
            account = null,
            local = false,
        )
}
