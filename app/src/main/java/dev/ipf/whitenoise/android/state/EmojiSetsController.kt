package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.marmotkit.EmojiSetInstallControlFfi
import dev.ipf.marmotkit.EmojiSetInstallProgressFfi
import dev.ipf.marmotkit.EmojiSetInstallResultFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reads and mutates sets for the account and runtime that opened the screen. */
internal interface EmojiSetsBackend {
    suspend fun sets(search: String?): List<EmojiSetFfi>

    suspend fun discover(search: String?): Boolean

    suspend fun fetch(input: String): EmojiSetFfi

    suspend fun current(coordinate: String): EmojiSetFfi?

    suspend fun preview(set: EmojiSetFfi): ByteArray

    fun newInstall(): EmojiSetInstall

    suspend fun remove(coordinate: String)
}

/** One version-bound install and its live cancellation handle. */
internal interface EmojiSetInstall : AutoCloseable {
    suspend fun run(set: EmojiSetFfi): EmojiSetInstallResultFfi

    fun progress(): EmojiSetInstallProgressFfi

    fun cancel()
}

/** Keeps all native set operations off the UI dispatcher and tied to a captured owner. */
internal fun WhiteNoiseAppState.emojiSetsBackend(
    account: String,
    generation: Int,
    activeOwner: String?,
): EmojiSetsBackend {
    suspend fun <T> scoped(block: suspend dev.ipf.marmotkit.MarmotInterface.() -> T): T =
        marmotIo {
            val targetExists = account == activeOwner || accounts.any { it.label == account }
            if (runtimeGeneration != generation || activeAccountRef != activeOwner || !targetExists) {
                throw CancellationException("emoji set owner changed")
            }
            block()
        }

    return object : EmojiSetsBackend {
        override suspend fun sets(search: String?): List<EmojiSetFfi> =
            scoped {
                // Discovery is capped and ordered by date. Always include the full installed projection.
                val installed = emojiSets(account, true, null, null)
                val discovered = emojiSets(account, false, search, null)
                (installed + discovered).distinctBy { it.coordinate }
            }

        override suspend fun discover(search: String?): Boolean = scoped { syncEmojiSets(account, search).complete }

        override suspend fun fetch(input: String): EmojiSetFfi = scoped { fetchEmojiSet(account, input) }

        override suspend fun current(coordinate: String): EmojiSetFfi? = scoped { emojiSet(account, coordinate) }

        override suspend fun preview(set: EmojiSetFfi): ByteArray =
            scoped {
                if (set.imageUrl != null) {
                    fetchEmojiSetCover(account, set.coordinate, set.eventIdHex).bytes
                } else {
                    fetchEmojiSetAsset(account, set.coordinate, set.eventIdHex, set.emoji.first().shortcode).bytes
                }
            }

        override fun newInstall(): EmojiSetInstall {
            val control = EmojiSetInstallControlFfi()
            return object : EmojiSetInstall {
                override suspend fun run(set: EmojiSetFfi): EmojiSetInstallResultFfi =
                    scoped { installEmojiSet(account, set.coordinate, set.eventIdHex, control) }

                override fun progress(): EmojiSetInstallProgressFfi = control.progress()

                override fun cancel() = control.cancel()

                override fun close() = control.close()
            }
        }

        override suspend fun remove(coordinate: String) {
            scoped { uninstallEmojiSet(account, coordinate) }
        }
    }
}

internal enum class EmojiSetNotice { Error, NotFound, Changed, Cancelled, DiscoveryIncomplete }

internal data class EmojiSetsState(
    val sets: List<EmojiSetFfi> = emptyList(),
    val preview: EmojiSetFfi? = null,
    val artwork: ByteArray? = null,
    val busy: Boolean = false,
    val progress: EmojiSetInstallProgressFfi? = null,
    val cancelling: Boolean = false,
    val notice: EmojiSetNotice? = null,
    val installedWithSkips: Int? = null,
)

