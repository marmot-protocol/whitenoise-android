package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.RecipientSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises typed attribution and the exact fence used by the production removal callback. */
class GroupCreationRecoveryTest {
    private val members = (1..10).map { RecipientSearch.Candidate("$it", "Same name", "npub$it") }

    /** Duplicate names never affect matching; only MDK's typed selected identity permits removal. */
    @Test fun supportedFailuresIdentifyExactlyOneSelectedRecipient() {
        listOf(
            MarmotKitException.MissingKeyPackage(" 3 "),
            MarmotKitException.MissingMemberInboxRoute("3"),
        ).forEach { error ->
            val failure = groupCreationRecovery(error, 1, members)
            assertEquals(members[2], failure.removableRecipient(1, members, GroupCreationSession { true }, null))
        }
    }

    /** Native details and unmatched identities never identify a victim by name or selection order. */
    @Test fun unattributedErrorsNeverOfferRemoval() {
        listOf(
            MarmotKitException.MissingKeyPackage(""),
            MarmotKitException.MissingMemberInboxRoute("outside"),
            MarmotKitException.InvalidKeyPackageEvent("recipient 3 failed"),
            MarmotKitException.Publish("offline"),
        ).forEach { assertNull(groupCreationRecovery(it, 1, members).recipient) }
    }

    /** Edited selections, accepted groups and replacement owners cannot consume old errors or repeated taps. */
    @Test fun staleOrAcceptedFailureCannotRemoveMembers() {
        val failure = groupCreationRecovery(MarmotKitException.MissingKeyPackage("3"), 1, members)
        val owner = GroupCreationSession { true }
        assertNull(failure.removableRecipient(2, members, owner, null))
        assertNull(failure.removableRecipient(1, members.drop(1), owner, null))
        assertNull(failure.removableRecipient(1, members, owner, "canonical"))
        assertNull(failure.removableRecipient(1, members, GroupCreationSession { false }, null))
        owner.dispose()
        assertNull(failure.removableRecipient(1, members, owner, null))
    }
}
