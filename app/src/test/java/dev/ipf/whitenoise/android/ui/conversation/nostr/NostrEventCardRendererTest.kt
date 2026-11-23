package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NostrEventCardRendererTest {
    /** Hostile event metadata is bounded and each kind falls back to its typed card. */
    @Test
    fun rendererRegistryBoundsHostileMetadataAndUsesTypedFallbacks() {
        val article =
            event(
                id = "a".repeat(64),
                kind = 30_023,
                tags = listOf(listOf("title", "T".repeat(400)), listOf("summary", "S".repeat(800))),
                content = "# Full article\n\nReadable body",
            ).toCardModel()
        val video =
            event(
                "b".repeat(64),
                kind = 34_235,
                tags =
                    listOf(
                        listOf(
                            "imeta",
                            "url https://cdn.example/video.mp4",
                            "m video/mp4",
                            "duration 125",
                            "dim 1920x1080",
                        ),
                    ),
            ).toCardModel()
        val release = event("c".repeat(64), kind = 30_063, tags = listOf(listOf("version", "2026.8.1"))).toCardModel()
        val file =
            event(
                "d".repeat(64),
                kind = 1_063,
                tags = listOf(listOf("m", "audio/ogg"), listOf("size", "2048")),
            ).toCardModel()
        val generic = event("e".repeat(64), kind = 7_777, content = "body").toCardModel()

        assertEquals(NostrEventCardKind.Article, article.kind)
        assertEquals(160, article.title?.length)
        assertEquals(420, article.summary?.length)
        assertEquals("# Full article\n\nReadable body", article.readerBody)
        assertEquals(listOf("2:05", "1920x1080"), video.metadata)
        assertEquals("https://cdn.example/video.mp4", video.mediaUrl)
        assertEquals("video/mp4", video.mediaMimeType)
        assertEquals(listOf("2026.8.1"), release.metadata)
        assertEquals(listOf("audio/ogg", "2.0 KB"), file.metadata)
        assertEquals(NostrEventCardKind.Generic, generic.kind)
        assertEquals("body", generic.summary)
    }

    /** Verifies every supported event kind retains its complete accepted body. */
    @Test
    fun supportedKindsRetainTheFullReaderBody() {
        val fullNote = "n".repeat(70_000)
        val note = event("a".repeat(64), kind = 1, content = fullNote).toCardModel()
        val article = event("b".repeat(64), kind = 30_023, content = "Full article").toCardModel()
        val nonReaders =
            listOf(
                event("c".repeat(64), kind = 34_235, content = "Video").toCardModel(),
                event("d".repeat(64), kind = 30_063, content = "Release").toCardModel(),
                event("e".repeat(64), kind = 1_063, content = "File").toCardModel(),
                event("f".repeat(64), kind = 7_777, content = "Generic").toCardModel(),
            )

        assertEquals("n".repeat(420), note.summary)
        assertEquals(fullNote, note.readerBody)
        assertEquals("Full article", article.readerBody)
        assertEquals(listOf("Video", "Release", "File", "Generic"), nonReaders.map { it.readerBody })
    }

    @Test
    fun gitKindsRetainIdentityAndBoundUntrustedContext() {
        val id = "a".repeat(64)
        val kinds = listOf(30617, 30618, 1617, 1618, 1619, 1621, 1630, 1631, 1632, 1633, 10317)
        kinds.forEach { kind -> assertEquals(NostrEventCardKind.Git, event(id, kind = kind).toCardModel().kind) }
        val pullRequest =
            event(
                id,
                kind = 1618,
                tags =
                    listOf(
                        listOf("subject", "Fix parser"),
                        listOf("a", "30617:$id:parser"),
                        listOf("branch-name", "fix-parser"),
                        listOf(
                            "clone",
                            "https://example.org/parser.git",
                            "nostr://bob@example.org/parser",
                            "javascript:hello",
                        ),
                    ),
                content = "# Full description",
            ).toCardModel()
        assertEquals("Fix parser", pullRequest.title)
        assertEquals("# Full description", pullRequest.readerBody)
        assertEquals(listOf("parser", "branch fix-parser"), pullRequest.metadata)
        assertEquals(listOf("https://example.org/parser.git", "nostr://bob@example.org/parser"), pullRequest.gitLinks)

        val patch = event(id, kind = 1617, content = "diff --git a/file b/file\n-# old\n+# new").toCardModel()
        assertEquals("diff --git a/file b/file\n-# old\n+# new", patch.readerBody)
        assertEquals(true, patch.literalReaderBody)
        val formatted =
            event(
                id,
                kind = 1617,
                content =
                    "From 1234 Mon Sep 17 00:00:00 2001\r\nSubject: [PATCH 1/2] [parser] Fix parser\r\n" +
                        " crash\r\n\r\nExplain it.\n---\n diff",
            ).toCardModel()
        assertEquals("[parser] Fix parser crash", formatted.title)
        assertEquals("Explain it.\n---\n diff", formatted.summary)
        assertEquals(NostrEventCardKind.Generic, event(id, kind = 1111).toCardModel().kind)
        val malformed = event(id, kind = 1111, tags = listOf(listOf("K", "1618"), listOf("E", "wrong"))).toCardModel()
        assertEquals(NostrEventCardKind.Generic, malformed.kind)
        val comment = event(id, kind = 1111, tags = listOf(listOf("K", "1618"), listOf("E", id))).toCardModel()
        assertEquals(NostrEventCardKind.Git, comment.kind)
        assertEquals(listOf(id), comment.metadata)
    }

    @Test
    fun repositoryAnnouncementKeepsItsIdentifierBesideAName() {
        val id = "a".repeat(64)
        val named =
            event(id, kind = 30617, tags = listOf(listOf("d", "parser-id"), listOf("name", "Parser"))).toCardModel()
        assertEquals("Parser", named.title)
        assertEquals(listOf("parser-id"), named.metadata)
        val unnamed = event(id, kind = 30617, tags = listOf(listOf("d", "parser-id"))).toCardModel()
        assertEquals("parser-id", unnamed.title)
        assertEquals(emptyList<String>(), unnamed.metadata)
    }

    @Test
    fun repositoryContextUsesTheFirstValidRepositoryCoordinate() {
        val id = "a".repeat(64)
        val tags =
            listOf(
                listOf("a", "30023:$id:article"),
                listOf("a", "30617:short:bad"),
                listOf("a", "30617:$id:"),
                listOf("a", "30617:$id:parser"),
                listOf("a", "30617:$id:other"),
            )
        assertEquals(listOf("parser"), event(id, kind = 1621, tags = tags).toCardModel().metadata)
        val none = event(id, kind = 1621, tags = tags.take(3)).toCardModel()
        assertEquals(emptyList<String>(), none.metadata)
    }

    @Test
    fun sshAndScpCloneAddressesAreKeptForExactCopyOnly() {
        val id = "a".repeat(64)
        val valid = listOf("ssh://git@example.org/team/parser.git", "git@example.org:team/parser.git")
        val rejected =
            listOf(
                "ssh://user:secret@example.org/parser.git",
                "git@-evil.example:parser.git",
                "git@example.org:/etc",
                "git@example.org:a b",
                "ssh://@example.org/p",
            )
        val links =
            event(id, kind = 30617, tags = listOf(listOf("clone") + valid + rejected)).toCardModel().gitLinks
        assertEquals(valid, links)
    }

    @Test
    fun remainingGitKindsMapIdentityAndContext() {
        val id = "a".repeat(64)
        val repo = "30617:$id:parser"
        val refs =
            event(
                id,
                kind = 30618,
                tags =
                    listOf(
                        listOf("HEAD", "ref: refs/heads/main"),
                        listOf("refs/heads/main", "abc"),
                        listOf("a", repo),
                    ),
            ).toCardModel()
        assertEquals(listOf("parser", "ref: refs/heads/main", "refs/heads/main · abc"), refs.metadata)
        val update =
            event(
                id,
                kind = 1619,
                tags = listOf(listOf("a", repo), listOf("c", "abc"), listOf("E", "b".repeat(64))),
            ).toCardModel()
        assertEquals(listOf("parser", "commit abc", "b".repeat(64)), update.metadata)
        val rootId = "d".repeat(64)
        listOf(1630, 1631, 1632, 1633).forEach { kind ->
            val status = event(id, kind = kind, tags = listOf(listOf("e", rootId, "", "root"))).toCardModel()
            assertEquals(listOf(rootId), status.metadata)
        }
        val services =
            event(id, kind = 10317, tags = listOf(listOf("g", "https://git.example.org", "javascript:x"))).toCardModel()
        assertEquals(listOf("https://git.example.org"), services.gitLinks)
        val issue = event(id, kind = 1621, tags = listOf(listOf("subject", "Crash"), listOf("a", repo))).toCardModel()
        assertEquals("Crash", issue.title)
        assertEquals(listOf("parser"), issue.metadata)
    }

    @Test
    fun gitContextIsBounded() {
        val id = "a".repeat(64)
        val refTags = (1..40).map { listOf("refs/heads/b$it", "c$it") }
        assertEquals(8, event(id, kind = 30618, tags = refTags).toCardModel().metadata.size)
        val clones = (1..40).map { "https://example.org/r$it.git" }
        assertEquals(8, event(id, kind = 30617, tags = listOf(listOf("clone") + clones)).toCardModel().gitLinks.size)
        val long = "https://example.org/" + "x".repeat(600)
        val longLinks = event(id, kind = 30617, tags = listOf(listOf("clone", long))).toCardModel().gitLinks
        assertEquals(emptyList<String>(), longLinks)
        val bidi = "https://example.org/a\u202Eb"
        val bidiLinks = event(id, kind = 30617, tags = listOf(listOf("clone", bidi))).toCardModel().gitLinks
        assertEquals(emptyList<String>(), bidiLinks)
        val name = event(id, kind = 30617, tags = listOf(listOf("name", "ok\u0007name"))).toCardModel().title
        assertEquals("okname", name)
        val longName = event(id, kind = 30617, tags = listOf(listOf("name", "n".repeat(5_000)))).toCardModel().title
        assertEquals(true, longName!!.length < 1_000)
    }

    /** Field and excerpt bounds never split a Unicode surrogate pair. */
    @Test
    fun textBoundsPreserveCompleteUnicodeCodePoints() {
        val emoji = "\uD83D\uDE00"
        val boundedField = ("a".repeat(159) + emoji + "tail").safeField()
        val boundedExcerpt =
            event(
                id = "a".repeat(64),
                content = "b".repeat(419) + emoji + "tail",
            ).toCardModel().summary

        assertEquals("a".repeat(159) + emoji, boundedField)
        assertEquals("b".repeat(419) + emoji, boundedExcerpt)
    }

    /** Video playback accepts only public HTTPS URLs on the default port without credentials. */
    @Test
    fun videoPlaybackUrlAcceptsOnlyPublicDefaultPortHttps() {
        assertEquals("https://cdn.example/video.mp4", safeNostrMediaUrl(" https://cdn.example/video.mp4 "))
        assertEquals(null, safeNostrMediaUrl("http://cdn.example/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://user:pass@cdn.example/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://cdn.example:8443/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://127.0.0.1/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://[::1]/video.mp4"))
    }

    /** HLS playlists are never offered for in-app playback; a later file variant of the same video is. */
    @Test
    fun hlsPlaylistsAreSkippedForTheNextFileVariant() {
        fun video(vararg variants: List<String>) =
            event(id = "a".repeat(64), kind = 34_236, tags = variants.map { listOf("imeta") + it }).toCardModel()

        val typed = video(listOf("url https://media.example/watch/stream", "m application/vnd.apple.mpegurl"))
        assertNull(typed.mediaUrl)
        val untyped = video(listOf("url https://media.example/watch/index.M3U8"))
        assertNull(untyped.mediaUrl)
        val mislabeled = video(listOf("url https://media.example/watch/index.m3u8", "m video/mp4"))
        assertNull(mislabeled.mediaUrl)

        val fallback =
            video(
                listOf("url https://media.example/watch/stream", "m application/x-mpegurl"),
                listOf("url https://media.example/watch/clip.mp4", "m video/mp4"),
            )
        assertEquals("https://media.example/watch/clip.mp4", fallback.mediaUrl)
        assertEquals("video/mp4", fallback.mediaMimeType)
    }

    /** Every initially supported media kind maps to its typed card renderer. */
    @Test
    fun everyInitialMediaKindUsesTheTypedRenderer() {
        listOf(21, 22, 34_235, 34_236).forEach { kind ->
            assertEquals(NostrEventCardKind.Video, event("a".repeat(64), kind = kind).toCardModel().kind)
        }
        assertEquals(NostrEventCardKind.Note, event("b".repeat(64), kind = 1).toCardModel().kind)
        assertEquals(NostrEventCardKind.Article, event("c".repeat(64), kind = 30_023).toCardModel().kind)
        assertEquals(NostrEventCardKind.Release, event("d".repeat(64), kind = 30_063).toCardModel().kind)
        assertEquals(NostrEventCardKind.File, event("e".repeat(64), kind = 1_063).toCardModel().kind)
    }

    /** Kind 1 notes expose declared video media while remaining notes. */
    @Test
    fun kindOneNotesExposeDeclaredVideoWithoutChangingTheirKind() {
        val note =
            event(
                id = "a".repeat(64),
                kind = 1,
                tags = listOf(listOf("imeta", "url https://media.example/opaque-resource", "m video/mp4")),
            ).toCardModel()
        assertEquals(NostrEventCardKind.Note, note.kind)
        assertEquals("https://media.example/opaque-resource", note.mediaUrl)
        val untyped =
            event(
                id = "b".repeat(64),
                kind = 1,
                tags = listOf(listOf("imeta", "url https://images.example/photo")),
            ).toCardModel()
        assertEquals(null, untyped.mediaUrl)
    }

    /** Builds a Nostr event fixture with defaults for every field but [id]. */
    private fun event(
        id: String,
        pubkey: String = "c".repeat(64),
        kind: Int = 1,
        createdAt: Long = 1,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )
}
