package dev.ipf.whitenoise.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class NetworkSecurityConfigTest {
    @Test
    fun mainNetworkSecurityConfigDisallowsCleartextWithoutDevHosts() {
        val config = appModuleFile("src/main/res/xml/network_security_config.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
        val domains = document.getElementsByTagName("domain")

        assertEquals(0, domains.length)
        assertTrue(config.readText().contains("""cleartextTrafficPermitted="false""""))
        assertFalse(config.readText().contains("10.0.2.2"))
    }

    @Test
    fun devAndStagingKeepLoopbackRelayCleartextExceptions() {
        listOf("debug", "staging").forEach { sourceSet ->
            val text = appModuleSource("src/$sourceSet/res/xml/network_security_config.xml")

            assertTrue(text.contains("""<domain includeSubdomains="false">localhost</domain>"""))
            assertTrue(text.contains("""<domain includeSubdomains="false">127.0.0.1</domain>"""))
        }
    }

    @Test
    fun emulatorHostAliasCleartextStaysDebugOnly() {
        val debug = appModuleSource("src/debug/res/xml/network_security_config.xml")
        assertTrue(debug.contains("""<domain includeSubdomains="false">10.0.2.2</domain>"""))

        // Staging is release-signed and runs on real hardware, where 10.0.2.2
        // is a routable LAN address rather than the emulator loopback alias.
        assertFalse(cleartextDomains("staging").contains("10.0.2.2"))
    }

    /** Release-signed configs trust only system CAs, so no user or bundled anchor can intercept relays. */
    @Test
    fun shippedConfigsTrustOnlySystemCertificates() {
        listOf("main", "staging").forEach { sourceSet ->
            val document = parse(sourceSet)
            listOf("trust-anchors", "certificates", "debug-overrides").forEach { tag ->
                assertEquals("$sourceSet must not declare <$tag>", 0, document.getElementsByTagName(tag).length)
            }
        }
        assertEquals(listOf("localhost", "127.0.0.1"), cleartextDomains("staging"))
    }

    private fun parse(sourceSet: String) =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(appModuleFile("src/$sourceSet/res/xml/network_security_config.xml"))

    private fun cleartextDomains(sourceSet: String): List<String> {
        val domains = parse(sourceSet).getElementsByTagName("domain")
        return (0 until domains.length).map { domains.item(it).textContent.trim() }
    }
}
