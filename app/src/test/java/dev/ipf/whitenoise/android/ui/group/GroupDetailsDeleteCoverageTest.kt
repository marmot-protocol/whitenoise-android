package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GroupDetailsDeleteCoverageTest {
    @Test
    fun nonMemberGroupDetailsExposeLocalDeleteAction() {
        val source = groupDetailsSource().readText()

        assertTrue(
            "group details should expose Delete only after membership is verified and the active account is no longer a member",
            "else if (!readOnlyInvite && controller.membersVerified)" in source &&
                "R.string.chat_row_action_delete_group" in source &&
                "pendingConfirm = DetailsConfirm.Delete" in source,
        )
        assertTrue(
            "confirmed details delete must use the conversation local wipe and dismiss the details screen",
            "mutation = { controller.deleteGroupLocal() }" in source &&
                "onSuccess = { onLeft() }" in source,
        )
    }

    private fun groupDetailsSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing GroupDetailsScreen.kt source file")
}
