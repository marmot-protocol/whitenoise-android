package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientPasteDecision
import dev.ipf.whitenoise.android.core.RecipientPastePolicy
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

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
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onScanQr: (() -> Unit)? = null,
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
                            onRejected = latestOnPasteRejected.value,
                        )
                    return if (consumed) null else transferableContent
                }
            }
        }
    val shape = RoundedCornerShape(28.dp)

    TextField(
        state = state,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.text.isNotEmpty() -> {
                        IconButton(onClick = { state.replaceRecipientText("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                        }
                    }
                    canOfferPaste -> {
                        IconButton(
                            onClick = {
                                dispatchRecipientPaste(
                                    state = state,
                                    items = clipboardManager?.primaryClip?.directRecipientPasteItems(),
                                    platformHandlesPassThrough = false,
                                    onRejected = onPasteRejected,
                                )
                            },
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.paste))
                        }
                    }
                }
                if (state.text.isEmpty() && onScanQr != null) {
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code))
                    }
                }
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = shape,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
        modifier =
            modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .contentReceiver(receiver)
                .fillMaxWidth()
                .amoledSurfaceBorder(shape),
    )
}

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
    onRejected: () -> Unit,
): Boolean =
    when (val decision = items?.let(RecipientPastePolicy::evaluate)) {
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
