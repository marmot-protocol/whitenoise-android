package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.CharacterCodingException

class OpenSourceNoticesTest {
    @Test
    fun usesUtf8ByteOffsetsAndRetainsNoticeNamesAndText() {
        val first = "Copyright Café\nApache notice\n".toByteArray()
        val second = "MIT notice\n".toByteArray()
        val index = "0:${first.size} Zulu library\n${first.size}:${second.size} Alpha library\n".toByteArray()
        val notices = parseOpenSourceNotices(index, first + second)
        assertEquals(listOf("Alpha library", "Zulu library"), notices.map(OpenSourceNotice::name))
        assertEquals("Copyright Café\nApache notice\n", notices.last().text)
    }

    @Test
    fun malformedAndTruncatedIndexesFailInsteadOfHidingNotices() {
        for (index in listOf("-1:4 Name", "0:9223372036854775807 Name", "4:3 Name", "0:0 Name", "wrong Name", "0:1 ")) {
            assertThrows(IllegalArgumentException::class.java) {
                parseOpenSourceNotices(index.toByteArray(), "notice".toByteArray())
            }
        }
        assertThrows(CharacterCodingException::class.java) {
            parseOpenSourceNotices("0:1 Name".toByteArray(), byteArrayOf(0xC3.toByte()))
        }
    }
}
