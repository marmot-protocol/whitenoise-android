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
