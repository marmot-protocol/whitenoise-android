package dev.ipf.whitenoise.android.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSaveResult
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.CustomEmojiStore
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.conversation.media.BoundedDocumentRead
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.readBoundedDocument
import dev.ipf.whitenoise.android.ui.decodeEmojiImage
import dev.ipf.whitenoise.android.ui.emojiCodeForFileName
import dev.ipf.whitenoise.android.ui.rememberCustomEmojiStore
import dev.ipf.whitenoise.android.ui.sanitizeEmojiCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val CUSTOM_EMOJI_CONTENT_TAG = "custom-emoji-content"

private val CustomEmojiRowImageSize = 32.dp
private val CustomEmojiPreviewSize = 64.dp

/** A picked image waiting for its shortcode. */
private class StagedEmoji(
    val bytes: ByteArray,
    val preview: ImageBitmap,
    val suggestedCode: String,
)

/** The user's `:shortcode:` emoji: device-local images added from Photos or Files, removable one by one. */
@Suppress("FunctionNaming")
@Composable
internal fun CustomEmojiScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = rememberCustomEmojiStore(context)
    val scope = rememberCoroutineScope()
    var staged by remember { mutableStateOf<StagedEmoji?>(null) }
    var removing by remember { mutableStateOf<CustomEmoji?>(null) }

    fun stage(uri: Uri) {
        scope.launch {
            val resolver = context.contentResolver
            val (read, name) =
                withContext(Dispatchers.IO) {
                    readBoundedDocument(CustomEmojiStore.MAX_BYTES) { resolver.openInputStream(uri) } to
                        queryDisplayName(resolver, uri).orEmpty()
                }
            val bytes =
                when (read) {
                    is BoundedDocumentRead.Success -> read.bytes
                    BoundedDocumentRead.TooLarge -> return@launch appState.present(R.string.custom_emoji_too_large)
                    BoundedDocumentRead.Empty,
                    BoundedDocumentRead.Unreadable,
                    -> return@launch appState.present(R.string.custom_emoji_not_image)
                }
            val preview =
                withContext(Dispatchers.Default) { decodeEmojiImage(bytes) }
                    ?: return@launch appState.present(R.string.custom_emoji_not_image)
            staged = StagedEmoji(bytes, preview, emojiCodeForFileName(name))
        }
    }

    val photos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(::stage)
        }
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::stage)
        }
    CustomEmojiContent(
        emoji = store.emoji,
        onBack = onBack,
        onChoosePhoto = {
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onChooseFile = { files.launch(arrayOf("image/*")) },
        onRemove = { removing = it },
    )
    staged?.let { pending ->
        CustomEmojiNameDialog(
            preview = pending.preview,
            suggestedCode = pending.suggestedCode,
            existing = store.emoji,
            onDismiss = { staged = null },
            onSave = { code ->
                staged = null
                scope.launch {
                    customEmojiSaveMessage(store.save(code, pending.bytes))?.let(appState::present)
                }
            },
        )
    }
    removing?.let { emoji ->
        WhiteNoiseAlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.custom_emoji_remove_title, emoji.shortcode)) },
            text = { Text(stringResource(R.string.custom_emoji_remove_detail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        scope.launch { store.remove(emoji.shortcode) }
                    },
                ) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The list without state ownership, so tests can render every projection. */
@Suppress("FunctionNaming")
@Composable
internal fun CustomEmojiContent(
    emoji: CustomEmojiSet,
    onBack: () -> Unit,
    onChoosePhoto: () -> Unit,
    onChooseFile: () -> Unit,
    onRemove: (CustomEmoji) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.custom_emoji), onBack = onBack) {
        SettingsList(modifier = Modifier.testTag(CUSTOM_EMOJI_CONTENT_TAG)) {
            item { SettingsExplainer(stringResource(R.string.custom_emoji_detail)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("custom_emoji.add")) {
                    row("choose_photo") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.choose_photos),
                            onClick = onChoosePhoto,
                            leading = { Icon(painterResource(R.drawable.ic_image), contentDescription = null) },
                        )
                    }
                    row("choose_file") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.choose_files),
                            onClick = onChooseFile,
                            leading = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                        )
                    }
                }
            }
            if (emoji.entries.isEmpty()) {
                item { SettingsExplainer(stringResource(R.string.custom_emoji_empty)) }
            } else {
                item {
                    SettingsGroup(modifier = Modifier.testTag("custom_emoji.list")) {
                        emoji.entries.forEach { entry ->
                            row(entry.shortcode) { context ->
                                SettingsAction(
                                    context = context,
                                    title = entry.shortcode,
                                    subtitle =
                                        stringResource(
                                            if (entry.sendable) R.string.remove else R.string.custom_emoji_unsendable,
                                        ),
                                    onClick = { onRemove(entry) },
                                    leading = {
                                        EmojiArtImage(
                                            entry.art,
                                            contentDescription = null,
                                            modifier = Modifier.size(CustomEmojiRowImageSize),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Typing keeps to the shortcode alphabet, the same rule the store enforces on save.
private val EmojiCodeInput =
    InputTransformation {
        val typed = asCharSequence().toString()
        val sanitized = sanitizeEmojiCode(typed)
        if (sanitized != typed) {
            replace(0, length, sanitized)
        }
    }

/** Names a picked image; the prefill is its sanitized file name. */
@Suppress("FunctionNaming")
@Composable
private fun CustomEmojiNameDialog(
    preview: ImageBitmap,
    suggestedCode: String,
    existing: CustomEmojiSet,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val field = rememberTextFieldState(suggestedCode)
    val code = field.text.toString()
    val replaces = code.isNotEmpty() && existing[":$code:"] != null
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Image(preview, contentDescription = null, modifier = Modifier.size(CustomEmojiPreviewSize)) },
        title = { Text(stringResource(R.string.custom_emoji_name_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WhiteNoiseTextField(
                    state = field,
                    modifier = Modifier.testTag("custom_emoji.name"),
                    label = { Text(stringResource(R.string.custom_emoji_name_label)) },
                    supportingText = {
                        Text(
                            if (replaces) {
                                stringResource(R.string.custom_emoji_replaces, ":$code:")
                            } else {
                                stringResource(R.string.custom_emoji_name_help)
                            },
                        )
                    },
                    inputTransformation = EmojiCodeInput,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(code) }, enabled = code.isNotEmpty()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The toast for a save that did not store the emoji. */
private fun customEmojiSaveMessage(result: CustomEmojiSaveResult): Int? =
    when (result) {
        CustomEmojiSaveResult.Saved -> null
        CustomEmojiSaveResult.TooLarge -> R.string.custom_emoji_too_large
        CustomEmojiSaveResult.NotAnImage -> R.string.custom_emoji_not_image
        CustomEmojiSaveResult.InvalidName,
        CustomEmojiSaveResult.Failed,
        -> R.string.custom_emoji_save_failed
    }
