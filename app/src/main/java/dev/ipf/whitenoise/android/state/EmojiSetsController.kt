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
    /** Installed sets plus discovered ones matching [search], from MDK's local projection only. */
    suspend fun sets(search: String?): List<EmojiSetFfi>

    /** Syncs set metadata from the discovery relays; false when some relays could not be reached. */
    suspend fun discover(search: String?): Boolean

    /** Fetches the metadata of one pasted or shared address without touching image hosts. */
    suspend fun fetch(input: String): EmojiSetFfi

    /** The locally known version of [coordinate], or null once it is gone. */
    suspend fun current(coordinate: String): EmojiSetFfi?

    /** Downloads the cover through MDK's hardened fetch, after the user asked to load this set's images. */
    suspend fun cover(set: EmojiSetFfi): ByteArray

    /** Downloads one emoji of the shown version, after the user asked to load this set's images. */
    suspend fun emojiImage(
        set: EmojiSetFfi,
        shortcode: String,
    ): ByteArray

    /** Reads one emoji of the installed snapshot from local storage, without contacting its image host. */
    suspend fun installedEmojiImage(
        set: EmojiSetFfi,
        shortcode: String,
    ): ByteArray

    /** A fresh install control. The caller closes it. */
    fun newInstall(): EmojiSetInstall

    /** Uninstalls [coordinate] from the account's library. */
    suspend fun remove(coordinate: String)
}

/** One version-bound install and its live cancellation handle. */
internal interface EmojiSetInstall : AutoCloseable {
    /** Installs exactly this version, failing with `EmojiSetChanged` if MDK holds a newer one. */
    suspend fun run(set: EmojiSetFfi): EmojiSetInstallResultFfi

    /** MDK's latest progress snapshot for this install. */
    fun progress(): EmojiSetInstallProgressFfi

    /** Asks MDK to stop. [run] then fails with `EmojiSetInstallCancelled` and leaves the library unchanged. */
    fun cancel()
}

/** Keeps all native set operations off the UI dispatcher and tied to a captured owner. */
internal fun WhiteNoiseAppState.emojiSetsBackend(
    account: String,
    generation: Int,
    activeOwner: String?,
): EmojiSetsBackend {
    /** Runs [block] off the main thread, cancelling it when the runtime, active owner or target account changed. */
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
                // Discovery is capped and ordered by date. Installed sets are listed first and match the same search.
                val installed = emojiSets(account, true, search, null)
                val discovered = emojiSets(account, false, search, null)
                (installed + discovered).distinctBy { it.coordinate }
            }

        override suspend fun discover(search: String?): Boolean = scoped { syncEmojiSets(account, search).complete }

        override suspend fun fetch(input: String): EmojiSetFfi = scoped { fetchEmojiSet(account, input) }

        override suspend fun current(coordinate: String): EmojiSetFfi? = scoped { emojiSet(account, coordinate) }

        override suspend fun cover(set: EmojiSetFfi): ByteArray =
            scoped {
                fetchEmojiSetCover(account, set.coordinate, set.eventIdHex).bytes
            }

        override suspend fun emojiImage(
            set: EmojiSetFfi,
            shortcode: String,
        ): ByteArray = scoped { fetchEmojiSetAsset(account, set.coordinate, set.eventIdHex, shortcode).bytes }

        override suspend fun installedEmojiImage(
            set: EmojiSetFfi,
            shortcode: String,
        ): ByteArray = scoped { readInstalledCustomEmoji(account, set.coordinate, shortcode).bytes }

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

/** The typed outcome of the last operation the screen reports. */
internal enum class EmojiSetNotice { Error, NotFound, Changed, Cancelled, DiscoveryIncomplete }

