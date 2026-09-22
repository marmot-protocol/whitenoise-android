package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.core.GroupSystemCopy
import dev.ipf.whitenoise.android.core.GroupSystemEvent
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MDK carries the authenticated actor of a removal, but its display name may simply not be known
 * here. The shared summary already has a truthful form for that — "You were removed" — so the
 * notification's job is to let it run rather than to manufacture an actor.
 */
class NotificationRemovalIdentityTextTest {
    /** A locally named actor is used, because naming the person who removed you is useful. */
    @Test
    fun aLocallyNamedActorIsAttributedForARemovalOfSelf() {
        val actorName =
            GroupSystemEvents.actorNameOrSomeone(
                event = removalOfSelf(),
                resolvedActorName = "Alice",
                subjectIsSelf = true,
                someone = SOMEONE,
            )

        assertEquals("Alice", actorName)
        assertEquals("Alice removed you", summary(actorName, subjectIsSelf = true))
    }

    /** An unresolved actor selects the passive branch instead of manufacturing "Someone". */
    @Test
    fun anUnresolvedActorSelectsThePassiveRemovalBranch() {
        val actorName =
            GroupSystemEvents.actorNameOrSomeone(
                event = removalOfSelf(),
                resolvedActorName = null,
                subjectIsSelf = true,
                someone = SOMEONE,
            )

        assertNull(actorName)
        val text = summary(actorName, subjectIsSelf = true)
        assertEquals("You were removed", text)
        assertFalse(SOMEONE in text)
    }

    /** A blank or sanitizer-rejected name arrives here as null and takes the same passive branch. */
    @Test
    fun aBlankOrUnsafeActorNameIsTreatedAsUnresolved() {
        listOf(null, "".takeIf(String::isNotBlank), "   ".takeIf(String::isNotBlank)).forEach { resolved ->
            assertNull(
                GroupSystemEvents.actorNameOrSomeone(
                    event = removalOfSelf(),
                    resolvedActorName = resolved,
                    subjectIsSelf = true,
                    someone = SOMEONE,
                ),
            )
        }
    }

    /** Removing somebody else is not this issue's case and keeps its existing unknown-actor copy. */
    @Test
    fun removingAnotherMemberKeepsTheExistingUnknownActorFallback() {
        val actorName =
            GroupSystemEvents.actorNameOrSomeone(
                event = removalOfSelf(),
                resolvedActorName = null,
                subjectIsSelf = false,
                someone = SOMEONE,
            )

        assertEquals(SOMEONE, actorName)
    }

    /** Other membership events keep their unknown-actor fallback even when they are about the reader. */
    @Test
    fun otherMembershipEventsAboutTheReaderKeepTheirFallback() {
        val actorName =
            GroupSystemEvents.actorNameOrSomeone(
                event = removalOfSelf().copy(systemType = "member_added"),
                resolvedActorName = null,
                subjectIsSelf = true,
                someone = SOMEONE,
            )

        assertEquals(SOMEONE, actorName)
    }

    /** A payload that literally says "Someone" cannot become the rendered actor. */
    @Test
    fun aPayloadThatSaysSomeoneStillYieldsTheDirectFallback() {
        val event = removalOfSelf().copy(text = "Someone removed you")

        val actorName =
            GroupSystemEvents.actorNameOrSomeone(
                event = event,
                resolvedActorName = null,
                subjectIsSelf = true,
                someone = SOMEONE,
            )

        assertEquals("You were removed", summary(actorName, subjectIsSelf = true, event = event))
    }

    /** Leaving still reads as leaving, never as an anonymous admin having removed the reader. */
    @Test
    fun theReaderLeavingIsNeverPresentedAsARemoval() {
        val text =
            GroupSystemEvents.summary(
                event = removalOfSelf().copy(systemType = "member_left"),
                actorName = null,
                subjectName = null,
                actorIsSelf = true,
                subjectIsSelf = true,
                copy = GroupSystemCopy.Default,
            )

        assertEquals(GroupSystemCopy.Default.youMemberLeft, text)
        assertFalse(SOMEONE in text)
    }

    /** Renders the shared summary for a removal of the reader. */
    private fun summary(
        actorName: String?,
        subjectIsSelf: Boolean,
        event: GroupSystemEvent = removalOfSelf(),
    ): String =
        GroupSystemEvents.summary(
            event = event,
            actorName = actorName,
            subjectName = null,
            actorIsSelf = false,
            subjectIsSelf = subjectIsSelf,
            copy = GroupSystemCopy.Default,
        )

    /** An authenticated projection of the reader being removed by an actor with no local name. */
    private fun removalOfSelf() =
        GroupSystemEvent(
            systemType = "member_removed",
            text = "",
            actor = ACTOR_ID,
            subject = SUBJECT_ID,
            name = null,
            fromAuthenticatedStateProjection = true,
        )

    private companion object {
        const val SOMEONE = "Someone"
        val ACTOR_ID = "ab".repeat(32)
        val SUBJECT_ID = "cd".repeat(32)
    }
}
