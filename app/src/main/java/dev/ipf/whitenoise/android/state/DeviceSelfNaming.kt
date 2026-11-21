package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LinkedDeviceFfi
import dev.ipf.marmotkit.MarmotEventFfi

/**
 * The name this device gives itself: its model, cut to MDK's device-name limit without splitting a character, or
 * null when the platform reports no model.
 */
internal fun deviceModelLabel(model: String?): String? {
    var label = model.orEmpty().trim()
    while (!deviceLabelFits(label)) label = label.dropLast(1)
    return label.trim().ifEmpty { null }
}

/**
 * Names this device with its model while MDK's roster lists it without a name, or under one of the [placeholders]
 * an approving device sends when its user typed none, so the account's devices can tell it apart. A name anyone
 * chose is never replaced, and each roster slot is tried once per account session, so a refused rename is not repeated.
 */
internal class DeviceSelfNaming(
    private val accountRef: String,
    private val modelLabel: String?,
    private val readDevices: suspend (accountRef: String) -> List<LinkedDeviceFfi>,
    private val rename: suspend (accountRef: String, slotIdHex: String, label: String) -> Unit,
    private val placeholders: Set<String> = emptySet(),
) {
    private val attempted = mutableSetOf<String>()

    /** Reads the roster and names this device when it has no name yet. A failed read or rename changes nothing. */
    suspend fun nameIfUnnamed() {
        val label = modelLabel ?: return
        val devices = runCatchingCancellable { readDevices(accountRef) }.getOrNull().orEmpty()
        val own = devices.firstOrNull { it.isThisDevice && !it.removalPending }
        val unnamed = own?.takeIf { it.label.isBlank() || isPlaceholder(it.label) }
        if (unnamed != null && attempted.add(unnamed.slotIdHex)) {
            runCatchingCancellable { rename(accountRef, unnamed.slotIdHex, label) }
        }
    }

    /**
     * Whether [label] is one of the [placeholders], ignoring surrounding space and letter case, since approving
     * devices capitalise the same default name differently.
     */
    private fun isPlaceholder(label: String): Boolean = placeholders.any { it.equals(label.trim(), ignoreCase = true) }

    /** Checks once, then again whenever MDK reports this account's roster changed, until the stream ends. */
    suspend fun observe(nextEvent: suspend () -> MarmotEventFfi?) {
        nameIfUnnamed()
        while (true) {
            val event = nextEvent() ?: return
            if (event is MarmotEventFfi.LinkedDevicesChanged && event.accountLabel == accountRef) nameIfUnnamed()
        }
    }
}
