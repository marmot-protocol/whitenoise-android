package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import dev.ipf.marmotkit.Nip46ErrorFfi
import dev.ipf.marmotkit.Nip46StateFfi
import dev.ipf.marmotkit.Nip46StatusFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACCOUNT = "a1b2c3"
private const val BUNKER = "bunker://abcdef?relay=wss://relay.example&secret=s3cret"

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSignerPairingTest {
    private fun TestScope.pairing(
        host: FakeRemoteSignerHost,
        expected: String? = null,
    ) = RemoteSignerPairing(host, backgroundScope, expected, sessionDispatcher = StandardTestDispatcher(testScheduler))

    @Test
    fun bunkerLinkReviewsExactGrantsBeforePairingThenSignsIn() =
        runTest {
            val session = FakeRemoteSignerSession()
            val host = FakeRemoteSignerHost(session)
            val flow = pairing(host)

            // A link already entered in the key field goes straight to the review.
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()

            assertEquals(BUNKER, JSONObject(host.configs.single()).getString("uri"))
            val review = flow.step as RemoteSignerStep.Review
            assertEquals(mdkRemoteSignerGrants, review.permissions.grants)
            assertEquals(0, session.connects)
            assertNull("nothing is approved before the user confirms", session.approved)

            flow.approve()
            runCurrent()
            assertEquals(review.permissions, session.approved)
            assertEquals(RemoteSignerStep.Connecting, flow.step)

            session.connectReply.complete(ACCOUNT)
            runCurrent()
            assertEquals(RemoteSignerStep.Done, flow.step)
            assertEquals(ACCOUNT, host.finished.single().second)
            assertTrue("the link leaves memory once the account owns the session", flow.link.text.isEmpty())
            assertEquals("the host may clear exactly this link from the clipboard", BUNKER, flow.usedLink)
            flow.dispose()
            assertNull(flow.usedLink)
            assertFalse("a handed-over session is never cancelled by the flow", session.cancelled)
        }

    @Test
    fun connectionCodeAppearsOnlyAfterApprovalAndTheListenerIsReady() =
        runTest {
            val session = FakeRemoteSignerSession()
            val host = FakeRemoteSignerHost(session)
            val flow = pairing(host)

            flow.choose(RemoteSignerMethod.ConnectionCode)
            runCurrent()
            val config = JSONObject(host.configs.single())
            val relays = config.getJSONArray("relays")
            assertEquals(remoteSignerOfferRelays, List(relays.length()) { relays.getString(it) })
            assertTrue(flow.step is RemoteSignerStep.Review)
            assertEquals("MDK accepts no call before approval", 0, session.offers)

            flow.approve()
            runCurrent()
            assertEquals("the offer is withheld until MDK listens", RemoteSignerStep.Connecting, flow.step)
            session.offerReply.complete("nostrconnect://client?secret=x")
            runCurrent()
            assertEquals("nostrconnect://client?secret=x", (flow.step as RemoteSignerStep.WaitingForSigner).offer)
            assertEquals("the prepared offer is used, not requested again", 1, session.offers)

            session.connectReply.complete(ACCOUNT)
            runCurrent()
            assertEquals(RemoteSignerStep.Done, flow.step)
        }

    /** Editing or validating a new link removes an error that belonged to the old text. */
    @Test
    fun malformedLinksStayInlineAndNeverCreateASession() =
        runTest {
            val host = FakeRemoteSignerHost(FakeRemoteSignerSession())
            val flow = pairing(host)
            flow.choose(RemoteSignerMethod.BunkerLink)

            flow.link.setTextAndPlaceCursorAtEnd("nsec1notalink")
            flow.submitLink()
            assertEquals(R.string.remote_signer_error_not_bunker_link, flow.linkErrorRes)
            flow.link.edit { selection = TextRange.Zero }
            assertEquals(R.string.remote_signer_error_not_bunker_link, flow.linkErrorRes)
            assertTrue(host.configs.isEmpty())

            flow.link.setTextAndPlaceCursorAtEnd("bunker://not-a-link")
            assertNull(flow.linkErrorRes)
            flow.submitLink()
            runCurrent()
            assertEquals(R.string.remote_signer_error_invalid_link, flow.linkErrorRes)
            assertEquals(RemoteSignerStep.EnterLink, flow.step)
            assertTrue(host.finished.isEmpty())

            flow.link.setTextAndPlaceCursorAtEnd(BUNKER)
            assertNull(flow.linkErrorRes)
            flow.submitLink()
            runCurrent()
            assertTrue(flow.step is RemoteSignerStep.Review)
            assertTrue(flow.back())
            assertNull(flow.linkErrorRes)
        }

    @Test
    fun signerApprovalShowsTheHostAndAcknowledgesOnlyThatRequest() =
        runTest {
            val session = FakeRemoteSignerSession()
            val flow = pairing(FakeRemoteSignerHost(session))
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()
            flow.approve()
            runCurrent()

            session.status = approvalStatus("https://signer.example/approve?id=1")
            advanceTimeBy(600)
            runCurrent()
            val approval = flow.step as RemoteSignerStep.Approval
            assertEquals("signer.example", approval.host)

            flow.approvalFinished()
            assertEquals(listOf("req-1"), session.completedAuth)
            assertEquals(RemoteSignerStep.Connecting, flow.step)
        }

    @Test
    fun nonHttpsApprovalPagesAreRefused() =
        runTest {
            val session = FakeRemoteSignerSession()
            val flow = pairing(FakeRemoteSignerHost(session))
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()
            flow.approve()
            runCurrent()

            session.status = approvalStatus("intent://evil#Intent;end")
            advanceTimeBy(600)
            runCurrent()
            assertEquals(RemoteSignerStep.Failed(RemoteSignerFailure.Unsupported), flow.step)
            assertTrue(session.completedAuth.isEmpty())
        }

    @Test
    fun declinedPairingCanBeRetriedOnTheSameSession() =
        runTest {
            val session = FakeRemoteSignerSession()
            val flow = pairing(FakeRemoteSignerHost(session))
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()
            flow.approve()
            runCurrent()

            session.connectReply.completeExceptionally(Nip46ErrorFfi.Rejected())
            runCurrent()
            assertEquals(RemoteSignerStep.Failed(RemoteSignerFailure.Declined), flow.step)

            session.connectReply = kotlinx.coroutines.CompletableDeferred()
            flow.retry()
            runCurrent()
            assertEquals(2, session.connects)
            session.connectReply.complete(ACCOUNT)
            runCurrent()
            assertEquals(RemoteSignerStep.Done, flow.step)
        }

    @Test
    fun repairRefusesASignerForAnotherAccount() =
        runTest {
            val session = FakeRemoteSignerSession()
            val host = FakeRemoteSignerHost(session)
            val flow = pairing(host, expected = ACCOUNT)
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()
            flow.approve()
            runCurrent()

            session.connectReply.complete("ffff")
            runCurrent()
            assertEquals(RemoteSignerStep.Failed(RemoteSignerFailure.WrongAccount), flow.step)
            assertTrue("a mismatched signer never reaches the account", host.finished.isEmpty())
            flow.retry()
            assertEquals("a wrong account starts over instead of retrying", RemoteSignerStep.EnterLink, flow.step)
            assertTrue("start over clears the previous bunker link", flow.link.text.isEmpty())
            assertTrue(session.cancelled)
        }

    @Test
    fun cancellingDuringPairingDiscardsTheSessionAndIgnoresItsLateReply() =
        runTest {
            val session = FakeRemoteSignerSession()
            val host = FakeRemoteSignerHost(session)
            val flow = pairing(host)
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()
            flow.approve()
            runCurrent()

            assertTrue(flow.back())
            assertTrue(session.cancelled)
            assertEquals(RemoteSignerStep.EnterLink, flow.step)
            runCurrent()
            assertEquals("a stale reply cannot revive the flow", RemoteSignerStep.EnterLink, flow.step)
            assertTrue(host.finished.isEmpty())
        }

    @Test
    fun changedPermissionsReturnToReview() =
        runTest {
            val session = FakeRemoteSignerSession()
            val flow = pairing(FakeRemoteSignerHost(session))
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()
            flow.approve()
            runCurrent()

            session.connectReply.completeExceptionally(Nip46ErrorFfi.PermissionApprovalRequired())
            runCurrent()
            assertTrue(flow.step is RemoteSignerStep.Review)
            assertEquals(2, session.reviewed)
        }

    @Test
    fun loginFailureIsRetryableAndKeepsTheSession() =
        runTest {
            val session = FakeRemoteSignerSession()
            val host = FakeRemoteSignerHost(session).apply { finishError = IllegalStateException("relay down") }
            val flow = pairing(host)
            flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
            runCurrent()
            flow.approve()
            runCurrent()
            session.connectReply.complete(ACCOUNT)
            runCurrent()

            assertEquals(RemoteSignerStep.Failed(RemoteSignerFailure.SignInFailed), flow.step)
            flow.dispose()
            assertFalse("a session that may belong to the account is never cancelled", session.cancelled)
        }

    @Test
    fun interruptedHandoffAsksForAFreshLinkInsteadOfStayingBusy() =
        runTest {
            val session = FakeRemoteSignerSession()
            val retried = FakeRemoteSignerSession()
            val host = FakeRemoteSignerHost(session, retried)
            host.finishError = CancellationException("runtime replaced")
            val flow = pairing(host)
            connect(flow, session)
            session.connectReply.complete(ACCOUNT)
            runCurrent()

            assertEquals(RemoteSignerStep.Failed(RemoteSignerFailure.SignInFailed), flow.step)
            assertFalse(flow.busy)
            assertFalse(session.cancelled)
            flow.retry()
            runCurrent()
            assertEquals("a used link's secret is spent", RemoteSignerStep.EnterLink, flow.step)
            assertEquals("no session reuses the spent link", 1, host.configs.size)
            assertTrue("the spent link is cleared", flow.link.text.isEmpty())
        }

    @Test
    fun connectionCodeRetryRecreatesASessionThatFailedToStart() =
        runTest {
            val host = FakeRemoteSignerHost(FakeRemoteSignerSession()).apply { failNextSession = true }
            val flow = pairing(host)
            flow.choose(RemoteSignerMethod.ConnectionCode)
            runCurrent()
            assertEquals(RemoteSignerStep.Failed(RemoteSignerFailure.Unreachable), flow.step)

            flow.retry()
            runCurrent()
            assertTrue(flow.step is RemoteSignerStep.Review)
        }

    @Test
    fun approvalPromptClearsWhenTheSignerLeavesApproval() =
        runTest {
            val session = FakeRemoteSignerSession()
            val flow = pairing(FakeRemoteSignerHost(session))
            connect(flow, session)
            session.status = approvalStatus("https://signer.example/a")
            advanceTimeBy(600)
            runCurrent()
            assertTrue(flow.step is RemoteSignerStep.Approval)

            session.status = Nip46StatusFfi(Nip46StateFfi.CONNECTING, null, null, null, 1u)
            advanceTimeBy(600)
            runCurrent()
            assertEquals(RemoteSignerStep.Connecting, flow.step)
            assertTrue("nothing was acknowledged", session.completedAuth.isEmpty())
        }

    private fun approvalStatus(url: String) = Nip46StatusFfi(Nip46StateFfi.APPROVAL, null, url, "req-1", 1u)

    private fun TestScope.connect(
        flow: RemoteSignerPairing,
        session: FakeRemoteSignerSession,
    ) {
        flow.choose(RemoteSignerMethod.BunkerLink, BUNKER)
        flow.submitLink()
        runCurrent()
        flow.approve()
        runCurrent()
        assertEquals(0, session.completedAuth.size)
    }
}
