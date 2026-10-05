package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NostrEventExpansionTest {
    @Test
    fun supportedKindsPreserveContentBeyondTheCompactExcerpt() {
        val body = "Complete referenced event.\n".repeat(100)
        listOf(1, 30023, 21, 22, 34235, 34236, 1063, 30063, 42).forEach { kind ->
            val card = event(kind, body).toCardModel()
            assertEquals("Kind $kind must remain readable in fullscreen", body.trim(), card.readerBody)
        }
    }

    @Test
    fun releaseReaderIncludesTheCompleteChangelog() {
        val changelog = "Fixes and improvements.\n".repeat(100).trim()
        val card = event(30063, "Release description", listOf(listOf("changelog", changelog))).toCardModel()
        assertEquals("Release description\n\n$changelog", card.readerBody)
    }

    @Test
    fun anInvalidSignatureNeverOpensTheFullscreenReader() =
        runTest {
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, _ -> listOf(event(1, "Signed note")) },
                    verifyEvent = { false },
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            val constrained =
                NostrEventReference.Event(
                    eventIdHex = ID,
                    authorPubkeyHex = "a".repeat(64),
                    kind = 1u,
                )
            val state = resolver.state(constrained)
            advanceUntilIdle()
            assertEquals(NostrEventCardState.Invalid, state.value)
            assertFalse(state.value is NostrEventCardState.Loaded)
            resolver.close()
        }

    private fun event(
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = ID,
        pubkey = "a".repeat(64),
        createdAt = 1,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    private companion object {
        const val ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
