package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarBindingIdentityTest {
    @Test
    fun avatarLoadResultOnlyAppliesToSameMemberAndUrl() {
        val requested =
            AvatarBindingTarget(
                seed = "alice",
                pictureUrl = "https://example.com/alice.png",
            )

        assertTrue(
            avatarLoadResultTargetsCurrentBinding(
                requested = requested,
                current =
                    AvatarBindingTarget(
                        seed = "alice",
                        pictureUrl = "https://example.com/alice.png",
                    ),
            ),
        )
        assertFalse(
            avatarLoadResultTargetsCurrentBinding(
                requested = requested,
                current =
                    AvatarBindingTarget(
                        seed = "bob",
                        pictureUrl = "https://example.com/alice.png",
                    ),
            ),
        )
        assertFalse(
            avatarLoadResultTargetsCurrentBinding(
                requested = requested,
                current =
                    AvatarBindingTarget(
                        seed = "alice",
                        pictureUrl = "https://example.com/bob.png",
                    ),
            ),
        )
    }

    @Test
    fun groupMemberRowKeyFollowsMemberIdentity() {
        val member =
            AppGroupMemberRecordFfi(
                memberIdHex = "alice",
                account = null,
                local = false,
            )

        assertEquals("alice", groupMemberRowKey(member))
    }
}
