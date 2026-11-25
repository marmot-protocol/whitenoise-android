package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientPasteDecision
import dev.ipf.whitenoise.android.core.RecipientPastePolicy
import dev.ipf.whitenoise.android.ui.common.SystemPasteIconButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.common.withPrimaryClipForPaste

/**
 * Recipient-only search field that validates explicit clipboard input before it can reach search.
 * Ordinary typing, IME commits, QR fills, and non-identity paste remain standard TextField edits.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("FunctionNaming", "LongMethod") // Compose field wiring stays together for one paste boundary.
internal fun RecipientSearchField(
    state: TextFieldState,
    placeholder: String,
    onPasteRejected: () -> Unit,
    isValidNpub: (String) -> Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onScanQr: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager =
        remember(context) {
            ContextCompat.getSystemService(context, ClipboardManager::class.java)
        }
    val canOfferPaste = rememberClipboardCanOfferPaste(clipboardManager)
    val latestOnPasteRejected = rememberUpdatedState(onPasteRejected)
    val receiver =
        remember(state) {
            object : ReceiveContentListener {
                override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                    if (transferableContent.source != TransferableContent.Source.Clipboard) {
                        return transferableContent
                    }
                    val items = transferableContent.clipEntry.clipData.directRecipientPasteItems()
                    val consumed =
                        dispatchRecipientPaste(
                            state = state,
                            items = items,
                            platformHandlesPassThrough = true,
                            isValidNpub = isValidNpub,
                            onRejected = latestOnPasteRejected.value,
                        )
                    return if (consumed) null else transferableContent
                }
            }
        }

    WhiteNoiseCompactSearchField(
        state = state,
        placeholder = placeholder,
        enabled = enabled,
        outlined = true,
        keyboardOptions = RecipientSearchKeyboardOptions,
        clearDescription = stringResource(R.string.clear),
        onClear = { state.replaceRecipientText("") },
        emptyTrailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canOfferPaste) {
                    SystemPasteIconButton(
                        enabled = enabled,
                        onPaste = {
                            clipboardManager.withPrimaryClipForPaste { clip ->
                                dispatchRecipientPaste(
                                    state = state,
                                    items = clip.directRecipientPasteItems(),
                                    platformHandlesPassThrough = false,
                                    isValidNpub = isValidNpub,
                                    onRejected = onPasteRejected,
                                )
                            }
                        },
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_content_paste),
                            contentDescription = stringResource(R.string.paste),
                        )
                    }
                }
                if (onScanQr != null) {
                    IconButton(onClick = onScanQr, enabled = enabled) {
                        Icon(
                            painterResource(R.drawable.ic_qr_code_scanner),
                            contentDescription = stringResource(R.string.scan_qr_code),
                        )
                    }
                }
            }
        },
        modifier =
            modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .contentReceiver(receiver)
                .fillMaxWidth(),
    )
}

/** Identity-friendly IME options shared by every people search field. */
internal val RecipientSearchKeyboardOptions =
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        imeAction = ImeAction.Search,
    )

/**
 * Returns only clipboard-owned text or the literal URI string. It deliberately never calls
 * `coerceToText`, which can synchronously dereference a content provider on the UI thread.
 */
@Suppress("ReturnCount") // Every unsafe provider, intent, size, or item state fails closed.
internal fun ClipData.directRecipientPasteItems(): List<String>? {
    if (itemCount !in 1..RecipientPastePolicy.MAX_ITEMS) return null
    val directItems = ArrayList<String>(itemCount)
    var remainingBytes = RecipientPastePolicy.MAX_UTF8_BYTES
    repeat(itemCount) { index ->
        if (index > 0) {
            remainingBytes -= 1
            if (remainingBytes < 0) return null
        }
        val item = getItemAt(index)
        if (item.intent != null) return null
        val directText: CharSequence = item.text ?: item.uri?.toString() ?: return null
        val byteCount = directText.utf8SizeWithin(remainingBytes) ?: return null
        remainingBytes -= byteCount
        directItems += directText.toString()
    }
    return directItems
}

/** Counts UTF-8 bytes without allocating or scanning beyond the caller's remaining budget. */
private fun CharSequence.utf8SizeWithin(limit: Int): Int? {
    var bytes = 0
    var index = 0
    while (index < length) {
        val character = this[index]
        val increment =
            when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                Character.isHighSurrogate(character) &&
                    index + 1 < length &&
                    Character.isLowSurrogate(this[index + 1]) -> {
                    index += 1
                    4
                }
                else -> 3
            }
        bytes += increment
        if (bytes > limit) return null
        index += 1
    }
    return bytes
}

/**
 * Applies the shared recipient policy for native and explicit-button paste entry points.
 *
 * Returns true when this boundary consumed the content. A native non-identity paste returns
 * false so TextField retains platform selection, accessibility, and IME behavior.
 */
internal fun dispatchRecipientPaste(
    state: TextFieldState,
    items: List<String>?,
    platformHandlesPassThrough: Boolean,
    isValidNpub: (String) -> Boolean,
    onRejected: () -> Unit,
): Boolean =
    when (val decision = items?.let { RecipientPastePolicy.evaluate(it, isValidNpub) }) {
        is RecipientPasteDecision.Accept -> {
            state.replaceSelectionForRecipientPaste(decision.value)
            true
        }
        is RecipientPasteDecision.PassThrough -> {
            if (platformHandlesPassThrough) {
                false
            } else {
                state.replaceSelectionForRecipientPaste(decision.value)
                true
            }
        }
        is RecipientPasteDecision.Reject, null -> {
            onRejected()
            true
        }
    }
