package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.LinkedDeviceFfi
import dev.ipf.marmotkit.MarmotEventFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** MDK's linked-device reads for one runtime, so tests can stand in for the native library. */
internal interface DeviceLinkNative {
    /** Linking as this device shows it: its own link, the requests it can answer and any moves. */
    suspend fun deviceLinkSnapshot(accountRef: String): DeviceLinkSnapshotFfi

    /** The account's own devices as the device group this device trusts lists them. */
    suspend fun linkedDevices(accountRef: String): List<LinkedDeviceFfi>
}

/**
 * The roster as the screen last read it. [roster] stays null until the first read succeeds, and a later failed read
 * sets [readFailed] while keeping the rows, so a failure is never shown as an empty list.
 */
internal data class LinkedDeviceRosterState(
    val roster: LinkedDeviceRoster? = null,
    val refreshing: Boolean = false,
    val readFailed: Boolean = false,
)

/**
 * Mirrors one account's linked-device state from MDK, which owns it durably. Nothing here is persisted: every value
 * is the latest native read, re-read when MDK reports a change.
 *
 * One controller belongs to one account on one runtime. [owns] answers whether that pairing is still the active one,
 * and [retire] ends it for good, so a read that returns after an account switch or runtime teardown is dropped
 * instead of being shown under another account.
 */
@Stable
internal class DeviceLinkController(
    val accountRef: String,
    private val native: DeviceLinkNative,
    private val owns: () -> Boolean = { true },
) {
    var roster by mutableStateOf(LinkedDeviceRosterState())
        private set

    var link by mutableStateOf<DeviceLinkSnapshotFfi?>(null)
        private set

    var linkReadFailed by mutableStateOf(false)
        private set

    // Each kind of read is serialized so an older read can never replace a newer one.
    private val rosterReads = Mutex()
    private val linkReads = Mutex()

    @Volatile
    private var retired = false

    /** Whether results may still be published: not retired, and still the active account on its runtime. */
    val current: Boolean
        get() = !retired && owns()

    /** Stops publishing for good. Reads already in flight finish natively and are then dropped. */
    fun retire() {
        retired = true
    }

    /** Re-reads the roster. A failed read keeps the rows already shown and marks the failure for a retry. */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure keeps the prior rows.
    suspend fun refreshRoster() {
        rosterReads.withLock {
            if (!current) return
            roster = roster.copy(refreshing = true)
            val devices =
                try {
                    native.linkedDevices(accountRef)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    null
                }
            if (!current) return
            roster =
                if (devices == null) {
                    roster.copy(refreshing = false, readFailed = true)
                } else {
                    LinkedDeviceRosterState(roster = linkedDeviceRoster(devices))
                }
        }
    }

    /** Re-reads the link snapshot. A failed read keeps the snapshot already shown. */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure keeps the prior snapshot.
    suspend fun refreshLink() {
        linkReads.withLock {
            if (!current) return
            val snapshot =
                try {
                    native.deviceLinkSnapshot(accountRef)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    null
                }
            if (!current) return
            linkReadFailed = snapshot == null
            if (snapshot != null) link = snapshot
        }
    }

    /**
     * Reads both once, then re-reads whichever MDK reports changed for this account until the stream ends. Change
     * events that arrive while a read is running collapse into one further read, so a burst costs one refresh and
     * nothing polls. MDK also sends both events when the account's worker opens or reconnects.
     */
    @Suppress("TooGenericExceptionCaught") // A closed subscription is reopened by the lifecycle owner.
    suspend fun observe(nextEvent: suspend () -> MarmotEventFfi?) {
        coroutineScope {
            val rosterInvalidations = Channel<Unit>(Channel.CONFLATED)
            val linkInvalidations = Channel<Unit>(Channel.CONFLATED)
            launch { rosterInvalidations.consumeEach { refreshRoster() } }
            launch { linkInvalidations.consumeEach { refreshLink() } }
            rosterInvalidations.trySend(Unit)
            linkInvalidations.trySend(Unit)
            try {
                while (true) {
                    when (deviceLinkInvalidation(nextEvent() ?: break, accountRef)) {
                        DeviceLinkInvalidation.Roster -> rosterInvalidations.trySend(Unit)
                        DeviceLinkInvalidation.Link -> linkInvalidations.trySend(Unit)
                        null -> Unit
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // The owner reopens the stream; the reads already requested still finish below.
            } finally {
                rosterInvalidations.close()
                linkInvalidations.close()
            }
        }
    }
}

/** Which native read an event makes stale. */
internal enum class DeviceLinkInvalidation {
    Roster,
    Link,
}

/** The read [event] makes stale for [accountRef], or null when it concerns another account or other state. */
internal fun deviceLinkInvalidation(
    event: MarmotEventFfi,
    accountRef: String,
): DeviceLinkInvalidation? =
    when (event) {
        is MarmotEventFfi.LinkedDevicesChanged ->
            DeviceLinkInvalidation.Roster.takeIf { event.accountLabel == accountRef }
        is MarmotEventFfi.DeviceLinkChanged ->
            DeviceLinkInvalidation.Link.takeIf { event.accountLabel == accountRef }
        else -> null
    }
