package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises admission boundaries without native networking or personal data. */
class ProductObservationGateTest {
    /** Work begun before permission is never replayed after a later grant. */
    @Test
    fun preConsentWorkCannotAcquireRetroactivePermission() {
        val gate = ProductObservationGate()
        val old = gate.ticket()
        assertNull(old)
        gate.reset(true)
        var recorded = 0
        gate.record(old) { recorded++ }
        gate.record(gate.ticket()) { recorded++ }
        assertEquals(1, recorded)
    }

    /** Revocation/regrant and account/runtime changes reject in-flight tickets even after permission returns. */
    @Test
    fun invalidationRetiresTicketsAcrossRegrantAndContextChanges() {
        val gate = ProductObservationGate()
        gate.reset(true)
        val old = gate.ticket()
        gate.reset(false)
        gate.reset(true)
        var recorded = 0
        gate.record(old) { recorded++ }
        val accountTicket = gate.ticket()
        gate.invalidate()
        gate.record(accountTicket) { recorded++ }
        gate.record(gate.ticket()) { recorded++ }
        gate.reset()
        gate.record(gate.ticket()) { recorded++ }
        assertEquals(1, recorded)
    }

    /** A meaningful registry expansion gives old relay-only grants a new native consent scope. */
    @Test
    fun entryEventsHaveAFiniteExpandedSchema() {
        val added = androidProductRegistry.filter { it.name !in MarmotTraceSection.hostTimingNames.values }
        assertEquals(listOf("app_android_entry"), added.map { it.name })
        assertEquals(
            listOf("notification", "profile", "share"),
            added
                .single()
                .properties
                .single()
                .choices,
        )
        val entries =
            listOf(
                ProductObservation.NOTIFICATION_ENTRY,
                ProductObservation.PROFILE_ENTRY,
                ProductObservation.SHARE_ENTRY,
            )
        for (event in entries) {
            assertEquals("app_android_entry", event.event().name)
            assertEquals(1, event.event().properties.size)
        }
    }
}
