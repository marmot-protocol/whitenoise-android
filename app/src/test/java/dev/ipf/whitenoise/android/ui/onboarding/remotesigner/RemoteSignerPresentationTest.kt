package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.Nip46ErrorFfi
import dev.ipf.marmotkit.Nip46StateFfi
import dev.ipf.marmotkit.Nip46StatusFfi
import dev.ipf.whitenoise.android.state.RemoteSignerAccountStatus
import dev.ipf.whitenoise.android.state.RemoteSignerAccounts
import dev.ipf.whitenoise.android.state.RemoteSignerHealth
import dev.ipf.whitenoise.android.state.toAccountStatus
import dev.ipf.whitenoise.android.ui.settings.RemoteSignerAccountAction
import dev.ipf.whitenoise.android.ui.settings.RemoteSignerAccountActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSignerPresentationTest {
    @Test
    fun everyMdkGrantHasAPlainLanguageLabelAndNoneIsDropped() {
        val groups = remoteSignerGrantsByGroup(mdkRemoteSignerGrants)

        assertEquals(
            mdkRemoteSignerGrants.toSet(),
            groups.values
                .flatten()
                .map { it.id }
                .toSet(),
        )
        assertFalse("MDK's grants all have plain-language groups", groups.containsKey(RemoteSignerGrantGroup.Other))
        assertTrue(groups.values.flatten().all { it.labelRes != null })
    }

    @Test
    fun unknownGrantsAreShownVerbatimUnderOther() {
        val groups = remoteSignerGrantsByGroup(listOf("sign_event:13", "sign_event:9999"))

        val other = groups.getValue(RemoteSignerGrantGroup.Other).single()
        assertEquals("sign_event:9999", other.id)
        assertNull(other.labelRes)
    }

    @Test
    fun bunkerLinksAreRecognizedAndKeysAreNot() {
        assertTrue(isBunkerLink("  bunker://abc?relay=wss://r"))
        assertTrue(isBunkerLink("BUNKER://abc"))
        assertFalse(isBunkerLink("nsec1abc"))
        assertFalse(isBunkerLink("nostrconnect://abc"))
    }

    @Test
    fun onlyHttpsApprovalPagesHaveAHost() {
        assertEquals("signer.example", remoteSignerApprovalHost("https://signer.example/a?b=c"))
        assertNull(remoteSignerApprovalHost("http://signer.example/a"))
        assertNull(remoteSignerApprovalHost("javascript:alert(1)"))
        assertNull(remoteSignerApprovalHost("not a url"))
    }

    @Test
    fun failuresMapToUserTermsAndCancellationIsSilent() {
        assertNull(remoteSignerFailureFor(Nip46ErrorFfi.Cancelled()))
        assertEquals(RemoteSignerFailure.Declined, remoteSignerFailureFor(Nip46ErrorFfi.Rejected()))
        val timeout = MarmotKitException.Nip46(Nip46ErrorFfi.Timeout())
        assertEquals(RemoteSignerFailure.TimedOut, remoteSignerFailureFor(timeout))
        assertEquals(RemoteSignerFailure.WrongAccount, remoteSignerFailureFor(Nip46ErrorFfi.Mismatch()))
        assertTrue(RemoteSignerFailure.WrongAccount.startOver)
        assertEquals(RemoteSignerFailure.SignInFailed, remoteSignerFailureFor(IllegalStateException()))
    }

    @Test
    fun accountStatusFollowsMdkStateAndKeepsApprovalOnlyWithItsRequest() {
        fun health(
            state: Nip46StateFfi,
            error: Nip46ErrorFfi? = null,
        ) = Nip46StatusFfi(state, error, null, null, 0u).toAccountStatus().health

        assertEquals(RemoteSignerHealth.Paired, health(Nip46StateFfi.READY))
        assertEquals("a restored idle session is healthy", RemoteSignerHealth.Paired, health(Nip46StateFfi.UNAVAILABLE))
        assertEquals(RemoteSignerHealth.Unreachable, health(Nip46StateFfi.UNAVAILABLE, Nip46ErrorFfi.Timeout()))
        assertEquals(RemoteSignerHealth.Declined, health(Nip46StateFfi.UNAVAILABLE, Nip46ErrorFfi.Rejected()))
        assertEquals(RemoteSignerHealth.PermissionReview, health(Nip46StateFfi.PERMISSION_APPROVAL))
        assertEquals(RemoteSignerHealth.PairingEnded, health(Nip46StateFfi.LOGGED_OUT))
        assertEquals(RemoteSignerHealth.Connecting, health(Nip46StateFfi.APPROVAL))

        val approval = Nip46StatusFfi(Nip46StateFfi.APPROVAL, null, "https://s.example", "r1", 3u).toAccountStatus()
        assertEquals(RemoteSignerHealth.ApprovalNeeded, approval.health)
        assertEquals("r1", approval.requestId)
        assertNull(Nip46StatusFfi(Nip46StateFfi.READY, null, "https://s.example", "r1", 3u).toAccountStatus().authUrl)
        assertFalse(RemoteSignerHealth.Reconnecting.needsAttention)
        assertTrue(RemoteSignerHealth.Unreachable.needsAttention)
    }

    @Test
    fun handlesStayWithTheirRuntimeAndAccount() {
        val accounts = RemoteSignerAccounts()
        val runtime = Any()
        val session = FakeRemoteSignerSession()
        session.status = Nip46StatusFfi(Nip46StateFfi.READY, null, null, null, 0u)
        accounts.adopt(runtime, "a", session)
        accounts.markWithoutSession(runtime, "b", RemoteSignerHealth.Unreachable)

        accounts.retainRuntime(null)
        assertTrue("a momentarily absent runtime keeps handles", accounts.session("a") === session)
        accounts.retainRuntime(runtime)
        assertEquals(RemoteSignerHealth.Unreachable, accounts.status("b")?.health)
        assertNull("an unresolved account has no handle", accounts.session("b"))

        assertNull("a replaced runtime cannot reach this store's handle", accounts.session(Any(), "a"))
        assertTrue(accounts.session(runtime, "a") === session)
        accounts.retainRuntime(Any())
        assertNull("a replaced runtime drops every handle", accounts.session("a"))
        assertFalse(accounts.isRemote("b"))
    }

    @Test
    fun accountKeysOffersOnlyActionsThatCanRun() {
        fun available(
            status: RemoteSignerAccountStatus,
            hasSession: Boolean = true,
            opened: Boolean = false,
        ) = RemoteSignerAccountActions(status, opened, hasSession) {}.available

        val paired = RemoteSignerAccountStatus(RemoteSignerHealth.Paired)
        assertEquals(listOf(RemoteSignerAccountAction.Disconnect), available(paired))
        assertEquals(
            "no session means nothing to disconnect",
            listOf(RemoteSignerAccountAction.Retry, RemoteSignerAccountAction.PairAgain),
            available(RemoteSignerAccountStatus(RemoteSignerHealth.Unreachable), hasSession = false),
        )
        val https = RemoteSignerAccountStatus(RemoteSignerHealth.ApprovalNeeded, "https://s.example/x", "r1")
        assertEquals(
            listOf(RemoteSignerAccountAction.OpenApproval, RemoteSignerAccountAction.Disconnect),
            available(https),
        )
        assertTrue(RemoteSignerAccountAction.FinishApproval in available(https, opened = true))
        val other = RemoteSignerAccountStatus(RemoteSignerHealth.ApprovalNeeded, "intent://x", "r1")
        assertEquals(listOf(RemoteSignerAccountAction.Retry, RemoteSignerAccountAction.Disconnect), available(other))
        assertEquals(
            listOf(RemoteSignerAccountAction.PairAgain),
            available(RemoteSignerAccountStatus(RemoteSignerHealth.PairingEnded)),
        )
    }
}
