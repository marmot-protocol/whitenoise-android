package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.WhiteNoiseApplication
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
    private val persistenceMutex = Mutex()
    private val stateLock = Any()
    private val preHydrationPicks = mutableListOf<String>()
    private var recentsState by mutableStateOf(emptyList<String>())
    private var isHydrated = false

    val recents: List<String>
        get() = recentsState

    suspend fun hydrateFromDisk() {
        persistenceMutex.withLock {
            ensureHydrated()
        }
    }

    fun onEmojiUsed(emoji: String) {
        synchronized(stateLock) {
            if (!isHydrated) {
                preHydrationPicks += emoji
            }
            recentsState = RecentEmojiList.recordPicked(recentsState, emoji)
        }
        scope.launch {
            persistenceMutex.withLock {
                ensureHydrated()
                saveToDisk(recentsState)
            }
        }
    }

    private suspend fun ensureHydrated() {
        if (isHydrated) return
        val loaded = loadFromDisk()
        synchronized(stateLock) {
            if (isHydrated) return
            val pending = preHydrationPicks.toList()
            preHydrationPicks.clear()
            recentsState = mergePreHydrationPicksWithLoaded(pending, loaded)
            isHydrated = true
        }
    }

    private fun mergePreHydrationPicksWithLoaded(
        pendingOldestFirst: List<String>,
        loaded: List<String>,
    ): List<String> {
        if (pendingOldestFirst.isEmpty()) return loaded
        return pendingOldestFirst.fold(loaded) { acc, pick ->
            RecentEmojiList.recordPicked(acc, pick)
        }
    }
}

@Composable
fun rememberRecentEmojiRecentsOwner(context: Context = LocalContext.current): RecentEmojiRecentsOwner {
    val application = context.applicationContext as WhiteNoiseApplication
    val owner = remember(application) { application.recentEmojiRecentsOwner }
    LaunchedEffect(owner) {
        owner.hydrateFromDisk()
    }
    return owner
}

internal fun WhiteNoiseApplication.createRecentEmojiRecentsOwner(): RecentEmojiRecentsOwner =
    RecentEmojiRecentsOwner(
        scope = applicationScope,
        loadFromDisk = {
            withContext(Dispatchers.IO) {
                RecentEmojiPreferences.load(this@createRecentEmojiRecentsOwner)
            }
        },
        saveToDisk = { emojis ->
            withContext(Dispatchers.IO) {
                RecentEmojiPreferences.save(this@createRecentEmojiRecentsOwner, emojis)
            }
        },
    )
