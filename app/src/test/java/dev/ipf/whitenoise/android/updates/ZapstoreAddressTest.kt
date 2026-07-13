package dev.ipf.whitenoise.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZapstoreAddressTest {
    @Test
    fun extractsVersionFromReleaseDTag() {
        assertEquals("2026.6.20", ZapstoreAddress.versionFromReleaseDTag("org.parres.darkmatter@2026.6.20", "org.parres.darkmatter"))
        assertNull(ZapstoreAddress.versionFromReleaseDTag("org.parres.whitenoise@2026.6.20", "org.parres.darkmatter"))
    }

    @Test
    fun rejectsNonCalVerReleaseDTagSuffix() {
        assertNull(ZapstoreAddress.versionFromReleaseDTag("org.parres.darkmatter@beta", "org.parres.darkmatter"))
        assertNull(ZapstoreAddress.versionFromReleaseDTag("org.parres.darkmatter@2026.6.20-beta", "org.parres.darkmatter"))
    }
}
