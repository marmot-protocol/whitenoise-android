package dev.ipf.whitenoise.android.ui.common

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.ipf.whitenoise.android.R

private const val NPUB_PREFIX = "npub1"
private const val NPUB_LENGTH = 63
private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private const val NPUB_HRP = "npub"
private const val NPUB_KEY_BYTES = 32
private const val BECH32_CHECKSUM_LENGTH = 6
private const val BECH32_GROUP_BITS = 5
private const val BECH32_GROUP_MASK = 31
private const val BECH32_HRP_HIGH_SHIFT = 5
private const val BECH32_POLYMOD_TOP_SHIFT = 25
private const val BECH32_POLYMOD_MASK = 0x1ffffff
private const val BECH32_ACCUMULATOR_MASK = 0xfff
private const val BYTE_MASK = 0xff
private val BECH32_GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
private const val MIN_ABBREVIATION_HEAD = 8
private const val MIN_ABBREVIATION_TAIL = 4
private val ELLIPSIS_PATTERN = Regex("…|\\.\\.\\.")

/**
 * True only for a complete lowercase bech32 npub: the right length and alphabet, a valid checksum, and a payload that
 * decodes to exactly 32 bytes with zero padding bits. A middle-ellipsized, grouped or corrupted label never qualifies,
 * so a string that is not a real key can never establish the identity a copy hands out (#3565).
 */
internal fun isCanonicalNpub(value: String): Boolean {
    val wellFormed =
        value.length == NPUB_LENGTH &&
            value.startsWith(NPUB_PREFIX) &&
            value.drop(NPUB_PREFIX.length).all { it in BECH32_CHARSET }
    return wellFormed && hasDecodableNpubPayload(value)
}

/** Checks the bech32 checksum and that the data groups, less the checksum, decode to a 32-byte key. */
private fun hasDecodableNpubPayload(npub: String): Boolean {
    val groups = npub.drop(NPUB_PREFIX.length).map(BECH32_CHARSET::indexOf)
    val checksumValid = bech32Polymod(bech32HrpExpand(NPUB_HRP) + groups) == 1
    return checksumValid && fiveBitGroupsToBytes(groups.dropLast(BECH32_CHECKSUM_LENGTH))?.size == NPUB_KEY_BYTES
}

/** BIP-173 human-readable-part expansion used by the checksum. */
private fun bech32HrpExpand(hrp: String): List<Int> {
    val high = hrp.map { it.code shr BECH32_HRP_HIGH_SHIFT }
    val low = hrp.map { it.code and BECH32_GROUP_MASK }
    return high + 0 + low
}

/** BIP-173 checksum polynomial; a valid string yields 1. */
private fun bech32Polymod(values: List<Int>): Int {
    var checksum = 1
    for (value in values) {
        val top = checksum ushr BECH32_POLYMOD_TOP_SHIFT
        checksum = ((checksum and BECH32_POLYMOD_MASK) shl BECH32_GROUP_BITS) xor value
        BECH32_GENERATORS.forEachIndexed { bit, generator ->
            if ((top shr bit) and 1 == 1) checksum = checksum xor generator
        }
    }
    return checksum
}

/**
 * BIP-173 `convertbits(5, 8, pad = false)`: the five-bit [groups] as bytes, or null when the leftover padding is a
 * whole group or holds a nonzero bit.
 */
private fun fiveBitGroupsToBytes(groups: List<Int>): ByteArray? {
    var accumulator = 0
    var bits = 0
    val bytes = ArrayList<Byte>()
    for (group in groups) {
        accumulator = ((accumulator shl BECH32_GROUP_BITS) or group) and BECH32_ACCUMULATOR_MASK
        bits += BECH32_GROUP_BITS
        while (bits >= Byte.SIZE_BITS) {
            bits -= Byte.SIZE_BITS
            bytes += ((accumulator shr bits) and BYTE_MASK).toByte()
        }
    }
    val cleanPadding = bits < BECH32_GROUP_BITS && ((accumulator shl (Byte.SIZE_BITS - bits)) and BYTE_MASK) == 0
    return bytes.toByteArray().takeIf { cleanPadding }
}

/**
 * True when [name] is [npub] itself or an abbreviation of exactly that key, such as `npub1abcdefg…wxyz`, rather than
 * a person's name. Only this key's own beginning and end qualify, so a human name that merely starts with `npub1`
 * keeps its place (#3565).
 */
internal fun abbreviatesPublicKey(
    name: String,
    npub: String,
): Boolean {
    val value = name.trim()
    val parts = value.split(ELLIPSIS_PATTERN)
    val abbreviation =
        parts.size == 2 &&
            parts[0].length >= MIN_ABBREVIATION_HEAD &&
            parts[1].length >= MIN_ABBREVIATION_TAIL &&
            parts[0].startsWith(NPUB_PREFIX) &&
            npub.startsWith(parts[0]) &&
            npub.endsWith(parts[1])
    return npub.isNotBlank() && value.isNotEmpty() && (value == npub || abbreviation)
}

/**
 * A compact public-key [label] whose own text copies the complete canonical [npub] (#3565). The tap belongs to the
 * label alone, so a surrounding row keeps its navigation, selection or account-switch action, and a long press is
 * left to the row. The label adds no semantics boundary: its short text stays in the row's spoken label, and the row
 * gains a Copy public key action whose label carries the full key. Without a canonical [npub], or while not
 * [enabled], it is plain text.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun PublicKeyCopyText(
    label: String,
    npub: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    enabled: Boolean = true,
) {
    val copy = rememberPublicKeyCopy(npub)
    val copyLabel = stringResource(R.string.public_key_copy_action_with_value, npub)
    Text(
        label,
        modifier =
            if (enabled && isCanonicalNpub(npub)) {
                modifier
                    .pointerInput(npub) { copyOnShortTap(copy) }
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(copyLabel) {
                                    copy()
                                    true
                                },
                            )
                    }
            } else {
                modifier
            },
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Runs [copy] for a tap released before the long-press timeout and consumes only that release, so the row's own tap
 * is skipped. A press held past the timeout is left unconsumed and reaches the row's long-press handler.
 */
private suspend fun PointerInputScope.copyOnShortTap(copy: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
        if (up != null) {
            up.consume()
            copy()
        }
    }
}

/**
 * Copies [npub] to the clipboard. Android 13 and later confirm clipboard writes themselves; older supported releases
 * get the app's localized Copied toast, as other compact copy sites do.
 */
@Composable
private fun rememberPublicKeyCopy(npub: String): () -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val current by rememberUpdatedState(npub)
    return remember(clipboard, context) {
        {
            clipboard.setText(AnnotatedString(current))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
