package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver

/** Saved picker intent carries its original owner, so Activity restoration cannot retarget local edits. */
internal class ChatFolderAssignmentDraft(
    val account: String,
    val runtime: Int,
    targets: List<String>,
    initialIntents: Map<String, Boolean> = emptyMap(),
) {
    val targets =
        targets
            .map { it.trim().lowercase() }
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
    val intents = mutableStateMapOf<String, Boolean>().apply { putAll(initialIntents) }

    /** Account/runtime and the full canonical target set must still match before any draft is usable. */
    fun matches(
        account: String,
        runtime: Int,
        targets: List<String>,
    ): Boolean =
        this.account == account &&
            this.runtime == runtime &&
            this.targets ==
            targets
                .map { it.trim().lowercase() }
                .filter(String::isNotEmpty)
                .distinct()
                .sorted()

    companion object {
        private const val SavedHeaderSize = 3

        /** Only account labels, chat/folder identifiers and Boolean membership intents enter saved state. */
        val saver =
            listSaver<ChatFolderAssignmentDraft, String>(
                save = { draft ->
                    listOf(draft.account, draft.runtime.toString(), draft.targets.size.toString()) + draft.targets +
                        draft.intents.flatMap { (folder, include) -> listOf(folder, include.toString()) }
                },
                restore = { saved ->
                    val count = saved[2].toInt()
                    ChatFolderAssignmentDraft(
                        saved[0],
                        saved[1].toInt(),
                        saved.drop(SavedHeaderSize).take(count),
                        saved.drop(SavedHeaderSize + count).chunked(2).associate { it[0] to it[1].toBooleanStrict() },
                    )
                },
            )
    }
}
