package dev.ipf.whitenoise.android.ui.conversation.nostr

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NostrVideoDownloadTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun publicHttpsVideoIsCopiedIntoThePlayerDirectory() {
        val bytes = ByteArray(10_000) { it.toByte() }
        val directory = temporary.newFolder("player")
        val file = download(directory, "https://media.example/clip.mp4") { bytes.toResponseBody() }
        assertNotNull(file)
        assertEquals(directory, file!!.parentFile)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun nonPublicHttpsSourcesAreRefusedBeforeAnyRequest() {
        var requests = 0
        listOf(
            "http://media.example/clip.mp4",
            "file:///data/data/app/databases/secret.db",
            "https://127.0.0.1/clip.mp4",
            "https://media.example:8443/clip.mp4",
            "https://user:pass@media.example/clip.mp4",
            "content://provider/clip.mp4",
            "not a url",
        ).forEach { url ->
            val directory = temporary.newFolder()
            val file =
                download(directory, url) {
                    requests++
                    "x".toResponseBody()
                }
            assertNull(url, file)
            assertEquals(url, 0, directory.listFiles()!!.size)
        }
        assertEquals(0, requests)
    }

    @Test
    fun declaredOrStreamedOversizeAndHttpErrorsLeaveNoFile() {
        val oversize = ByteArray(MAX_TEST_BYTES.toInt() + 1)
        val declared = temporary.newFolder()
        assertNull(download(declared, VIDEO_URL) { oversize.toResponseBody() })
        assertEquals(0, declared.listFiles()!!.size)

        val streamed = temporary.newFolder()
        assertNull(download(streamed, VIDEO_URL) { UnknownLengthBody(oversize) })
        assertEquals(0, streamed.listFiles()!!.size)

        val missing = temporary.newFolder()
        assertNull(download(missing, VIDEO_URL, code = 404) { "not found".toResponseBody() })
        assertEquals(0, missing.listFiles()!!.size)

        val empty = temporary.newFolder()
        assertNull(download(empty, VIDEO_URL) { ByteArray(0).toResponseBody() })
        assertEquals(0, empty.listFiles()!!.size)
    }

    @Test
    fun aDeletedPlayerDirectoryIsNotRecreated() {
        val directory = File(temporary.root, "gone")
        assertNull(download(directory, VIDEO_URL) { "video".toResponseBody() })
        assertEquals(false, directory.exists())
    }

    @Test
    fun orphanSweepKeepsOnlyThisProcessDirectory() {
        val cache = temporary.newFolder("cache")
        val current = nostrVideoDirectory(cache).apply { mkdirs() }
        File(current, "video-1.bin").writeText("open")
        val orphan = File(current.parentFile, "process-old").apply { mkdirs() }
        File(orphan, "video-2.bin").writeText("left behind")
        sweepOrphanedNostrVideoFiles(cache)
        assertEquals(listOf(current.name), current.parentFile!!.list()!!.toList())
        assertEquals("open", File(current, "video-1.bin").readText())
    }

    private fun download(
        directory: File,
        url: String,
        code: Int = 200,
        body: () -> ResponseBody,
    ): File? {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body())
                        .build()
                }.build()
        return runBlocking { downloadNostrVideo(directory, url, client, MAX_TEST_BYTES) }
    }

    /** A chunked response whose size is known only by reading it. */
    private class UnknownLengthBody(
        bytes: ByteArray,
    ) : ResponseBody() {
        private val source = Buffer().write(bytes)

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = source
    }

    private companion object {
        const val VIDEO_URL = "https://media.example/clip.mp4"
        const val MAX_TEST_BYTES = 4_096L
    }
}
