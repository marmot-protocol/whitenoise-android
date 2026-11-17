package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LinkedDeviceFfi
import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.marmotkit.MarmotEventFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device names itself with its model only while it has no name, and never renames another device. */
class DeviceSelfNamingTest {
    private val renames = mutableListOf<Pair<String, String>>()
    private var roster = listOf<LinkedDeviceFfi>()

    /** The model is trimmed, cut to MDK's limit without splitting a character, and absent when blank. */
    @Test
    fun theModelBecomesAFittingLabel() {
        assertEquals("Pixel 9a", deviceModelLabel("  Pixel 9a "))
        assertNull(deviceModelLabel(" "))
        assertNull(deviceModelLabel(null))
        val long = deviceModelLabel("é".repeat(40))
        assertEquals("é".repeat(32), long)
        assertTrue(deviceLabelFits(long.orEmpty()))
    }

    /** An unnamed device is given its model once, however often the roster is read. */
    @Test
    fun anUnnamedDeviceIsNamedOnce() =
        runTest {
            roster = listOf(device("self", isThisDevice = true), device("other"))
            val naming = naming()

            naming.nameIfUnnamed()
            naming.nameIfUnnamed()

            assertEquals(listOf("self" to "Pixel 9a"), renames)
        }

    /** A placeholder name sent by an approving device is replaced like no name at all. */
    @Test
    fun aPlaceholderNameIsReplaced() =
        runTest {
            roster = listOf(device("self", label = " New device ", isThisDevice = true))

            DeviceSelfNaming(ACCOUNT, "Pixel 9a", { roster }, ::record, setOf("New device")).nameIfUnnamed()

            assertEquals(listOf("self" to "Pixel 9a"), renames)
        }

    /** A name anyone chose stays, and a device being removed is left alone. */
    @Test
    fun aNamedOrLeavingDeviceIsLeftAlone() =
        runTest {
            roster = listOf(device("self", label = "Desk", isThisDevice = true))
            naming().nameIfUnnamed()
            roster = listOf(device("self", isThisDevice = true, removalPending = true))
            naming().nameIfUnnamed()

            assertTrue(renames.isEmpty())
        }

    /** Nothing is renamed without a model, when the read fails, or when the roster lacks this device. */
    @Test
    fun nothingIsNamedWithoutAModelOrARoster() =
        runTest {
            roster = listOf(device("self", isThisDevice = true))
            naming(model = null).nameIfUnnamed()
            DeviceSelfNaming(ACCOUNT, "Pixel 9a", { error("offline") }, ::record).nameIfUnnamed()
            roster = listOf(device("other"))
            naming().nameIfUnnamed()

            assertTrue(renames.isEmpty())
        }

    /** A refused rename is not retried for the same slot. */
    @Test
    fun aRefusedRenameIsNotRepeated() =
        runTest {
            roster = listOf(device("self", isThisDevice = true))
            var attempts = 0
            val refuse: suspend (String, String, String) -> Unit = { _, _, _ -> error("refused ${++attempts}") }
            val naming = DeviceSelfNaming(ACCOUNT, "Pixel 9a", { roster }, refuse)

            naming.nameIfUnnamed()
            naming.nameIfUnnamed()

            assertEquals(1, attempts)
        }

    /** The roster is checked again when MDK reports this account's roster changed, and not for another account. */
    @Test
    fun aRosterChangeNamesADeviceThatJustLinked() =
        runTest {
            val events =
                ArrayDeque(
                    listOf<MarmotEventFfi>(
                        MarmotEventFfi.LinkedDevicesChanged("id", "other-account"),
                        MarmotEventFfi.LinkedDevicesChanged("id", ACCOUNT),
                    ),
                )

            naming().observe {
                val event = events.removeFirstOrNull()
                if (event is MarmotEventFfi.LinkedDevicesChanged && event.accountLabel == ACCOUNT) {
                    roster = listOf(device("self", isThisDevice = true))
                }
                event
            }

            assertEquals(listOf("self" to "Pixel 9a"), renames)
        }

    /** A namer over the scripted roster that records each rename. */
    private fun naming(model: String? = "Pixel 9a") = DeviceSelfNaming(ACCOUNT, model, { roster }, ::record)

    /** Records one rename as slot to label. */
    @Suppress("UNUSED_PARAMETER") // The account is fixed in these tests.
    private fun record(
        accountRef: String,
        slotIdHex: String,
        label: String,
    ) {
        renames += slotIdHex to label
    }

    /** One roster device as MDK reports it. */
    private fun device(
        slot: String,
        label: String = "",
        isThisDevice: Boolean = false,
        removalPending: Boolean = false,
    ) = LinkedDeviceFfi(
        slotIdHex = slot,
        isThisDevice = isThisDevice,
        label = label,
        client = "White Noise",
        platform = "Android",
        linkedAt = 1uL,
        linkedBySlotIdHex = null,
        status = LinkedDeviceStatusFfi.ACTIVE,
        addToAllChats = true,
        bringHistory = false,
        keepWhileInactive = false,
        lastActiveAt = null,
        inactive = false,
        removalPending = removalPending,
    )

    private companion object {
        const val ACCOUNT = "account"
    }
}
