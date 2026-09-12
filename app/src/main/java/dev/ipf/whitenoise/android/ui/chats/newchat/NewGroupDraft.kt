package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import dev.ipf.whitenoise.android.media.ImageUploadDraft

/** An unsubmitted UI draft shared by member selection and setup; never an engine/group cache. */
@Stable
internal class NewGroupDraft(
    val name: TextFieldState = TextFieldState(),
    val description: TextFieldState = TextFieldState(),
    retentionSecs: Long = 0L,
    retryGroupIdHex: String? = null,
    imageNeedsReselection: Boolean = false,
) {
    var retentionSecs by mutableLongStateOf(retentionSecs)
    var imageDraft by mutableStateOf<ImageUploadDraft?>(null)
    var imageNeedsReselection by mutableStateOf(imageNeedsReselection)
    var retryGroupIdHex by mutableStateOf(retryGroupIdHex)
    var createRequestToken by mutableLongStateOf(0L)
}

/** Saves small authored fields/selection and canonical recovery ID, never prepared image bytes in a Bundle. */
private val NewGroupDraftSaver =
    listSaver<NewGroupDraft, Any>(
        save = {
            listOf(
                it.name.text.toString(),
                it.name.selection.start,
                it.name.selection.end,
                it.description.text.toString(),
                it.description.selection.start,
                it.description.selection.end,
                it.retentionSecs,
                it.retryGroupIdHex.orEmpty(),
                it.createRequestToken,
                it.imageDraft != null || it.imageNeedsReselection,
            )
        },
        restore = {
            NewGroupDraft(
                name = TextFieldState(it[0] as String, TextRange(it[1] as Int, it[2] as Int)),
                description = TextFieldState(it[3] as String, TextRange(it[4] as Int, it[5] as Int)),
                retentionSecs = it[6] as Long,
                retryGroupIdHex = (it[7] as String).takeIf(String::isNotEmpty),
                imageNeedsReselection = it[9] as Boolean && (it[7] as String).isEmpty(),
            ).apply { createRequestToken = it[8] as Long }
        },
    )

/** Retains setup fields across step changes while account/runtime keys remain with the flow caller. */
@Composable
internal fun rememberNewGroupDraft(initialRetryGroupIdHex: String? = null): NewGroupDraft =
    rememberSaveable(saver = NewGroupDraftSaver) { NewGroupDraft(retryGroupIdHex = initialRetryGroupIdHex) }
