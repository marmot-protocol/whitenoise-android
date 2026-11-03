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
private const val MIN_ABBREVIATION_HEAD = 8
private const val MIN_ABBREVIATION_TAIL = 4
private val ELLIPSIS_PATTERN = Regex("…|\\.\\.\\.")

/**
 * True only for a complete lowercase bech32 npub. A middle-ellipsized or grouped label never qualifies, so a
 * truncated string can never establish the identity a copy hands out (#3565).
 */
internal fun isCanonicalNpub(value: String): Boolean =
    value.length == NPUB_LENGTH &&
        value.startsWith(NPUB_PREFIX) &&
        value.drop(NPUB_PREFIX.length).all { it in BECH32_CHARSET }

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
