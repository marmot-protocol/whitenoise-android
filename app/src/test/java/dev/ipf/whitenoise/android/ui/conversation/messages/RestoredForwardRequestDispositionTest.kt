package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.state.PendingForwardRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Restoration decisions for a persisted forward request: requests belonging to
 * another conversation or source owner are ignored, and requests whose bound
 * owners can no longer sign are discarded — never silently re-owned by the
 * source, active, or fallback account.
 */
class RestoredForwardRequestDispositionTest {
    private fun request(
        origin: String = ORIGIN,
        source: String = "account-a",
        destination: String? = "account-b",
    ) = PendingForwardRequest(
        requestId = "request-1",
        sourceAccountRef = source,
        originGroupIdHex = origin,
        payloads =
            listOf(
                ForwardMessagePayload.Text(
                    sourceGroupIdHex = origin,
                    sourceMessageIdHex = "01".repeat(32),
                    text = "body",
                ),
            ),
        destinationAccountRef = destination,
        selectedGroupIds = listOf("11".repeat(32)),
    )

    private fun disposition(
        request: PendingForwardRequest,
        boundGroupIdHex: String = ORIGIN,
        boundAccountRef: String? = "account-a",
        signedIn: Set<String> = setOf("account-a", "account-b"),
    ) = restoredForwardRequestDisposition(
        request = request,
        boundGroupIdHex = boundGroupIdHex,
        boundAccountRef = boundAccountRef,
        isOwnerSignedIn = signedIn::contains,
    )

    /** A fully valid request restores, including one with no destination chosen yet. */
    @Test
    fun validRequestsRestore() {
        assertEquals(RestoredForwardDisposition.Restore, disposition(request()))
        assertEquals(RestoredForwardDisposition.Restore, disposition(request(destination = null)))
    }

    /** A request for another conversation or another source owner is left alone. */
    @Test
    fun foreignConversationOrOwnerIsIgnored() {
        assertEquals(
            RestoredForwardDisposition.Ignore,
            disposition(request(), boundGroupIdHex = "ff".repeat(32)),
        )
        assertEquals(
            RestoredForwardDisposition.Ignore,
            disposition(request(), boundAccountRef = "account-z"),
        )
    }

    /** A signed-out source owner discards the request instead of restoring it. */
    @Test
    fun signedOutSourceIsDiscarded() {
        assertEquals(
            RestoredForwardDisposition.Discard,
            disposition(request(), signedIn = setOf("account-b")),
        )
    }

    /** A signed-out chosen destination discards the request rather than re-owning it. */
    @Test
    fun signedOutDestinationIsDiscardedNotSubstituted() {
        assertEquals(
            RestoredForwardDisposition.Discard,
            disposition(request(), signedIn = setOf("account-a")),
        )
    }

    /** Origin matching is case-insensitive like the rest of the group-id handling. */
    @Test
    fun originMatchIsCaseInsensitive() {
        assertEquals(
            RestoredForwardDisposition.Restore,
            disposition(request(origin = ORIGIN.uppercase())),
        )
    }

    private companion object {
        val ORIGIN = "aa".repeat(32)
    }
}
