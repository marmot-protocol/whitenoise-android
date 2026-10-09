package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.StartProfileChatNoActiveAccountException
import dev.ipf.whitenoise.android.state.groupCreateFailureDetail
import dev.ipf.whitenoise.android.state.groupCreateSelectionFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureCopyable
import dev.ipf.whitenoise.android.state.startProfileChatFailureDetail
import dev.ipf.whitenoise.android.state.startProfileChatFailureIsMissingSetup
import dev.ipf.whitenoise.android.ui.chats.newchat.startChatErrorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteFailurePresentationTest {
    /** Unmatched typed identities keep the precise reason without blaming a selected person. */
    @Test fun foundingFailureRequiresCapturedRecipientForNamedCopy() {
        val missing = MarmotKitException.MissingKeyPackage("outside-selection")
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            groupCreateSelectionFailureDetail(missing, null),
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package_for, listOf("Ada")),
            groupCreateSelectionFailureDetail(missing, "Ada"),
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox),
            groupCreateSelectionFailureDetail(MarmotKitException.MissingMemberInboxRoute("outside"), null),
        )
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateSelectionFailureDetail(MarmotKitException.InvalidKeyPackageEvent("private details"), null),
        )
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            groupCreateSelectionFailureDetail(MarmotKitException.Publish("offline"), null),
        )
    }

    @Test
    fun startProfileChatFailureMapsMissingSetupToHumanCopy() {
        val missing = MarmotKitException.MissingKeyPackage("deadbeef")

        assertEquals(
            AppText.Resource(
                R.string.error_missing_key_package_for,
                listOf("Alice"),
            ),
            startProfileChatFailureDetail(missing) { "Alice" },
        )
        assertFalse(startProfileChatFailureCopyable(missing))

        val missingBlankAccount = MarmotKitException.MissingKeyPackage("  ")
        assertEquals(
            AppText.Resource(R.string.error_missing_key_package),
            startProfileChatFailureDetail(missingBlankAccount) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(missingBlankAccount))
    }

    @Test
    fun startProfileChatDistinguishesInvalidRecipientFromUnusableKeyPackage() {
        val invalidIdentity = MarmotKitException.InvalidIdentity("bad npub")

        assertFalse(startProfileChatFailureIsMissingSetup(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            startProfileChatFailureDetail(invalidIdentity) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(invalidIdentity))
        assertEquals(
            AppText.Resource(R.string.error_invalid_identity_reference),
            groupCreateFailureDetail(invalidIdentity) { "ignored" },
        )

        val invalidKeyPackage = MarmotKitException.InvalidKeyPackageEvent("unsupported cipher suite")
        assertFalse(startProfileChatFailureIsMissingSetup(invalidKeyPackage))
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            startProfileChatFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateFailureDetail(invalidKeyPackage) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(invalidKeyPackage))
    }

    @Test
    fun groupFailureNamesTheAffectedInboxRecipientAndNeverExposesNativeDetails() {
        val missing = MarmotKitException.MissingMemberInboxRoute(" bob ")
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox_for, listOf("Bob")),
            groupCreateFailureDetail(missing) { account ->
                assertEquals("bob", account)
                "Bob"
            },
        )
        assertEquals(
            AppText.Resource(R.string.error_missing_member_inbox),
            groupCreateFailureDetail(MarmotKitException.MissingMemberInboxRoute("  ")) { error("No account") },
        )
        val invalid = MarmotKitException.InvalidKeyPackageEvent("secret relay URL and identity")
        assertEquals(
            AppText.Resource(R.string.error_invalid_key_package),
            groupCreateFailureDetail(invalid) { error("No typed affected account") },
        )
    }

    @Test
    fun aHydrationPendingGroupReadsAsStillLoadingNotAsACreateFailure() {
        val pending = MarmotKitException.GroupHydrationPending("7c3bdc38")

        assertFalse(startProfileChatFailureIsMissingSetup(pending))
        assertEquals(
            AppText.Resource(R.string.toast_chat_still_loading),
            groupCreateFailureDetail(pending) { "ignored" },
        )
        assertEquals(
            AppText.Resource(R.string.toast_chat_still_loading),
            startProfileChatFailureDetail(pending) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(pending))
    }

    @Test
    fun startChatCreationAndReadFailuresUseTheSameFallback() {
        val failures =
            listOf(
                MarmotKitException.Publish("relay unreachable"),
                MarmotKitException.Runtime("relay unreachable"),
                IllegalStateException("unexpected failure"),
            )
        failures.forEach { failure ->
            val state = startChatErrorUiState("npub", "progress", failure, "Alice") { "Alice" }
            assertEquals(startProfileChatFailureDetail(failure) { "Alice" }, state.detail)
            assertEquals(AppText.Resource(R.string.error_group_create_failed_retry), state.detail)
            assertFalse(state.invitation)
        }
    }

    @Test
    fun startProfileChatFailureDistinguishesTechnicalFailures() {
        val publishFailure = MarmotKitException.Publish("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(publishFailure) { "ignored" },
        )
        assertTrue(startProfileChatFailureCopyable(publishFailure))
        val runtimeFailure = MarmotKitException.Runtime("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(runtimeFailure) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(runtimeFailure))
        val unexpectedFailure = RuntimeException("relay unreachable")
        assertEquals(
            AppText.Resource(R.string.error_group_create_failed_retry),
            startProfileChatFailureDetail(unexpectedFailure) { "ignored" },
        )
        assertTrue(startProfileChatFailureCopyable(unexpectedFailure))
    }

    @Test
    fun startProfileChatFailureMapsNoActiveAccountToLocalizedCopy() {
        val noActiveAccount = StartProfileChatNoActiveAccountException()

        assertEquals(
            AppText.Resource(R.string.toast_no_active_account),
            startProfileChatFailureDetail(noActiveAccount) { "ignored" },
        )
        assertFalse(startProfileChatFailureCopyable(noActiveAccount))
    }
}
