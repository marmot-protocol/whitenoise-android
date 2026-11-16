package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.ui.chats.newchat.createdGroupIdAfterProjectionUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewChatFlowProjectionUnavailableTest {
    @Test
    fun extractsTheCanonicalGroupIdFromTheProjectionUnavailableError() {
        assertEquals(
            "created-group",
            createdGroupIdAfterProjectionUnavailable(
                MarmotKitException.CreatedGroupProjectionUnavailable("created-group"),
            ),
        )
    }

    /** A committed group whose member report MDK lost still reopens by its canonical ID instead of creating again. */
    @Test
    fun extractsTheCanonicalGroupIdWhenTheMemberReportIsUnavailable() {
        assertEquals(
            "created-group",
            createdGroupIdAfterProjectionUnavailable(
                MarmotKitException.CreatedGroupMemberReportUnavailable("created-group"),
            ),
        )
        assertNull(
            createdGroupIdAfterProjectionUnavailable(MarmotKitException.CreatedGroupMemberReportUnavailable(" ")),
        )
    }

    @Test
    fun rejectsOtherFailuresAndBlankCanonicalGroupIds() {
        assertNull(createdGroupIdAfterProjectionUnavailable(MarmotKitException.Runtime("sqlite busy")))
        assertNull(
            createdGroupIdAfterProjectionUnavailable(
                MarmotKitException.CreatedGroupProjectionUnavailable("  "),
            ),
        )
    }
}
