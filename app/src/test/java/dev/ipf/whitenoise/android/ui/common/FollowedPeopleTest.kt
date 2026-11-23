package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowedPeopleTest {
    @Test
    fun matchesFollowedKeysAndNeverTheAccountItself() {
        val own = "d".repeat(64)
        val ada = "a".repeat(64)
        val people = FollowedPeople.of(listOf(ada, own), ownAccountIdHex = own)

        assertTrue(people.contains(ada))
        assertFalse(people.contains(own))
        assertFalse(people.contains(null))
        assertFalse(people.contains("b".repeat(64)))
        assertEquals(people, FollowedPeople.of(listOf(ada), ownAccountIdHex = own))
    }

    @Test
    fun anEmptyListIsTheUnknownState() {
        val own = "d".repeat(64)

        assertSame(FollowedPeople.None, FollowedPeople.of(emptyList(), ownAccountIdHex = null))
        assertSame(FollowedPeople.None, FollowedPeople.of(listOf(own), ownAccountIdHex = own))
    }

    @Test
    fun badgeScalesWithTheAvatarWithinItsBounds() {
        assertEquals(20f, followedPersonBadgeSize(48.dp).value, 0.01f)
        assertEquals(12f, followedPersonBadgeSize(24.dp).value, 0.01f)
        assertEquals(12.5f, followedPersonBadgeSize(30.dp).value, 0.01f)
        assertEquals(28f, followedPersonBadgeSize(152.dp).value, 0.01f)
    }
}
