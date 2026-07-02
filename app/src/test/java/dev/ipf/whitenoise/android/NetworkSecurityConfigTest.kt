package dev.ipf.whitenoise.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class NetworkSecurityConfigTest {
    @Test
    fun mainNetworkSecurityConfigDisallowsCleartextWithoutDevHosts() {
        val config = File("src/main/res/xml/network_security_config.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
        val domains = document.getElementsByTagName("domain")

        assertEquals(0, domains.length)
        assertTrue(config.readText().contains("""cleartextTrafficPermitted="false""""))
        assertFalse(config.readText().contains("10.0.2.2"))
    }

    @Test
    fun devAndStagingKeepLoopbackRelayCleartextExceptions() {
        listOf("debug", "staging").forEach { sourceSet ->
            val text = File("src/$sourceSet/res/xml/network_security_config.xml").readText()

            assertTrue(text.contains("""<domain includeSubdomains="false">localhost</domain>"""))
            assertTrue(text.contains("""<domain includeSubdomains="false">127.0.0.1</domain>"""))
        }
    }

    @Test
    fun emulatorHostAliasCleartextStaysDebugOnly() {
        val debug = File("src/debug/res/xml/network_security_config.xml").readText()
        assertTrue(debug.contains("""<domain includeSubdomains="false">10.0.2.2</domain>"""))

        // Staging is release-signed and runs on real hardware, where 10.0.2.2
        // is a routable LAN address rather than the emulator loopback alias.
        assertFalse(cleartextDomains("staging").contains("10.0.2.2"))
    }

    private fun cleartextDomains(sourceSet: String): List<String> {
        val config = File("src/$sourceSet/res/xml/network_security_config.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
        val domains = document.getElementsByTagName("domain")
        return (0 until domains.length).map { domains.item(it).textContent.trim() }
    }
}
