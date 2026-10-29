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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
internal class StagedEmoji(
    val bytes: ByteArray,
    val preview: ImageBitmap,
    val suggestedCode: String,
    val owner: String,
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
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<CustomEmoji?>(null) }

    val selectionAccount = appState.activeAccountRef
    val selectionRuntime = appState.runtimeGeneration
    val selectionIdentity = "$selectionRuntime:$selectionAccount"

    LaunchedEffect(selectionIdentity) {
        if (selectedOwner != selectionIdentity) {
            selectedUri = null
            selectedOwner = null
        }
    }
    PrepareSelectedEmoji(
        appState,
        selectedUri,
        selectedOwner,
        onFailed = { uri, message ->
            if (selectedUri == uri.toString()) {
                selectedUri = null
                selectedOwner = null
            }
            appState.present(message)
        },
        onStaged = { staged = it },
    )

    val pickers =
        rememberEmojiPickers(appState) { uri, owner ->
            selectedOwner = owner
            selectedUri = uri.toString()
        }
    CustomEmojiContent(
        emoji = store.emoji,
        onBack = onBack,
        onChoosePhoto = pickers.photo,
        onChooseFile = pickers.file,
        onRemove = { removing = it },
    )
    StagedEmojiDialog(appState, store, staged?.takeIf { it.owner == selectionIdentity }) {
        staged = null
        selectedUri = null
        selectedOwner = null
    }
    removing?.let { emoji ->
        ConfirmEmojiRemoval(
            emoji,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                scope.launch { store.remove(emoji.shortcode) }
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
internal fun CustomEmojiNameDialog(
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

/**
 * Reads the selected image for the account and runtime that picked it, then stages it or reports a failure — dropping
 * the result if ownership changed meanwhile.
 */
@Suppress("FunctionNaming")
@Composable
private fun PrepareSelectedEmoji(
    appState: WhiteNoiseAppState,
    selectedUri: String?,
    selectedOwner: String?,
    onFailed: (Uri, Int) -> Unit,
    onStaged: (StagedEmoji) -> Unit,
) {
    val context = LocalContext.current
    val selectionAccount = appState.activeAccountRef
    val selectionRuntime = appState.runtimeGeneration
    val selectionIdentity = "$selectionRuntime:$selectionAccount"
    LaunchedEffect(selectedUri, selectedOwner, selectionIdentity) {
        if (selectedOwner != selectionIdentity) return@LaunchedEffect
        val uri = selectedUri?.let(Uri::parse) ?: return@LaunchedEffect

        /** Reports [message] for this selection only if its account and runtime are still current. */
        fun failSelection(message: Int) {
            failCurrentEmojiSelection(appState, selectionAccount to selectionRuntime, uri, message, onFailed)
        }
        val resolver = context.contentResolver
        val (read, name) =
            withContext(Dispatchers.IO) {
                readBoundedDocument(CustomEmojiStore.MAX_BYTES) { resolver.openInputStream(uri) } to
                    queryDisplayName(resolver, uri).orEmpty()
            }
        if (appState.activeAccountRef != selectionAccount || appState.runtimeGeneration != selectionRuntime) {
            return@LaunchedEffect
        }
        val bytes =
            when (read) {
                is BoundedDocumentRead.Success -> read.bytes
                BoundedDocumentRead.TooLarge -> return@LaunchedEffect failSelection(R.string.custom_emoji_too_large)
                BoundedDocumentRead.Empty,
                BoundedDocumentRead.Unreadable,
                -> return@LaunchedEffect failSelection(R.string.custom_emoji_not_image)
            }
        val preview =
            withContext(Dispatchers.Default) {
                decodeEmojiImage(
                    bytes,
                    dev.ipf.whitenoise.android.media.MediaTrustDomain
                        .local(selectionAccount),
                )
            }
                ?: return@LaunchedEffect failSelection(R.string.custom_emoji_not_image)
        if (appState.activeAccountRef != selectionAccount || appState.runtimeGeneration != selectionRuntime) {
            return@LaunchedEffect
        }
        onStaged(StagedEmoji(bytes, preview, emojiCodeForFileName(name), selectionIdentity))
    }
}

/** Asks the user to confirm removing [emoji] from their custom emoji set. */
@Suppress("FunctionNaming")
@Composable
internal fun ConfirmEmojiRemoval(
    emoji: CustomEmoji,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_emoji_remove_title, emoji.shortcode)) },
        text = { Text(stringResource(R.string.custom_emoji_remove_detail)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private data class EmojiPickers(
    val photo: () -> Unit,
    val file: () -> Unit,
)

/** Creates photo and file pickers whose results are delivered only to the account and runtime that launched them. */
@Composable
private fun rememberEmojiPickers(
    appState: WhiteNoiseAppState,
    onPicked: (Uri, String) -> Unit,
): EmojiPickers {
    var pickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val identity = "${appState.runtimeGeneration}:${appState.activeAccountRef}"

    /** Forwards a picked [uri] only when the launching owner is still the current account and runtime. */
    fun stage(uri: Uri?) {
        val owner = pickerOwner
        pickerOwner = null
        if (owner == null || uri == null) return
        if (owner != "${appState.runtimeGeneration}:${appState.activeAccountRef}") return
        onPicked(uri, owner)
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::stage)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::stage)
    return EmojiPickers(
        photo = {
            pickerOwner = identity
            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        file = {
            pickerOwner = identity
            files.launch(arrayOf("image/*"))
        },
    )
}

/** Shows the naming dialog for a staged emoji and saves it to [store] on confirmation. */
@Suppress("FunctionNaming")
@Composable
internal fun StagedEmojiDialog(
    appState: WhiteNoiseAppState,
    store: CustomEmojiStore,
    pending: StagedEmoji?,
    onClear: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    pending?.let {
        CustomEmojiNameDialog(
            preview = it.preview,
            suggestedCode = it.suggestedCode,
            existing = store.emoji,
            onDismiss = onClear,
            onSave = { code ->
                onClear()
                scope.launch { customEmojiSaveMessage(store.save(code, it.bytes))?.let(appState::present) }
            },
        )
    }
}

/** Delivers a selection failure only when [owner] still matches the active account and runtime. */
private fun failCurrentEmojiSelection(
    appState: WhiteNoiseAppState,
    owner: Pair<String?, Int>,
    uri: Uri,
    message: Int,
    onFailed: (Uri, Int) -> Unit,
) {
    if (appState.activeAccountRef != owner.first || appState.runtimeGeneration != owner.second) return
    onFailed(uri, message)
}