/** Everything the browser and preview render. Only MDK's projection, never a copy of set contents. */
internal data class EmojiSetsState(
    val sets: List<EmojiSetFfi> = emptyList(),
    val preview: EmojiSetFfi? = null,
    val busy: Boolean = false,
    /** The opening relay sync. Rows stay usable; Find, Refresh and the update check wait for it. */
    val discovering: Boolean = false,
    val progress: EmojiSetInstallProgressFfi? = null,
    val cancelling: Boolean = false,
    val notice: EmojiSetNotice? = null,
    val installedWithSkips: Int? = null,
    /** True while [sets] holds the results of a search rather than the full library. */
    val searched: Boolean = false,
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
    private var reads = 0
    private var appliedRead = 0

    /** The opening sync's notice, held while a preview or an operation covers the list. */
    private var heldSyncNotice: EmojiSetNotice? = null

    /** Loads the existing projection without contacting relays or image hosts. */
    suspend fun load() = operate { refresh() }

    /** Contacts only the account's discovery relays, then rereads the local projection. Waits out the opening sync. */
    suspend fun discover(search: String) {
        if (state.discovering) return
        operate {
            heldSyncNotice = null
            val query = search.trim().ifBlank { null }
            val complete = backend.discover(query)
            this.search = query
            refresh()
            if (!complete) state = state.copy(notice = EmojiSetNotice.DiscoveryIncomplete)
        }
    }

    /**
     * Syncs the unfiltered listing without locking the rows, then rereads the projection. True when every relay
     * answered. Starting it clears the opening read's notice. Its own notice waits until no preview or operation
     * covers the list and never replaces a notice already shown there.
     */
    @Suppress("TooGenericExceptionCaught") // Failure only leaves the local projection, which Refresh retries.
    suspend fun discoverInBackground(): Boolean {
        if (closed || state.busy || state.discovering) return false
        heldSyncNotice = null
        state = state.copy(discovering = true, notice = null)
        var notice: EmojiSetNotice? = null
        return try {
            val complete = backend.discover(null)
            refresh()
            if (!complete) notice = EmojiSetNotice.DiscoveryIncomplete
            complete
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = EmojiSetNotice.Error
            false
        } finally {
            state = state.copy(discovering = false)
            heldSyncNotice = notice
            releaseHeldSyncNotice()
        }
    }

    /** Opens metadata for an explicit pasted address. Images require their own action. */
    suspend fun open(input: String) =
        operate {
            show(backend.fetch(input))
            refresh()
        }

    /** Opens a version from the current projection. */
    fun show(set: EmojiSetFfi) {
        state = state.copy(preview = set, notice = null, installedWithSkips = null)
    }

    /** Leaves the set preview without discarding the list. */
    fun dismissPreview() {
        if (state.busy) return
        state = state.copy(preview = null, notice = null, installedWithSkips = null)
        releaseHeldSyncNotice()
    }

    /** Refreshes the native projection, installs a new version and updates the composer on completion. */
    suspend fun install() {
        val set = state.preview ?: return
        operate(set) {
            val current = backend.current(set.coordinate)
            if (closed) return@operate
            if (current == null) {
                state = state.copy(preview = null, notice = EmojiSetNotice.NotFound)
                return@operate
            }
            if (current.eventIdHex != set.eventIdHex) {
                state = state.copy(preview = current, notice = EmojiSetNotice.Changed)
                return@operate
            }
            state = state.copy(preview = current)
            val installed = current.installed
            if (installed != null &&
                (installed.eventIdHex == current.eventIdHex || !installed.updateAvailable)
            ) {
                return@operate
            }
            val result = installWithProgress(current)
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
            state = state.copy(preview = backend.current(set.coordinate))
            refresh()
        }
    }

    /** Rereads the list for the current search. Results apply in start order, so an older read cannot land last. */
    private suspend fun refresh() {
        val read = ++reads
        val sets = backend.sets(search)
        if (read > appliedRead) {
            appliedRead = read
            state = state.copy(sets = sets, searched = search != null)
        }
    }

    /** Runs one install, polling MDK's progress until it finishes, and cancels it if this coroutine is cancelled. */
    private suspend fun installWithProgress(set: EmojiSetFfi): EmojiSetInstallResultFfi =
        backend.newInstall().use { install ->
            activeInstall = install
            try {
                // Main owns disposal and this handoff, before the first native suspension.
                if (closed) {
                    install.cancel()
                    throw MarmotKitException.EmojiSetInstallCancelled()
                }
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

    /** Runs one operation at a time and maps its failure to a notice. A changed set needs a fresh decision. */
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
            releaseHeldSyncNotice()
        }
    }

    /** Shows the held sync notice once the list is visible, unless another notice already reports there. */
    private fun releaseHeldSyncNotice() {
        if (state.preview != null || state.busy) return
        if (state.notice == null) state = state.copy(notice = heldSyncNotice)
        heldSyncNotice = null
    }

    /** Never retries the install with a different version without a second user decision. */
    @Suppress("TooGenericExceptionCaught") // Refresh failure must not make the old version actionable again.
    private suspend fun reloadChanged(set: EmojiSetFfi?) {
        state = state.copy(preview = null, notice = EmojiSetNotice.Changed)
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
