package dev.ipf.whitenoise.android.ui.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMemberIdentitySearchTest {
    @Test
    fun supportedReferencesNormalizeToTheExistingMdkPublicIdentity() {
        listOf(
            MEMBER_NPUB,
            MEMBER_HEX.uppercase(),
            MEMBER_NPROFILE,
            "nostr:$MEMBER_NPUB",
            "nostr:$MEMBER_NPROFILE",
            "marmot://profile/$MEMBER_NPUB?from=qr",
            "whitenoise://profile/$MEMBER_NPUB",
            "whitenoise-dev://profile/$MEMBER_NPUB",
            "whitenoise-staging://profile/$MEMBER_NPUB",
            "https://whitenoise.chat/profile/$MEMBER_NPUB",
            "http://marmot.app/$MEMBER_NPUB",
        ).forEach { reference ->
            val raw = "  $reference \n"
            val input = GroupMemberIdentitySearch.decoderInput(raw)
            assertEquals(reference, MEMBER_HEX, validatedFixtureIdentity(input))
            assertTrue(GroupMemberIdentitySearch.matches(raw, MEMBER_HEX, MEMBER_HEX.uppercase(), ""))
            assertFalse(GroupMemberIdentitySearch.matches(raw, MEMBER_HEX, OTHER_HEX, "Alice"))
            assertEquals(reference, reference, GroupMemberIdentitySearch.clipboardInput(raw))
        }
    }

    @Test
    fun malformedPrivateAndEventReferencesNeverFallBackToNameMatching() {
        listOf(
            "npub1",
            MEMBER_NPUB.dropLast(1) + "q",
            "nprofile1",
            "nsec1private",
            "note1event",
            "nevent1event",
            "naddr1event",
            "nostr:nsec1private",
            "https://evil.example/$MEMBER_NPUB",
            "marmot://event/$MEMBER_NPUB",
            "ftp://profile/$MEMBER_NPUB",
            "https://user@whitenoise.chat/$MEMBER_NPUB",
            "https://whitenoise.chat/profile/$MEMBER_NPUB/extra",
        ).forEach { input ->
            val hex = validatedFixtureIdentity(GroupMemberIdentitySearch.decoderInput(input))
            assertNull(input, hex)
            assertFalse(GroupMemberIdentitySearch.matches(input, hex, MEMBER_HEX, input))
        }
        assertNull(GroupMemberIdentitySearch.clipboardInput("nsec1private"))
        assertNull(GroupMemberIdentitySearch.clipboardInput("nostr:note1event"))
    }

    @Test
    fun ordinaryNamesRetainSubstringSearchAndWhitespaceHandling() {
        listOf("Alice", "  ali  ", "BOB: WORK", "").forEach { input ->
            assertFalse(GroupMemberIdentitySearch.isIdentityQuery(input))
            assertTrue(GroupMemberIdentitySearch.matches(input, null, MEMBER_HEX, "Alice Bob: work"))
        }
        assertFalse(GroupMemberIdentitySearch.matches("unknown", null, MEMBER_HEX, "Alice"))
        assertNull(GroupMemberIdentitySearch.clipboardInput("Alice"))
        assertNull(GroupMemberIdentitySearch.decoderInput("bob@example.com"))
    }
}

// Published MDK bootstrap vectors: crates/marmot-app/src/ids.rs::npub_and_nprofile_match_bootstrap_vectors.
internal const val MEMBER_HEX = "aa4fc8665f5696e33db7e1a572e3b0f5b3d615837b0f362dcb1c8068b098c7b4"
internal const val MEMBER_NPUB = "npub14f8usejl26twx0dhuxjh9cas7keav9vr0v8nvtwtrjqx3vycc76qqh9nsy"
internal const val MEMBER_NPROFILE =
    "nprofile1qqs25n7gve04d9hr8km7rftjuwc0tv7kzkphkrek9h93eqrgkzvv0dqpremhxue69uhhyetvv9uju" +
        "et49emks6t5v4hx76tnv5hxx6rpwsq3uamnwvaz7tmjv4kxz7fww4ejuamgd96x2mn0d9ek2tnrdpshggcu28s"
internal const val OTHER_HEX = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
internal const val NON_MEMBER_HEX = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"

/** A finite vector table, not an identity resolver that returns the same key for arbitrary input. */
internal fun validatedFixtureIdentity(input: String?): String? =
    when (input) {
        MEMBER_NPUB, MEMBER_NPROFILE, MEMBER_HEX -> MEMBER_HEX
        OTHER_HEX -> OTHER_HEX
        NON_MEMBER_HEX -> NON_MEMBER_HEX
        else -> null
    }
