package dev.ipf.whitenoise.android.core.namecoin

import java.security.MessageDigest

/**
 * Namecoin OP_NAME_UPDATE script assembly and OP_NAME_* vout parsing.
 *
 * ElectrumX indexes Namecoin names by the scripthash of a canonical
 * OP_NAME_UPDATE-shaped script over the name bytes; the resolver hashes that
 * here to derive the key it sends to `blockchain.scripthash.get_history`.
 * The derivation is computed LOCALLY so the resolver never trusts the server
 * for name-to-script mapping. Once history comes back, the same file decodes
 * the OP_NAME_UPDATE or OP_NAME_FIRSTUPDATE vout the transaction carries and
 * pulls the raw name value JSON out. Both layouts share the OP_NAME_NEW
 * envelope; only FIRSTUPDATE wedges a 20-byte HASH160 commitment between the
 * name and the value push.
 *
 * Port of `app/namecoin_script.odin` from whitenoise-linux (79447095f2),
 * matching the shape used by the applesauce, gitworkshop, and Amethyst ports
 * of this same layout so a value payload produced against any of them
 * round-trips through this parser.
 */
internal object NamecoinScript {
    // Namecoin script opcodes used by the name-index script.
    private const val OP_NAME_NEW: Int = 0x51
    private const val OP_NAME_FIRSTUPDATE: Int = 0x52
    private const val OP_NAME_UPDATE: Int = 0x53
    private const val OP_2DROP: Int = 0x6d
    private const val OP_DROP: Int = 0x75
    private const val OP_RETURN: Int = 0x6a
    private const val OP_PUSHDATA1: Int = 0x4c
    private const val OP_PUSHDATA2: Int = 0x4d
    private const val OP_PUSHDATA4: Int = 0x4e

    /** HASH160 commitment length in an OP_NAME_FIRSTUPDATE vout, per namecore consensus rules. */
    private const val FIRSTUPDATE_RAND_LEN = 20

    /**
     * Build the OP_NAME_UPDATE name-index script for [name] bytes:
     * `OP_NAME_UPDATE <push(name)> <push(empty)> OP_2DROP OP_DROP OP_RETURN`.
     */
    fun buildNameIndexScript(name: ByteArray): ByteArray {
        val out = ArrayList<Byte>(name.size + 8)
        out.add(OP_NAME_UPDATE.toByte())
        pushData(out, name)
        pushData(out, ByteArray(0))
        out.add(OP_2DROP.toByte())
        out.add(OP_DROP.toByte())
        out.add(OP_RETURN.toByte())
        return out.toByteArray()
    }

    private fun pushData(
        out: ArrayList<Byte>,
        data: ByteArray,
    ) {
        val n = data.size
        when {
            n < OP_PUSHDATA1 -> out.add(n.toByte())
            n <= 0xff -> {
                out.add(OP_PUSHDATA1.toByte())
                out.add(n.toByte())
            }
            n <= 0xffff -> {
                out.add(OP_PUSHDATA2.toByte())
                out.add((n and 0xff).toByte())
                out.add(((n ushr 8) and 0xff).toByte())
            }
            else -> {
                out.add(OP_PUSHDATA4.toByte())
                out.add((n and 0xff).toByte())
                out.add(((n ushr 8) and 0xff).toByte())
                out.add(((n ushr 16) and 0xff).toByte())
                out.add(((n ushr 24) and 0xff).toByte())
            }
        }
        for (b in data) out.add(b)
    }

    /**
     * ElectrumX scripthash: SHA-256 of the script, byte-reversed, lowercase
     * hex. Every ElectrumX server keys `blockchain.scripthash.get_history` by
     * this exact digest.
     */
    fun electrumScriptHash(script: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(script)
        val out = StringBuilder(64)
        for (i in digest.indices.reversed()) {
            out.append(HEX_CHARS[(digest[i].toInt() ushr 4) and 0x0f])
            out.append(HEX_CHARS[digest[i].toInt() and 0x0f])
        }
        return out.toString()
    }

    private const val HEX_CHARS = "0123456789abcdef"

    /** One decoded push: the pushed bytes plus the position just past it. */
    private data class Push(
        val data: ByteArray,
        val next: Int,
    )

