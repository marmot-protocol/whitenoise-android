package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Git cards offer only shell-safe clone addresses and parse format-patch headers and link tags robustly. */
class NostrGitCardParsingTest {
    private val id = "a".repeat(64)

    /**
     * A clone address that a shell would expand, or that git would read as an option, is never offered for Copy.
     * An https one stays listed only so the reader can open it behind a confirmation.
     */
    @Test
    fun cloneAddressesAShellWouldExpandAreNotCopyable() {
        val safe =
            listOf(
                "ssh://git@example.org/team/parser.git",
                "https://example.org/parser.git",
                "git@example.org:team/parser.git",
                "nostr://alice@example.org/relay.example.org/parser",
            )
        val openableOnly =
            listOf(
                "https://example.org/\$(id)",
                "https://example.org/a;touch",
                "https://example.org/repo?raw",
                "https://[2001:db8::1]/repo.git",
            )
        val dropped =
            listOf(
                "ssh://git@example.org/x;id",
                "ssh://git@example.org/a\$IFS",
                "git://example.org/x?y",
                "nostr://alice@example.org/relay.example.org/x#y",
                "ssh://-oProxyCommand@example.org/x",
                "nostr://-alice@example.org/x",
                "-oProxyCommand@example.org:x",
                "wss://relay.example.org/?a=\$(id)",
                "ssh://git@[2001:db8::1]/repo.git",
            )
        val tags = listOf(listOf("clone") + dropped + openableOnly + safe)
        val links = event(kind = 30617, tags = tags).toCardModel().gitLinks
        assertEquals(safe + openableOnly, links)
        assertEquals(safe, links.filter(::isShellSafeGitLink))
    }

    /** Eight clone mirrors still leave a slot for the repository's web link. */
    @Test
    fun cloneMirrorsLeaveASlotForTheWebLink() {
        val clones = listOf("clone") + (1..8).map { "https://mirror$it.example.org/parser.git" }
        val web = "https://example.org/parser"
        val links = event(kind = 30617, tags = listOf(listOf("web", web), clones)).toCardModel().gitLinks
        assertEquals(clones.drop(1).take(7) + web, links)
    }

    /** A web link the reader can neither copy nor open never takes the slot a usable web link needs. */
    @Test
    fun unactionableWebLinkCannotTakeTheWebSlot() {
        val clones = listOf("clone") + (1..7).map { "https://mirror$it.example.org/parser.git" }
        // A query rules out Copy, and the out-of-range port rules out Open.
        val unactionable = "https://example.org:70000/parser?tab=readme"
        val web = "https://example.org/parser?tab=readme"
        val tags = listOf(clones, listOf("web", unactionable, web))
        val links = event(kind = 30617, tags = tags).toCardModel().gitLinks
        assertEquals(clones.drop(1) + web, links)
    }

    /** Without a web link, eight clone mirrors keep every slot ahead of the relays. */
    @Test
    fun withoutAWebLinkClonesFillEverySlotBeforeRelays() {
        val clones = listOf("clone") + (1..8).map { "https://mirror$it.example.org/parser.git" }
        val relays = listOf("relays", "wss://relay.example.org")
        val links = event(kind = 30617, tags = listOf(relays, clones)).toCardModel().gitLinks
        assertEquals(clones.drop(1), links)
    }

    /** Open-only clone mirrors cannot push a copyable clone address out of the link cap. */
    @Test
    fun copyableCloneAddressesLeadOpenOnlyMirrors() {
        val mirrors = (1..7).map { "https://mirror$it.example.org/parser.git?ref=main" }
        val ssh = "ssh://git@example.org/parser.git"
        val links = event(kind = 30617, tags = listOf(listOf("clone") + mirrors + ssh)).toCardModel().gitLinks
        assertEquals(ssh, links.first())
    }

    /** Only the first 32 values of a tag are considered, so a crafted list of thousands costs a fixed amount. */
    @Test
    fun onlyTheFirstValuesOfATagAreConsidered() {
        val invalid = (1..32).map { "javascript:alert($it)" }
        val late = "https://example.org/late.git"
        val links = event(kind = 30617, tags = listOf(listOf("clone") + invalid + late)).toCardModel().gitLinks
        assertEquals(emptyList<String>(), links)
        val early = event(kind = 30617, tags = listOf(listOf("clone") + invalid.drop(1) + late)).toCardModel()
        assertEquals(listOf(late), early.gitLinks)
    }

    /** A whitespace-only separator line ends the headers, so the first paragraph is the summary. */
    @Test
    fun whitespaceOnlySeparatorKeepsTheFirstParagraph() {
        assertPatch("Subject: [PATCH] Fix\n \nFirst paragraph.\n\nSecond.", "Fix", "First paragraph.\n\nSecond.")
    }

    /** An LF followed by a CRLF ends the headers at that blank line, not at a later one. */
    @Test
    fun mixedLineEndingSeparatorKeepsTheFirstParagraph() {
        val body = "First paragraph.\r\n\r\nSecond."
        assertPatch("Subject: [PATCH] Fix\n\r\n$body", "Fix", body)
    }

    /** Without another blank line, the summary is still the body rather than the raw headers. */
    @Test
    fun whitespaceOnlySeparatorWithNoLaterBlankLineStillFindsTheBody() {
        assertPatch("From abc\nSubject: [PATCH] Fix\n\t\nBody only", "Fix", "Body only")
    }

    /** A subject folded straight after its colon still loses its `[PATCH n/m]` tag. */
    @Test
    fun subjectFoldedAfterTheColonDropsItsPatchTag() {
        assertPatch("Subject:\n [PATCH 1/2] Fix parser\n\nBody", "Fix parser", "Body")
    }

    /** A long relay list placed first cannot push the clone and web links past the link cap. */
    @Test
    fun relaysCannotCrowdOutCloneAndWebLinks() {
        val relays = listOf("relays") + (1..10).map { "wss://relay$it.example.org" }
        val clone = "https://example.org/parser.git"
        val web = "https://example.org/parser"
        val tags = listOf(relays, listOf("clone", clone), listOf("web", web))
        val links = event(kind = 30617, tags = tags).toCardModel().gitLinks
        assertEquals(listOf(clone, web) + relays.drop(1).take(6), links)
    }

    /** A comment whose coordinate names the same repository as its context shows that name once. */
    @Test
    fun commentRepositoryContextIsShownOnce() {
        val comment =
            event(
                kind = 1111,
                tags = listOf(listOf("K", "1617"), listOf("E", id), listOf("a", "30617:$id:parser")),
            ).toCardModel()
        assertEquals(listOf("parser"), comment.previewMetadata())
    }

    /** A ref whose target is only control characters is dropped instead of leaving a dangling separator. */
    @Test
    fun refWithAnUnprintableTargetIsDropped() {
        val tags = listOf(listOf("refs/heads/bad", "\u0001\u0002"), listOf("refs/heads/main", "abc"))
        val refs = event(kind = 30618, tags = tags).toCardModel()
        assertEquals(listOf("refs/heads/main · abc"), refs.metadata)
    }

    /** Checks a kind 1617 patch's card title and summary. */
    private fun assertPatch(
        content: String,
        title: String,
        summary: String,
    ) {
        val card = event(kind = 1617, content = content).toCardModel()
        assertEquals(title, card.title)
        assertEquals(summary, card.summary)
    }

    /** Builds a signed-looking event fixture. */
    private fun event(
        kind: Int,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = "c".repeat(64),
        createdAt = 1,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )
}
