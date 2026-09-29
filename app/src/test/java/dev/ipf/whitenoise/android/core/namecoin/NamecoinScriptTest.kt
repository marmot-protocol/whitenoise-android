package dev.ipf.whitenoise.android.core.namecoin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Script assembly, scripthash derivation, and name-op vout decoding tests for
 * [NamecoinScript], ported from the script sections of
 * `tests/app/namecoin_test.odin` (whitenoise-linux commit 79447095f2). The
 * scripthash golden vector is pinned against an independent shell computation
 * (sha256 of the canonical index script, byte-reversed) so the derivation
 * cannot drift from what ElectrumX servers index by.
 */
class NamecoinScriptTest {
    // ── script assembly + scripthash ───────────────────────────────────

    @Test
    fun scriptLayoutForShortName() {
        // Short names use the direct-length push opcode: the first byte after
        // OP_NAME_UPDATE is the name length, not a PUSHDATA opcode.
        val name = "d/example".toByteArray()
        val script = NamecoinScript.buildNameIndexScript(name)
        // OP_NAME_UPDATE (0x53) | len=9 | name bytes | 0x00 (empty value push)
        // | OP_2DROP (0x6d) | OP_DROP (0x75) | OP_RETURN (0x6a)
        assertEquals(0x53.toByte(), script[0])
        assertEquals(name.size.toByte(), script[1])
        for (i in name.indices) {
            assertEquals(name[i], script[2 + i])
        }
        val tailAt = 2 + name.size
        assertEquals(0x00.toByte(), script[tailAt])
        assertEquals(0x6d.toByte(), script[tailAt + 1])
        assertEquals(0x75.toByte(), script[tailAt + 2])
        assertEquals(0x6a.toByte(), script[tailAt + 3])
        assertEquals(tailAt + 4, script.size)
    }

    @Test
    fun scriptPushData1ForLongName() {
        // Names >= 0x4c bytes cross into PUSHDATA1 encoding.
        val name = "d/" + "a".repeat(80)
        val script = NamecoinScript.buildNameIndexScript(name.toByteArray())
        assertEquals(0x53.toByte(), script[0])
        assertEquals(0x4c.toByte(), script[1]) // OP_PUSHDATA1
        assertEquals(name.length.toByte(), script[2])
    }

    @Test
    fun scripthashMatchesGoldenVector() {
        // sha256(53 09 "d/example" 00 6d 75 6a), byte-reversed, lowercase hex —
        // computed independently of this code (shell shasum) so the test pins
        // the exact digest ElectrumX indexes by.
        val script = NamecoinScript.buildNameIndexScript("d/example".toByteArray())
        assertEquals(
            "92f51fe51fa9c53ea842325c9c63a9b8592b3f64c7477b6e538a64d80cd8c0ac",
            NamecoinScript.electrumScriptHash(script),
        )
    }

    @Test
    fun scripthashDeterministic() {
        val script = NamecoinScript.buildNameIndexScript("d/example".toByteArray())
        val h1 = NamecoinScript.electrumScriptHash(script)
        val h2 = NamecoinScript.electrumScriptHash(script)
        assertEquals(h1, h2)
        assertEquals(64, h1.length)
    }

    // ── name-op vout decoding ──────────────────────────────────────────

    @Test
    fun parseOpNameUpdate() {
        // OP_NAME_UPDATE <push "d/example"> <push "hello"> <trailer>
        val name = "d/example".toByteArray()
        val value = "hello".toByteArray()
        val script =
            byteArrayOf(0x53, name.size.toByte()) + name +
                byteArrayOf(value.size.toByte()) + value +
                byteArrayOf(0x6d, 0x75, 0x6a)
        val parsed = NamecoinScript.parseNameScript(script)
        assertNotNull(parsed)
        assertEquals("d/example", parsed!!.name)
        assertEquals("hello", parsed.value)
    }

    @Test
    fun parseOpNameFirstUpdate() {
        // OP_NAME_FIRSTUPDATE <push name> <push 20-byte rand> <push value> <trailer>
        val name = "d/mstrofnone".toByteArray()
        val value = """{"nostr":"deadbeef"}""".toByteArray()
        val rand = ByteArray(20) { 0xAA.toByte() }
        val script =
            byteArrayOf(0x52, name.size.toByte()) + name +
                byteArrayOf(20) + rand +
                byteArrayOf(value.size.toByte()) + value +
                byteArrayOf(0x6d, 0x6d, 0x75, 0x6a)
        val parsed = NamecoinScript.parseNameScript(script)
        assertNotNull(parsed)
        assertEquals("d/mstrofnone", parsed!!.name)
        assertEquals("""{"nostr":"deadbeef"}""", parsed.value)
    }

    @Test
    fun parseRejectsOpNameNew() {
        // OP_NAME_NEW (0x51) carries no visible value.
        val script = byteArrayOf(0x51, 0x02, 0xaa.toByte(), 0xbb.toByte(), 0x75, 0x6a)
        assertNull(NamecoinScript.parseNameScript(script))
    }

    @Test
    fun parseRejectsTruncatedPush() {
        // Declared name length runs past the end of the script.
        val script = byteArrayOf(0x53, 0x40, 0x64, 0x2f)
        assertNull(NamecoinScript.parseNameScript(script))
    }

    @Test
    fun hexDecodeRoundtrip() {
        val input = byteArrayOf(0x00, 0x53, 0xff.toByte(), 0xa1.toByte(), 0x0d)
        val asHex = input.joinToString("") { "%02x".format(it) }
        val back = NamecoinScript.hexDecode(asHex)
        assertNotNull(back)
        assertArrayEquals(input, back!!)
        assertNull(NamecoinScript.hexDecode("0xnothex"))
        assertNull(NamecoinScript.hexDecode("abc")) // odd length
    }

    @Test
    fun extractNameValueMatches() {
        // A vout: OP_NAME_UPDATE push "d/x" push "V".
        val script =
            byteArrayOf(0x53, 3, 'd'.code.toByte(), '/'.code.toByte(), 'x'.code.toByte(), 1, 'V'.code.toByte()) +
                byteArrayOf(0x6d, 0x75, 0x6a)
        val asHex = script.joinToString("") { "%02x".format(it) }
        // A second vout that pays a p2pkh; the front-door filter should skip it.
        val p2pkh = "76a914aabbccddeeff112233445566778899aabbccddee88ac"
        assertEquals("V", NamecoinScript.extractNameValue(listOf(p2pkh, asHex), "d/x"))
        // Non-matching name returns null.
        assertNull(NamecoinScript.extractNameValue(listOf(asHex), "d/y"))
    }
}
