package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val COMPOSER_TOOLBAR_KEY = "composer_toolbar_v1"

/** The AppState-owned host preference is shared across accounts, unlike per-draft expansion geometry. */
internal class ComposerToolbarPreference(
    private val preferences: SharedPreferences,
    private val scope: CoroutineScope,
    private val commitMutex: Mutex,
) {
    var value by mutableStateOf(read())
        private set
    var saving by mutableStateOf(false)
        private set

    /** Latest local intent is retained across editor dismissal, including a retryable failure. */
    var requestedValue by mutableStateOf(value)
        private set
    var failed by mutableStateOf(false)
        private set
    private var latestRequest = 0
    private var pendingSaves = 0

    private fun read(): ComposerToolbarSettings =
        ComposerToolbarSettings.decode(
            runCatching {
                preferences.getString(COMPOSER_TOOLBAR_KEY, null)
            }.getOrNull(),
        )

    /** Serialize every completed edit, including rapid changes, and publish only successful disk writes. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun save(
        settings: ComposerToolbarSettings,
        onComplete: (Boolean) -> Unit,
    ) {
        val next = settings.normalized()
        val request = ++latestRequest
        requestedValue = next
        failed = false
        if (!saving && next == value) {
            onComplete(true)
            return
        }
        pendingSaves++
        saving = true
        scope.launch(start = CoroutineStart.ATOMIC) {
            var completed = false
            try {
                commitMutex.withLock {
                    val previous = value
                    val committed = commit(next, previous)
                    withContext(NonCancellable) {
                        if (committed) value = next
                        if (request == latestRequest) failed = !committed
                        completed = true
                        onComplete(committed)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    if (!completed) {
                        if (request == latestRequest) failed = true
                        onComplete(false)
                    }
                    pendingSaves--
                    saving = pendingSaves > 0
                }
            }
        }
    }

    /** SharedPreferences mutates memory before returning a disk failure; restore the last confirmed value. */
    private suspend fun commit(
        next: ComposerToolbarSettings,
        previous: ComposerToolbarSettings,
    ): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            val success =
                runCatching {
                    preferences.edit().putString(COMPOSER_TOOLBAR_KEY, next.encode()).commit()
                }.getOrDefault(false)
            if (!success) {
                runCatching {
                    preferences.edit().putString(COMPOSER_TOOLBAR_KEY, previous.encode()).commit()
                }
            }
            success
        }
}
