package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

/** Prototype compact search geometry with the unchanged recipient paste policy and native selection/IME state. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod") // Compose naming follows the framework convention.
internal fun NewGroupRecipientSearchField(
    state: TextFieldState,
    onPasteRejected: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { ContextCompat.getSystemService(context, ClipboardManager::class.java) }
    val offerPaste = rememberClipboardCanOfferPaste(clipboard)
    val latestRejected = rememberUpdatedState(onPasteRejected)
    val receiver =
        remember(state) {
            object : ReceiveContentListener {
                override fun onReceive(content: TransferableContent): TransferableContent? {
                    if (content.source != TransferableContent.Source.Clipboard) return content
                    val consumed =
                        dispatchRecipientPaste(
                            state,
                            content.clipEntry.clipData.directRecipientPasteItems(),
                            true,
                            latestRejected.value,
                        )
                    return if (consumed) null else content
                }
            }
        }
    val interactions = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.extraLarge
    val colors =
        TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        )
    BasicTextField(
        state = state,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .contentReceiver(receiver)
                .amoledSurfaceBorder(shape),
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
        interactionSource = interactions,
        decorator = { inner ->
            TextFieldDefaults.DecorationBox(
                value = state.text.toString(),
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactions,
                placeholder = { Text(stringResource(R.string.search_people_hint), maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.text.isNotEmpty()) {
                            IconButton({ state.replaceRecipientText("") }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.clear))
                            }
                        } else {
                            if (offerPaste) {
                                IconButton({
                                    dispatchRecipientPaste(
                                        state,
                                        clipboard?.primaryClip?.directRecipientPasteItems(),
                                        false,
                                        onPasteRejected,
                                    )
                                }) {
                                    Icon(Icons.Default.ContentPaste, stringResource(R.string.paste))
                                }
                            }
                            IconButton(onScan) {
                                Icon(
                                    painterResource(R.drawable.ic_qr_code_scanner),
                                    stringResource(R.string.scan_qr_code),
                                )
                            }
                        }
                    }
                },
                shape = shape,
                colors = colors,
                contentPadding = TextFieldDefaults.contentPaddingWithoutLabel(top = 0.dp, bottom = 0.dp),
                container = {
                    TextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactions,
                        colors = colors,
                        shape = shape,
                        focusedIndicatorLineThickness = 0.dp,
                        unfocusedIndicatorLineThickness = 0.dp,
                    )
                },
            )
        },
    )
}