/** Owns presentation for one screen. Installed state and the version check remain in MarmotKit. */
@Suppress("TooManyFunctions") // The browser, preview and install share one screen owner.
internal class EmojiSetsController(
    private val backend: EmojiSetsBackend,
    private val libraryChanged: () -> Unit,
) {
    var state by mutableStateOf(EmojiSetsState())
        private set
    private var activeInstall: EmojiSetInstall? = null
    private var closed = false
    private var search: String? = null

    /** Loads the existing projection without contacting relays or image hosts. */
    suspend fun load() = operate { refresh() }

    /** Contacts only the account's discovery relays, then rereads the local projection. */
    suspend fun discover(search: String) =
        operate {
            val query = search.trim().ifBlank { null }
            val complete = backend.discover(query)
            this.search = query
            refresh()
            if (!complete) state = state.copy(notice = EmojiSetNotice.DiscoveryIncomplete)
        }

    /** Opens metadata for an explicit pasted address. Artwork requires its own action. */
    suspend fun open(input: String) =
        operate {
            show(backend.fetch(input))
            refresh()
        }

    /** Opens a version from the current projection. */
    fun show(set: EmojiSetFfi) {
        state = state.copy(preview = set, artwork = null, notice = null, installedWithSkips = null)
    }

    /** Leaves the set preview without discarding the list. */
    fun dismissPreview() {
        if (!state.busy) state = state.copy(preview = null, artwork = null, notice = null, installedWithSkips = null)
    }

    /** Downloads one explicit preview through MDK's hardened image fetch. */
    suspend fun loadArtwork() {
        val set = state.preview ?: return
        operate(set) { state = state.copy(artwork = backend.preview(set)) }
    }

    /** Installs exactly the displayed version and updates the composer projection on completion. */
    suspend fun install() {
        val set = state.preview ?: return
        operate(set) {
            val result = installWithProgress(set)
            libraryChanged()
            show(result.set)
            state = state.copy(installedWithSkips = result.skipped.size.takeIf { it > 0 })
            refresh()
        }
    }

    /** Cancels native work while keeping the progress surface until the result is known. */
    fun cancelInstall() {
        activeInstall?.let {
            state = state.copy(cancelling = true)
            it.cancel()
        }
    }

    /** Cancels an unfinished install when its UI or account owner disappears. */
    fun close() {
        closed = true
        cancelInstall()
    }

    /** Uninstalls the snapshot while retaining the discovered metadata and sent messages. */
    suspend fun remove() {
        val set = state.preview ?: return
        operate(set) {
            backend.remove(set.coordinate)
            libraryChanged()
            state = state.copy(preview = backend.current(set.coordinate), artwork = null)
            refresh()
        }
    }

    private suspend fun refresh() {
        state = state.copy(sets = backend.sets(search))
    }

    private suspend fun installWithProgress(set: EmojiSetFfi): EmojiSetInstallResultFfi =
        backend.newInstall().use { install ->
            activeInstall = install
            try {
                state = state.copy(progress = install.progress())
                coroutineScope {
                    val poll =
                        launch {
                            while (isActive) {
                                delay(PROGRESS_INTERVAL_MS)
                                state = state.copy(progress = install.progress())
                            }
                        }
                    try {
                        install.run(set)
                    } finally {
                        poll.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                install.cancel()
                throw cancelled
            } finally {
                activeInstall = null
                state = state.copy(progress = null, cancelling = false)
            }
        }

    @Suppress("TooGenericExceptionCaught") // FFI and runtime failures share a retryable presentation.
    private suspend fun operate(
        set: EmojiSetFfi? = null,
        action: suspend () -> Unit,
    ) {
        if (closed || state.busy) return
        state = state.copy(busy = true, notice = null, installedWithSkips = null)
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MarmotKitException.EmojiSetChanged) {
            reloadChanged(set)
        } catch (_: MarmotKitException.EmojiSetInstallCancelled) {
            state = state.copy(notice = EmojiSetNotice.Cancelled)
        } catch (_: MarmotKitException.EmojiSetNotFound) {
            state = state.copy(notice = EmojiSetNotice.NotFound)
        } catch (_: Exception) {
            state = state.copy(notice = EmojiSetNotice.Error)
        } finally {
            state = state.copy(busy = false)
        }
    }

    /** Never retries the install with a different version without a second user decision. */
    @Suppress("TooGenericExceptionCaught") // Refresh failure must not make the old version actionable again.
    private suspend fun reloadChanged(set: EmojiSetFfi?) {
        state = state.copy(preview = null, artwork = null, notice = EmojiSetNotice.Changed)
        try {
            val current = set?.let { backend.current(it.coordinate) }
            state = state.copy(preview = current)
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the changed-version notice and require reopening the set.
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
