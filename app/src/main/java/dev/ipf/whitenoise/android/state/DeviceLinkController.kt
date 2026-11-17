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

/** MDK's linked-device reads and link commands for one runtime, so tests can stand in for the native library. */
internal interface DeviceLinkNative {
    /** Linking as this device shows it: its own link, the requests it can answer and any moves. */
    suspend fun deviceLinkSnapshot(accountRef: String): DeviceLinkSnapshotFfi

    /** The account's own devices as the device group this device trusts lists them. */
    suspend fun linkedDevices(accountRef: String): List<LinkedDeviceFfi>

    /** On a new device: asks a device already signed in to this account to link this one. */
    suspend fun startDeviceLink(accountRef: String): DeviceLinkSnapshotFfi

    /** On a new device: the user says both screens show the same emoji. */
    suspend fun confirmDeviceLinkMatch(accountRef: String): DeviceLinkSnapshotFfi

    /** On a new device: cancels its link. */
    suspend fun cancelDeviceLink(accountRef: String): DeviceLinkSnapshotFfi

    /** On a device already signed in: links the device behind [promptId] with the user's [choices]. */
    suspend fun linkDevice(
        accountRef: String,
        promptId: String,
        choices: DeviceLinkChoices,
    ): DeviceLinkSnapshotFfi

    /** On a device already signed in: answers [promptId] Not now. */
    suspend fun declineDeviceLink(
        accountRef: String,
        promptId: String,
    ): DeviceLinkSnapshotFfi
}

/**
 * What the approving user chose for a new device. MDK offers both settings on by default: [addToAllChats] adds the
 * device to the chats the account's devices are in, and [bringHistory] brings it those chats' history.
 */
internal data class DeviceLinkChoices(
    val label: String = "",
    val addToAllChats: Boolean = true,
    val bringHistory: Boolean = true,
)

/** MDK's limit on a device name, in UTF-8 bytes. */
internal const val DEVICE_LABEL_MAX_BYTES = 64

/** Whether [label] fits MDK's device-name limit. Only the size is checked here; MDK owns every other rule. */
internal fun deviceLabelFits(label: String) = label.toByteArray(Charsets.UTF_8).size <= DEVICE_LABEL_MAX_BYTES

/** A link command the user started, so the screen can show which action is running. */
internal enum class DeviceLinkCommand {
    Start,
    ConfirmMatch,
    Cancel,
    Link,
    Decline,
}

/** A command that failed, kept until the screen has shown it once. */
internal data class DeviceLinkCommandFailure(
    val command: DeviceLinkCommand,
    val cause: Throwable,
)

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

    /** The link command now running, or null. While one runs every other command is ignored. */
    var running by mutableStateOf<DeviceLinkCommand?>(null)
        private set

    /** The last command failure, until [failureShown]. */
    var failure by mutableStateOf<DeviceLinkCommandFailure?>(null)
        private set

    // One command at a time: MDK's link steps are ordered, and a second tap must not submit a second answer.
    private val commands = Mutex()

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

    /** Asks a device already signed in to link this one. */
    suspend fun start() {
        command(DeviceLinkCommand.Start) { native.startDeviceLink(accountRef) }
    }

    /** Tells MDK the user sees the same emoji on both screens. */
    suspend fun confirmMatch() {
        command(DeviceLinkCommand.ConfirmMatch) { native.confirmDeviceLinkMatch(accountRef) }
    }

    /** Cancels this device's own link. */
    suspend fun cancel() {
        command(DeviceLinkCommand.Cancel) { native.cancelDeviceLink(accountRef) }
    }

    /** Links the device behind [promptId] with the user's [choices]. */
    suspend fun link(
        promptId: String,
        choices: DeviceLinkChoices,
    ) {
        command(DeviceLinkCommand.Link) { native.linkDevice(accountRef, promptId, choices) }
    }

    /** Answers [promptId] Not now. Closing a prompt without calling this answers nothing. */
    suspend fun decline(promptId: String) {
        command(DeviceLinkCommand.Decline) { native.declineDeviceLink(accountRef, promptId) }
    }

    /** Forgets the failure once the screen has shown it, so it is announced once. */
    fun failureShown() {
        failure = null
    }

    /**
     * Runs one link command and publishes the snapshot MDK returns. A command started while another runs is dropped,
     * which makes a double tap harmless. A result or failure that arrives after retirement or an account change is
     * dropped too.
     */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure is shown to the user once.
    private suspend fun command(
        kind: DeviceLinkCommand,
        run: suspend () -> DeviceLinkSnapshotFfi,
    ) {
        if (!current || !commands.tryLock()) return
        try {
            running = kind
            val snapshot = run()
            if (current) {
                linkReads.withLock {
                    link = snapshot
                    linkReadFailed = false
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (current) failure = DeviceLinkCommandFailure(kind, error)
        } finally {
            running = null
            commands.unlock()
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
