package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMemberDuplicateKeyTest {
    @Test
    fun detectsRawDuplicateSignatureKeyCommitError() {
        // The exact raw string the engine surfaces today (#899) — the user must
        // never see this; the controller maps it to plain language instead.
        assertTrue(
            isDuplicateSignatureKeyError(
                "details=backend failure: add_members: " +
                    "CreateCommitError(ProposalValidationError(DuplicateSignatureKey))",
            ),
        )
        // Survives wrapper/format churn: matched on the leaf enum name, any case.
        assertTrue(isDuplicateSignatureKeyError("duplicatesignaturekey"))
    }

    @Test
    fun otherErrorsAreNotTreatedAsDuplicateKey() {
        assertFalse(isDuplicateSignatureKeyError(null))
        assertFalse(isDuplicateSignatureKeyError(""))
        assertFalse(isDuplicateSignatureKeyError("backend busy, try again"))
    }

    @Test
    fun friendlyDuplicateMessageNamesTheResolvedMember() {
        // The plain-language replacement for the raw enum path (#899): names the
        // person and explains the two real causes (already a member / key
        // collision) without leaking CreateCommitError(...).
        assertEquals(
            "Couldn't add Alice. They're already a member, or their signing key conflicts with an existing member.",
            ConversationControllerCopy().couldntAddMemberDuplicate("Alice"),
        )
    }

    @Test
    fun duplicateKeyDisplayNameUsesFirstRefForListInvites() {
        assertEquals(
            "Alice",
            duplicateSignatureKeyDisplayName(listOf("alice", "bob")) { ref ->
                ref.replaceFirstChar { it.uppercaseChar() }
            },
        )
    }
}
