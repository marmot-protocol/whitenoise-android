package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.ChatHistoryImportFfi
import dev.ipf.marmotkit.ChatHistoryImportStateFfi
import dev.ipf.marmotkit.HistoryImportProvenanceFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors MDK's list of chats whose history from another of the account's devices is arriving or has arrived. MDK
 * owns the transfer: this holder keeps no copy of the history, starts no download and has no retry timer. It
 * re-reads the list when MDK says it changed.
 */
@Stable
internal class ChatHistoryImports(
    private val accountRef: String,
    private val readImports: suspend (accountRef: String) -> List<ChatHistoryImportFfi>,
    private val owns: () -> Boolean = { true },
) {
    var byGroup by mutableStateOf<Map<String, ChatHistoryImportStateFfi>>(emptyMap())
        private set

    // Serializes read-and-publish so an older read can never replace a newer one.
    private val reads = Mutex()

    /** MDK's latest state for [groupIdHex], or null when the chat has no history import. */
    fun state(groupIdHex: String): ChatHistoryImportStateFfi? = byGroup[groupIdHex.lowercase()]

    /** Re-reads the list. A failed read keeps what is shown, and a read for a retired account is dropped. */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure keeps the prior list.
    suspend fun refresh() {
        reads.withLock {
            val imports =
                try {
                    readImports(accountRef)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    return
                }
            if (owns()) byGroup = imports.associate { it.groupIdHex.lowercase() to it.state }
        }
    }

    /** Reads once, then re-reads whenever MDK reports this account's history imports changed. */
    @Suppress("TooGenericExceptionCaught") // A closed subscription is reopened by the lifecycle owner.
    suspend fun observe(nextEvent: suspend () -> MarmotEventFfi?) {
        refresh()
        try {
            while (true) {
                val event = nextEvent() ?: return
                if (event is MarmotEventFfi.ChatHistoryImportsChanged && event.accountLabel == accountRef) refresh()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return
        }
    }
}

/** How far a chat's history transfer is, while it is still under way. A settled import has no transfer state. */
internal enum class ChatHistoryTransfer {
    Receiving,
    Retrying,
}

/**
 * The transfer under way for MDK's [state], or null when there is none or it has settled. Retrying is MDK's own
 * report that an attempt failed and will be made again; nothing here decides to retry.
 */
internal fun chatHistoryTransfer(state: ChatHistoryImportStateFfi?): ChatHistoryTransfer? =
    when (state) {
        is ChatHistoryImportStateFfi.Receiving ->
            if (state.retrying) ChatHistoryTransfer.Retrying else ChatHistoryTransfer.Receiving
        is ChatHistoryImportStateFfi.Imported, null -> null
    }

/** Where an imported row came from, as the timeline labels it. */
internal sealed interface ImportedHistorySource {
    /** One of the account's devices, by the name MDK now has for it. */
    data class Device(
        val label: String,
    ) : ImportedHistorySource

    /** One of the account's devices whose name MDK does not have: unnamed, no longer linked or not yet read. */
    data object AnotherDevice : ImportedHistorySource

    /** A backup. The archive's identity proves nothing about who wrote a message, so none is shown. */
    data object Backup : ImportedHistorySource
}

/**
 * The source of [record] when it arrived in imported history, or null for a row that arrived live. MDK's typed
 * provenance decides; the device name comes from the chat's current import [state] when it names the same device,
 * so a rename shows without re-reading rows, and otherwise from the row itself.
 */
internal fun importedHistorySource(
    record: TimelineMessageRecordFfi?,
    state: ChatHistoryImportStateFfi?,
): ImportedHistorySource? {
    val provenance = record?.historyImportProvenance
    if (provenance is HistoryImportProvenanceFfi.Backup) return ImportedHistorySource.Backup
    // The typed provenance names the device; the older sibling-only field is the fallback for rows without it.
    val typedSlot = (provenance as? HistoryImportProvenanceFfi.Device)?.deviceSlotIdHex
    val slot = typedSlot ?: record?.importedFrom?.deviceSlotIdHex
    return slot?.let { deviceSource(it, record?.importedFrom?.deviceLabel, state) }
}

/** The label for the device in [slot]: its current name from the chat's import [state], else the row's own. */
private fun deviceSource(
    slot: String,
    rowLabel: String?,
    state: ChatHistoryImportStateFfi?,
): ImportedHistorySource {
    val current = (state as? ChatHistoryImportStateFfi.Imported)?.from?.takeIf { it.deviceSlotIdHex == slot }
    val label = (current?.deviceLabel ?: rowLabel).orEmpty()
    return if (label.isBlank()) ImportedHistorySource.AnotherDevice else ImportedHistorySource.Device(label)
}

/**
 * The label to draw between [older] and [current]: the source of the imported rows that end at [older], when
 * [current] is live or came from somewhere else. Null inside a run from one source and between live rows, so the
 * label marks exactly where imported history gives way.
 */
internal fun importedHistoryBoundary(
    older: TimelineMessageRecordFfi?,
    current: TimelineMessageRecordFfi?,
    state: ChatHistoryImportStateFfi?,
): ImportedHistorySource? {
    val before = importedHistorySource(older, state)
    return before.takeIf { it != importedHistorySource(current, state) }
}