    /**
     * Read one push from [script] at [pos]; null for a malformed or truncated
     * push. Mirrors `nc_read_push`: the declared length is always bounds-checked
     * against the remaining script before slicing.
     */
    private fun readPush(
        script: ByteArray,
        pos: Int,
    ): Push? {
        if (pos >= script.size) return null
        val op = script[pos].toInt() and 0xff
        if (op == 0x00) return Push(script.copyOfRange(pos, pos), pos + 1)
        if (op < OP_PUSHDATA1) {
            val end = pos + 1 + op
            if (end > script.size) return null
            return Push(script.copyOfRange(pos + 1, end), end)
        }
        if (op == OP_PUSHDATA1) {
            if (pos + 2 > script.size) return null
            val length = script[pos + 1].toInt() and 0xff
            val end = pos + 2 + length
            if (end > script.size) return null
            return Push(script.copyOfRange(pos + 2, end), end)
        }
        if (op == OP_PUSHDATA2) {
            if (pos + 3 > script.size) return null
            val length =
                (script[pos + 1].toInt() and 0xff) or
                    ((script[pos + 2].toInt() and 0xff) shl 8)
            val end = pos + 3 + length
            if (end > script.size) return null
            return Push(script.copyOfRange(pos + 3, end), end)
        }
        if (op == OP_PUSHDATA4) {
            if (pos + 5 > script.size) return null
            val length =
                (script[pos + 1].toLong() and 0xff) or
                    ((script[pos + 2].toLong() and 0xff) shl 8) or
                    ((script[pos + 3].toLong() and 0xff) shl 16) or
                    ((script[pos + 4].toLong() and 0xff) shl 24)
            if (length > Int.MAX_VALUE) return null
            val end = pos + 5 + length.toInt()
            if (end < 0 || end > script.size) return null
            return Push(script.copyOfRange(pos + 5, end), end)
        }
        return null
    }

    /** A decoded name-op vout: the `(name, value)` pair it carries. */
    internal data class NameOp(
        val name: String,
        val value: String,
    )

    /**
     * Decode one Namecoin name-op vout into its `(name, value)` pair. Accepts
     * both value-carrying layouts:
     *
     * ```
     * OP_NAME_FIRSTUPDATE <name> <rand> <value> OP_2DROP OP_2DROP OP_DROP <p2pkh>
     * OP_NAME_UPDATE      <name> <value>        OP_2DROP OP_DROP        <p2pkh>
     * ```
     *
     * OP_NAME_NEW carries no value here; returns null for it.
     */
    fun parseNameScript(script: ByteArray): NameOp? {
        if (script.isEmpty()) return null
        val op = script[0].toInt() and 0xff
        if (op != OP_NAME_UPDATE && op != OP_NAME_FIRSTUPDATE) return null
        val namePush = readPush(script, 1) ?: return null
        var pos = namePush.next
        if (op == OP_NAME_FIRSTUPDATE) {
            val randPush = readPush(script, pos) ?: return null
            if (randPush.data.size != FIRSTUPDATE_RAND_LEN) return null
            pos = randPush.next
        }
        val valuePush = readPush(script, pos) ?: return null
        return NameOp(String(namePush.data, Charsets.UTF_8), String(valuePush.data, Charsets.UTF_8))
    }

    /** Decode one lowercase hex string into bytes; null on any malformed nibble or odd length. */
    fun hexDecode(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = hexNibble(s[i * 2])
            val lo = hexNibble(s[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexNibble(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

    /**
     * Walk [voutHexScripts] (each a hex-encoded scriptPubKey) and return the
     * raw name value string for the vout that carries [name]; null when no
     * vout matches.
     */
    fun extractNameValue(
        voutHexScripts: List<String>,
        name: String,
    ): String? {
        for (hexScript in voutHexScripts) {
            if (hexScript.length < 2) continue
            // Cheap front-door: only 0x52 / 0x53 opcodes carry a value.
            if (!(hexScript[0] == '5' && (hexScript[1] == '2' || hexScript[1] == '3'))) continue
            val bytes = hexDecode(hexScript) ?: continue
            val parsed = parseNameScript(bytes) ?: continue
            if (parsed.name == name) return parsed.value
        }
        return null
    }
}
