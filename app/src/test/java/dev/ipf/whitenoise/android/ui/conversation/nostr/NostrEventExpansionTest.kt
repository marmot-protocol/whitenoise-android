package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * An article has one date: its signed publication time when the tag holds a plausible one, otherwise the event
     * time. A millisecond value, a time later than the event allows or an unreadable tag falls back, and the
     * publication time is never repeated as a second, unlabelled metadata date (#3317).
     */
    @Test
    fun anArticleCarriesOneDatePreferringItsPublicationTime() {
        val created = 1_700_000_000L
        val published = article("1600038000", created)
        assertEquals(1_600_038_000L, published.publishedAt)
        assertEquals(1_600_038_000L, published.displayEpochSeconds())
        assertEquals(emptyList<String>(), published.metadata)

        assertEquals("the same instant is kept", created, article("$created", created).publishedAt)
        val skewed = article("${created + 3_600}", created)
        assertEquals("an hour of clock skew is kept", created + 3_600, skewed.publishedAt)

        listOf(
            null,
            "",
            "soon",
            "0",
            "-5",
            "99999999999999999999",
            "99999999999999999",
            "1700000000000",
            "${created + 2 * 86_400}",
        ).forEach { raw ->
            val card = article(raw, created)
            assertNull("published_at=$raw", card.publishedAt)
            assertEquals(created, card.displayEpochSeconds())
            assertEquals(emptyList<String>(), card.metadata)
        }
        val note = event(1, "Note", listOf(listOf("published_at", "1600038000")), createdAt = created).toCardModel()
        assertNull("only an article has a publication time", note.publishedAt)
    }

    /** An article created at [createdAt] whose `published_at` tag holds [publishedAt], or has no such tag. */
    private fun article(
        publishedAt: String?,
        createdAt: Long,
    ): NostrEventCardModel {
        val tags = publishedAt?.let { listOf(listOf("published_at", it)) }.orEmpty()
        return event(30023, "Body", tags, createdAt).toCardModel()
    }

    /** An event MDK rejects, for example for a bad signature, settles as Invalid and never opens the reader. */
    @Test
    fun anInvalidSignatureNeverOpensTheFullscreenReader() =
        runTest {
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { null } },
                    refresh = { NostrEventCardState.Invalid },
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

    /** A signed-looking event of [kind] with [content] and [tags], created at [createdAt]. */
    private fun event(
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList(),
        createdAt: Long = 1,
    ) = NostrEvent(
        id = ID,
        pubkey = "a".repeat(64),
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    private companion object {
        const val ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
