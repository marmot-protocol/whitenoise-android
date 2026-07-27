package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.core.RecentEmojiList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RecentEmojiRecentsOwner(
    private val scope: CoroutineScope,
    private val loadFromDisk: suspend () -> List<String>,
    private val saveToDisk: suspend (List<String>) -> Unit,
) {
    private val writeMutex = Mutex()
    private var recentsState by mutableStateOf(emptyList<String>())

    val recents: List<String>
        get() = recentsState

    suspend fun hydrateFromDiskIfEmpty() {
        if (recentsState.isEmpty()) {
            val loaded = loadFromDisk()
            if (recentsState.isEmpty()) {
                recentsState = loaded
            }
        }
    }

    fun onEmojiUsed(emoji: String) {
        recentsState = RecentEmojiList.recordPicked(recentsState, emoji)
        scope.launch {
            writeMutex.withLock {
                saveToDisk(recentsState)
            }
        }
    }
}

@Composable
fun rememberRecentEmojiRecentsOwner(context: Context = LocalContext.current): RecentEmojiRecentsOwner {
    val scope = rememberCoroutineScope()
    val owner =
        remember(context, scope) {
            RecentEmojiRecentsOwner(
                scope = scope,
                loadFromDisk = {
                    withContext(Dispatchers.IO) {
                        RecentEmojiPreferences.load(context)
                    }
                },
                saveToDisk = { emojis ->
                    withContext(Dispatchers.IO) {
                        RecentEmojiPreferences.save(context, emojis)
                    }
                },
            )
        }
    LaunchedEffect(context) {
        owner.hydrateFromDiskIfEmpty()
    }
    return owner
}
