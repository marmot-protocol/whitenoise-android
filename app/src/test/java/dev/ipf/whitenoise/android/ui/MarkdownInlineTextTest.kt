package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MarkdownInlineTextTest {
    private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace)
    private val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline)

    private fun build(inlines: List<MarkdownInlineFfi>) = markdownInlinesToAnnotatedString(inlines, codeStyle, linkStyle)

    @Test
    fun plainTextAndBreaksConcatenate() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Text("first"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Text("second"),
                    MarkdownInlineFfi.HardBreak,
                    MarkdownInlineFfi.Text("third"),
                ),
            )
        assertEquals("first\nsecond\nthird", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun inlineRenderingStopsAtSiblingBreadthCap() {
        val annotated =
            build(
                List(MARKDOWN_MAX_CONTAINER_SIBLINGS) { MarkdownInlineFfi.Text("x") } +
                    MarkdownInlineFfi.Text("never"),
            )

        assertEquals("x".repeat(MARKDOWN_MAX_CONTAINER_SIBLINGS), annotated.text)
    }

    @Test
    fun plainTextStripsUnsafeBidiAndInvisibleControls() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Text("pay\u202Ecod.exe\u200B"),
                    MarkdownInlineFfi.SoftBreak,
                    MarkdownInlineFfi.Code("sha\u2066-256"),
                    MarkdownInlineFfi.Text(" "),
                    MarkdownInlineFfi.Autolink("https://example.com/\u202Egpj.exe", MarkdownAutolinkKindFfi.URI),
                ),
            )

        assertEquals("paycod.exe\nsha-256 https://example.com/gpj.exe", annotated.text)
    }

    @Test
    fun strongAppliesBoldOverItsRange() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Text("a "),
                    MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bold"))),
                ),
            )
        assertEquals("a bold", annotated.text)
        val range = annotated.spanStyles.single()
        assertEquals(FontWeight.Bold, range.item.fontWeight)
        assertEquals(2, range.start)
        assertEquals(6, range.end)
    }

    @Test
    fun emphasisNestedInsideStrongStacksBothStyles() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Strong(
                        listOf(
                            MarkdownInlineFfi.Text("very "),
                            MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("nested"))),
                        ),
                    ),
                ),
            )
        assertEquals("very nested", annotated.text)
        val bold = annotated.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals(0, bold.start)
        assertEquals(11, bold.end)
        val italic = annotated.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals(5, italic.start)
        assertEquals(11, italic.end)
    }

    @Test
    fun strikethroughAppliesLineThrough() {
        val annotated = build(listOf(MarkdownInlineFfi.Strikethrough(listOf(MarkdownInlineFfi.Text("gone")))))
        assertEquals("gone", annotated.text)
        assertEquals(
            TextDecoration.LineThrough,
            annotated.spanStyles
                .single()
                .item.textDecoration,
        )
    }

    @Test
    fun inlineCodeUsesTheProvidedCodeStyle() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Text("run "),
                    MarkdownInlineFfi.Code("ls -la"),
                ),
            )
        assertEquals("run ls -la", annotated.text)
        val range = annotated.spanStyles.single()
        assertEquals(FontFamily.Monospace, range.item.fontFamily)
        assertEquals(4, range.start)
        assertEquals(10, range.end)
    }

    @Test
    fun httpLinkCarriesConfirmAnnotationAndLinkStyle() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Link(
                        dest = "https://example.com/page",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("example")),
                    ),
                ),
            )
        assertEquals("example", annotated.text)
        // An explicit [label](url) link routes the tap through a confirmation
        // (Clickable + confirm tag) that surfaces the real destination, since
        // the label is attacker-chosen and may not match the URL (#273).
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals(CONFIRM_LINK_TAG_PREFIX + "https://example.com/page", (link.item as LinkAnnotation.Clickable).tag)
        assertEquals(0, link.start)
        assertEquals(7, link.end)
    }

    @Test
    fun linkConfirmationDisplayStripsUnsafeCharactersAndCapsLength() {
        assertEquals("https://trusted.example/evil.exe", markdownSafeDisplayText("https://trusted.example/\u202Eevil.exe"))

        val long = "a".repeat(MARKDOWN_LINK_CONFIRM_DISPLAY_MAX_LENGTH + 5)
        assertEquals(MARKDOWN_LINK_CONFIRM_DISPLAY_MAX_LENGTH, markdownSafeDisplayText(long).length)
    }

    @Test
    fun nonHttpLinkRendersTextWithoutAnnotation() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Link(
                        dest = "javascript:alert(1)",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("sneaky")),
                    ),
                ),
            )
        assertEquals("sneaky", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun uriAutolinkIsTappable() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Autolink("https://example.com", MarkdownAutolinkKindFfi.URI),
                ),
            )
        assertEquals("https://example.com", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
        assertEquals(0, link.start)
        assertEquals(19, link.end)
    }

    @Test
    fun paddedUriAutolinkPreservesVisibleTextButTrimsAnnotation() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Autolink("  https://example.com  ", MarkdownAutolinkKindFfi.URI),
                ),
            )
        // The author's padding stays visible, but the stored destination is
        // trimmed so the gate, the annotation, and Uri.parse agree.
        assertEquals("  https://example.com  ", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun emailAutolinkOpensThroughMailto() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Autolink("user@example.com", MarkdownAutolinkKindFfi.EMAIL),
                ),
            )
        // Visible text stays the bare address; the annotation carries the
        // mailto: destination ACTION_VIEW needs.
        assertEquals("user@example.com", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals("mailto:user@example.com", (link.item as LinkAnnotation.Url).url)
    }

    @Test
    fun schemeAllowlistAdmitsExactlyTheExternalSchemes() {
        // Allowed, case-insensitively.
        assertTrue(isOpenableMarkdownLink("https://example.com"))
        assertTrue(isOpenableMarkdownLink("HTTP://EXAMPLE.COM"))
        assertTrue(isOpenableMarkdownLink("mailto:user@example.com"))
        assertTrue(isOpenableMarkdownLink("MAILTO:USER@EXAMPLE.COM"))
        // tel: and custom app schemes are not openable.
        assertFalse(isOpenableMarkdownLink("tel:+15551234567"))
        assertFalse(isOpenableMarkdownLink("whitenoise://invite/abc"))
        assertFalse(isOpenableMarkdownLink("whitenoise-staging://invite/abc"))
        assertFalse(isOpenableMarkdownLink("  tel:+15551234567  "))
        // Denied: script/data/file/ftp and anything unknown stays inert.
        assertFalse(isOpenableMarkdownLink("javascript:alert(1)"))
        assertFalse(isOpenableMarkdownLink("data:text/html;base64,PGI+"))
        assertFalse(isOpenableMarkdownLink("file:///etc/passwd"))
        assertFalse(isOpenableMarkdownLink("ftp://example.com/file"))
        assertFalse(isOpenableMarkdownLink("nostr:npub1abc"))
        assertFalse(isOpenableMarkdownLink("httpsx://example.com"))
        assertFalse(isOpenableMarkdownLink("intent://scan/#Intent;end"))
        assertFalse(isOpenableMarkdownLink("no-scheme-at-all"))
        assertFalse(isOpenableMarkdownLink(":missing-scheme"))
        assertFalse(isOpenableMarkdownLink(""))
    }

    @Test
    fun schemeAllowlistIsLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            assertTrue(isOpenableMarkdownLink("HTTPS://example.com"))
            assertTrue(isOpenableMarkdownLink("MAILTO:user@example.com"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun mailtoLinkCarriesAnnotationButTelStaysInert() {
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Link(
                        dest = "mailto:user@example.com",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("mail")),
                    ),
                    MarkdownInlineFfi.Text(" "),
                    MarkdownInlineFfi.Link(
                        dest = "tel:+15551234567",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("call")),
                    ),
                ),
            )
        assertEquals("mail call", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        // tel: is inert, so only mailto carries an annotation — and it routes
        // through the confirm-on-open path like any explicit link (#273).
        assertEquals(1, links.size)
        assertEquals(CONFIRM_LINK_TAG_PREFIX + "mailto:user@example.com", (links[0].item as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun whitespacePaddedUrlsAreNormalizedNotRejected() {
        // The gate accepts a padded http(s) URL…
        assertTrue(isOpenableMarkdownLink("  https://example.com"))
        // …but padding never launders a forbidden scheme through.
        assertFalse(isOpenableMarkdownLink(" javascript:alert(1)"))

        // The annotation must carry the TRIMMED destination: Uri.parse of a
        // padded string yields a null scheme, so storing the raw form would
        // pass the gate and then fail at launch.
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.Link(
                        dest = "  https://example.com  ",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("padded")),
                    ),
                ),
            )
        assertEquals("padded", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals(CONFIRM_LINK_TAG_PREFIX + "https://example.com", (link.item as LinkAnnotation.Clickable).tag)

        // A padded javascript: destination stays completely inert.
        val inert =
            build(
                listOf(
                    MarkdownInlineFfi.Link(
                        dest = " javascript:alert(1)",
                        title = null,
                        children = listOf(MarkdownInlineFfi.Text("sneaky")),
                    ),
                ),
            )
        assertTrue(inert.getLinkAnnotations(0, inert.length).isEmpty())
    }

    @Test
    fun shortenedBech32KeepsTwelvePlusSixAroundAnEllipsis() {
        val npub = "npub1" + "q".repeat(58)
        assertEquals("npub1qqqqqqq…qqqqqq", shortenedBech32(npub))
        // At or under the shortened length there is nothing to save — pass through.
        assertEquals("npub1qqqqqqqqqqqqqq", shortenedBech32("npub1qqqqqqqqqqqqqq"))
        assertEquals("note1abc", shortenedBech32("note1abc"))
        // One char over the pass-through budget shortens.
        assertEquals("npub1qqqqqqq…qqqqqq", shortenedBech32("npub1" + "q".repeat(15)))
    }

    @Test
    fun plaintextResolverHandlesNprofileNostrMentionsWithoutAtSigil() {
        val nprofile = "nprofile1" + "q".repeat(70)

        val resolved =
            resolveMentionsInPlaintext(
                "hello nostr:$nprofile!",
                resolver = { bech32 -> "Bob".takeIf { bech32 == nprofile } },
            )

        assertEquals("hello @Bob!", resolved)
    }

    @Test
    fun plaintextResolverFallsBackToShortenedNprofile() {
        val nprofile = "nprofile1" + "z".repeat(70)

        val resolved = resolveMentionsInPlaintext("hello nostr:$nprofile,", resolver = { null })

        assertEquals("hello @nprofile1zzz…zzzzzz,", resolved)
    }

    @Test
    fun plaintextResolverIgnoresNpubRelayHintSuffixesWithoutEatingFollowingWords() {
        val npub = "npub1" + "q".repeat(58)

        val resolved =
            resolveMentionsInPlaintext(
                "hello nostr:$npub?relay=wss://relay.example after",
                resolver = { bech32 -> "Alice".takeIf { bech32 == npub } },
            )

        assertEquals("hello @Alice after", resolved)
    }

    @Test
    fun plaintextResolverPreservesPunctuationAfterNpubRelayHintSuffixes() {
        val npub = "npub1" + "q".repeat(58)

        val resolved =
            resolveMentionsInPlaintext(
                "hello nostr:$npub?relay=wss://relay.example! next " +
                    "nostr:$npub?relay=wss://relay.example, ok",
                resolver = { bech32 -> "Alice".takeIf { bech32 == npub } },
            )

        assertEquals("hello @Alice! next @Alice, ok", resolved)
    }

    @Test
    fun plaintextResolverStripsUnsafeUnicodeAroundMentions() {
        val npub = "npub1" + "q".repeat(58)

        val resolved =
            resolveMentionsInPlaintext(
                "pay\u202E $100 nostr:$npub\u200B now",
                resolver = { bech32 -> "Alice".takeIf { bech32 == npub } },
            )

        assertEquals("pay $100 @Alice now", resolved)
    }

    @Test
    fun unresolvedMentionRendersShortenedBech32InCodeStyleWithProfileAnnotation() {
        val npub = "npub1" + "q".repeat(58)
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.NostrMention(
                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                    ),
                ),
            )
        assertEquals("@npub1qqqqqqq…qqqqqq", annotated.text)
        val code = annotated.spanStyles.single()
        assertEquals(FontFamily.Monospace, code.item.fontFamily)
        assertEquals(0, code.start)
        assertEquals(annotated.length, code.end)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        val clickable = link.item as LinkAnnotation.Clickable
        assertEquals(NOSTR_PROFILE_LINK_TAG_PREFIX + npub, clickable.tag)
        // The annotation must carry its own styles: a styles-less link is
        // painted in the theme's default link color, which vanishes on the
        // outgoing (primary-container) bubble.
        assertEquals(FontFamily.Monospace, clickable.styles?.style?.fontFamily)
    }

    @Test
    fun resolvedMentionRendersBoldDisplayName() {
        val npub = "npub1" + "q".repeat(58)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrMention(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = linkStyle,
                mentionDisplayName = { bech32 -> "Alice".takeIf { bech32 == npub } },
            )
        assertEquals("@Alice", annotated.text)
        val bold = annotated.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals(0, bold.start)
        assertEquals(6, bold.end)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        val clickable = link.item as LinkAnnotation.Clickable
        assertEquals(NOSTR_PROFILE_LINK_TAG_PREFIX + npub, clickable.tag)
        // Explicit annotation styles (see the unresolved-mention test): the
        // bold run must also ride the link itself so it inherits the visible
        // color, not the theme's default link color.
        assertEquals(FontWeight.Bold, clickable.styles?.style?.fontWeight)
    }

    @Test
    fun resolvedGroupMemberMentionKeepsTheAtPrefixAndMentionStyling() {
        val npub = "npub1" + "q".repeat(58)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrMention(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = linkStyle,
                mentionDisplayName = { "Alice" },
                isGroupMember = { bech32 -> bech32 == npub },
            )
        // A current member is addressed: the "@" mention treatment stays.
        assertEquals("@Alice", annotated.text)
        val bold = annotated.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals(NOSTR_PROFILE_LINK_TAG_PREFIX + npub, (link.item as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun customBubbleMentionOmitsDecorativeBackground() {
        val npub = "npub1" + "q".repeat(58)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrMention(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = SpanStyle(color = Color.Black),
                mentionDisplayName = { "Alice" },
                isGroupMember = { true },
                useDecorativeBackgrounds = false,
            )

        assertEquals(
            Color.Unspecified,
            annotated.spanStyles
                .single()
                .item.background,
        )
    }

    @Test
    fun resolvedNonMemberMentionDropsTheAtPrefixButStaysTappable() {
        val npub = "npub1" + "q".repeat(58)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrMention(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = linkStyle,
                mentionDisplayName = { "Alice" },
                // Resolves to a name, but the account is NOT in the active group.
                isGroupMember = { false },
            )
        // #1017: a non-member reference keeps the resolved name but NOT the "@";
        // it reads as an inline profile link, not a mention.
        assertEquals("Alice", annotated.text)
        // No bold mention treatment — the name is styled as the (link) reference.
        assertTrue(annotated.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
        // Still tappable through to the in-app profile sheet (#259 preserved).
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals(NOSTR_PROFILE_LINK_TAG_PREFIX + npub, (link.item as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun unresolvedNonMemberMentionDropsTheAtPrefixButStaysTappable() {
        val npub = "npub1" + "q".repeat(58)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrMention(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = linkStyle,
                mentionDisplayName = { null },
                isGroupMember = { false },
            )
        // The roster decision is independent of the profile cache: a known
        // non-member cache miss must not render a transient false @ mention.
        assertEquals("npub1qqqqqqq…qqqqqq", annotated.text)
        val code = annotated.spanStyles.single()
        assertEquals(FontFamily.Monospace, code.item.fontFamily)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals(NOSTR_PROFILE_LINK_TAG_PREFIX + npub, (link.item as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun nonMemberNprofileMentionAlsoDropsTheAtPrefix() {
        val nprofile = "nprofile1" + "q".repeat(60)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrMention(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, nprofile),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = linkStyle,
                mentionDisplayName = { "Bob" },
                isGroupMember = { false },
            )
        // Same non-member treatment for nprofile as for npub (#1017).
        assertEquals("Bob", annotated.text)
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        assertEquals(NOSTR_PROFILE_LINK_TAG_PREFIX + nprofile, (link.item as LinkAnnotation.Clickable).tag)
    }

    @Test
    fun resolvedMentionWithoutAMembershipResolverKeepsTheAtPrefix() {
        val npub = "npub1" + "q".repeat(58)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrMention(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = linkStyle,
                mentionDisplayName = { "Alice" },
                // No roster available (isGroupMember == null): keep pre-#1017
                // behavior so roster-less callers are unchanged.
            )
        assertEquals("@Alice", annotated.text)
    }

    @Test
    fun nostrUriRendersShortenedBech32WithoutAtSignAndIgnoresTheResolver() {
        val nprofile = "nprofile1" + "q".repeat(60)
        val annotated =
            markdownInlinesToAnnotatedString(
                inlines =
                    listOf(
                        MarkdownInlineFfi.NostrUri(
                            MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPROFILE, nprofile),
                        ),
                    ),
                codeStyle = codeStyle,
                linkStyle = linkStyle,
                mentionDisplayName = { "Alice" },
            )
        assertEquals("nprofile1qqq…qqqqqq", annotated.text)
        assertEquals(
            FontFamily.Monospace,
            annotated.spanStyles
                .single()
                .item.fontFamily,
        )
        // nprofile still routes to the profile callback, and the annotation
        // carries the monospace style explicitly (visible on both bubbles).
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        val clickable = link.item as LinkAnnotation.Clickable
        assertEquals(NOSTR_PROFILE_LINK_TAG_PREFIX + nprofile, clickable.tag)
        assertEquals(FontFamily.Monospace, clickable.styles?.style?.fontFamily)
    }

    @Test
    fun nonProfileNostrEntitiesAreStyledButInert() {
        val note = "note1" + "q".repeat(58)
        val nevent = "nevent1" + "q".repeat(60)
        val annotated =
            build(
                listOf(
                    MarkdownInlineFfi.NostrMention(
                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, note),
                    ),
                    MarkdownInlineFfi.Text(" "),
                    MarkdownInlineFfi.NostrUri(
                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NEVENT, nevent),
                    ),
                ),
            )
        assertEquals("@note1qqqqqqq…qqqqqq nevent1qqqqq…qqqqqq", annotated.text)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        // Both entities still get the monospace treatment.
        assertEquals(2, annotated.spanStyles.count { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun headingRampMapsSixDistinctTiersAllSemiBold() {
        val typography = Typography()
        val expectedSizes =
            mapOf(
                1 to typography.headlineSmall.fontSize,
                2 to typography.titleLarge.fontSize,
                3 to 20.sp,
                4 to 18.sp,
                5 to typography.bodyLarge.fontSize,
                6 to typography.bodyMedium.fontSize,
            )
        expectedSizes.forEach { (level, size) ->
            val style = markdownHeadingTextStyle(level, typography)
            assertEquals("level $level", size, style.fontSize)
            assertEquals("level $level", FontWeight.SemiBold, style.fontWeight)
        }
        // Six distinct, strictly descending sizes — every tier reads
        // differently in a bubble.
        val sizes = (1..6).map { markdownHeadingTextStyle(it, typography).fontSize.value }
        assertEquals(6, sizes.distinct().size)
        assertEquals(sizes.sortedDescending(), sizes)
        // Out-of-range levels clamp to the smallest tier.
        assertEquals(typography.bodyMedium.fontSize, markdownHeadingTextStyle(7, typography).fontSize)
    }

    @Test
    fun listMarkersFormatBulletAndOrderedKinds() {
        val bullet = MarkdownListKindFfi.Bullet(marker = "-")
        assertEquals("•", markdownListMarker(bullet, index = 0))
        assertEquals("•", markdownListMarker(bullet, index = 4))
        val ordered = MarkdownListKindFfi.Ordered(start = 3u, delimiter = ".")
        assertEquals("3.", markdownListMarker(ordered, index = 0))
        assertEquals("5.", markdownListMarker(ordered, index = 2))
        // start is FFI-supplied: UInt.MAX_VALUE must not wrap to a small
        // number — the marker math runs in Long.
        val huge = MarkdownListKindFfi.Ordered(start = UInt.MAX_VALUE, delimiter = ".")
        assertEquals("4294967295.", markdownListMarker(huge, index = 0))
        assertEquals("4294967296.", markdownListMarker(huge, index = 1))
    }
}
