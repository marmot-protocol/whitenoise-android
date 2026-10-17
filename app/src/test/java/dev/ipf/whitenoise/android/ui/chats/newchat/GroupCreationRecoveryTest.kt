package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MissingKeyPackageReasonFfi
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
            MarmotKitException.MissingKeyPackage(" 3 ", MissingKeyPackageReasonFfi.NOT_FOUND),
            MarmotKitException.MissingMemberInboxRoute("3"),
        ).forEach { error ->
            val failure = groupCreationRecovery(error, 1, members)
            assertEquals(members[2], failure.removableRecipient(1, members, GroupCreationSession { true }, null))
        }
    }

    /** Native details and unmatched identities never identify a victim by name or selection order. */
    @Test fun unattributedErrorsNeverOfferRemoval() {
        listOf(
            MarmotKitException.MissingKeyPackage("", MissingKeyPackageReasonFfi.NOT_FOUND),
            MarmotKitException.MissingMemberInboxRoute("outside"),
            MarmotKitException.InvalidKeyPackageEvent("recipient 3 failed"),
            MarmotKitException.Publish("offline"),
        ).forEach { assertNull(groupCreationRecovery(it, 1, members).recipient) }
    }

    /**
     * An incomplete lookup knows nothing is missing, so its remedy is Retry: every selected member stays submitted
     * and none is offered for removal, even though MDK names the account whose lookup could not finish (#3289).
     */
    @Test fun incompleteLookupKeepsTheWholeSelectionForRetry() {
        val failure = groupCreationRecovery(MarmotKitException.DirectoryLookupIncomplete("3"), 1, members)

        assertNull(failure.recipient)
        assertNull(failure.removableRecipient(1, members, GroupCreationSession { true }, null))
        assertEquals(members.map { it.accountIdHex }, failure.submittedMembers)
    }

    /** Edited selections, accepted groups and replacement owners cannot consume old errors or repeated taps. */
    @Test fun staleOrAcceptedFailureCannotRemoveMembers() {
        val missing = MarmotKitException.MissingKeyPackage("3", MissingKeyPackageReasonFfi.NOT_FOUND)
        val failure = groupCreationRecovery(missing, 1, members)
        val owner = GroupCreationSession { true }
        assertNull(failure.removableRecipient(2, members, owner, null))
        assertNull(failure.removableRecipient(1, members.drop(1), owner, null))
        assertNull(failure.removableRecipient(1, members, owner, "canonical"))
        assertNull(failure.removableRecipient(1, members, GroupCreationSession { false }, null))
        owner.dispose()
        assertNull(failure.removableRecipient(1, members, owner, null))
    }
}
