package dev.ipf.darkmatter.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryResourceConfigTest {
    @Test
    fun serviceVersionCombinesVersionNameAndCode() {
        assertEquals("2026.6.8+4", telemetryServiceVersion("2026.6.8", 4))
    }

    @Test
    fun deploymentEnvironmentUsesAllowedValues() {
        assertEquals("production", telemetryDeploymentEnvironment("production"))
        assertEquals("staging", telemetryDeploymentEnvironment(" Staging "))
        assertEquals("development", telemetryDeploymentEnvironment("development"))
        assertEquals("test", telemetryDeploymentEnvironment("test"))
    }

    @Test
    fun deploymentEnvironmentMapsOldAndroidReleaseDefaultToProduction() {
        assertEquals("production", telemetryDeploymentEnvironment("android-release"))
        assertEquals("production", telemetryDeploymentEnvironment(""))
    }

    @Test
    fun deviceModelIdentifierUsesOnlyModel() {
        assertEquals("SM-S928B", telemetryDeviceModelIdentifier(" SM-S928B "))
        assertNull(telemetryDeviceModelIdentifier(" "))
    }
}
