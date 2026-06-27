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
    fun devAndStagingKeepLocalRelayCleartextExceptions() {
        listOf("debug", "staging").forEach { sourceSet ->
            val config = File("src/$sourceSet/res/xml/network_security_config.xml")
            val text = config.readText()

            assertTrue(text.contains("localhost"))
            assertTrue(text.contains("127.0.0.1"))
            assertTrue(text.contains("10.0.2.2"))
            assertTrue(text.contains("""<domain includeSubdomains="false">localhost</domain>"""))
            assertTrue(text.contains("""<domain includeSubdomains="false">127.0.0.1</domain>"""))
            assertTrue(text.contains("""<domain includeSubdomains="false">10.0.2.2</domain>"""))
        }
    }
}
