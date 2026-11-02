package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountAttachmentCursor
import dev.ipf.marmotkit.AccountAttachmentPageReadFfi
import dev.ipf.marmotkit.AccountAttachmentQueryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.ui.chats.GlobalAttachmentLibraryQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * The Files & media library against the packaged MarmotKit account attachment index (#2000). Real attachments
 * are sent into two chats, then read back through the app's pager and query: every slot exactly once, newest
 * first with album order kept, chat and kind filters, bounded one-slot pages, an unchanged probe that reads
 * nothing, and a new send arriving at the head.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class AccountAttachmentLibraryFfiIntegrationTest {
    /** One sequence keeps the native setup, every query and the addition against the same account. */
    @Test
    @Suppress("LongMethod") // Keep the end-to-end native boundary in one auditable sequence.
    fun pagesTheAccountIndexAcrossChats() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "account-library-ffi-${UUID.randomUUID()}").apply { mkdirs() }
            val marmot = Marmot(root.absolutePath, MarmotClient.bootstrapRelays, marmotDeploymentWhiteNoise())
            var accountRef: String? = null
            var closed = false
            try {
                withTimeout(TEST_TIMEOUT_MS) {
                    marmot.start()
                    val account = marmot.createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
                    accountRef = account.label
                    val albums = marmot.createGroup(account.label, "Library albums", emptyList(), null)
                    val files = marmot.createGroup(account.label, "Library files", emptyList(), null)
                    // A second apart, so newest-first order never rests on a same-second tie-break.
                    marmot.uploadMedia(account.label, albums, upload(photo("first.png"), photo("second.png")))
                    delay(SEND_SPACING_MS)
                    marmot.uploadMedia(account.label, files, upload(document("notes.pdf")))
                    delay(SEND_SPACING_MS)
                    marmot.uploadMedia(account.label, files, upload(photo("latest.png")))

                    /** A pager over the account index for [query], counting native page reads. */
                    fun pager(
                        query: GlobalAttachmentLibraryQuery,
                        limit: UInt = ACCOUNT_ATTACHMENT_PAGE_LIMIT,
                    ): Pair<AccountAttachmentPager, CountingReader> {
                        val reader = CountingReader(marmot, account.label, query.toFfi(), limit)
                        return AccountAttachmentPager(reader, query::admits) to reader
                    }

                    val all = GlobalAttachmentLibraryQuery(groups = null)
                    val (everything, everythingReads) = pager(all, limit = 1u)
                    everything.refresh()
                    while (everything.state.hasMore) everything.loadMore()
                    assertEquals(
                        listOf("latest.png", "notes.pdf", "first.png", "second.png"),
                        everything.state.entries.map { it.fileName() },
                    )
                    assertEquals(
                        listOf(files, files, albums, albums).map { it.lowercase() },
                        everything.state.entries.map { it.groupIdHex.lowercase() },
                    )
                    assertTrue("one-slot pages never read past the end", everythingReads.calls <= 5)

                    val readsBeforeProbe = everythingReads.calls
                    everything.refresh()
                    assertEquals("an unchanged index reads no page", readsBeforeProbe, everythingReads.calls)

                    delay(SEND_SPACING_MS)
                    marmot.uploadMedia(account.label, albums, upload(photo("newest.png")))
                    everything.refresh()
                    assertEquals(
                        "newest.png",
                        everything.state.entries
                            .first()
                            .fileName(),
                    )
                    assertEquals(5, everything.state.entries.size)
                    everything.close()

                    val (oneChat, _) = pager(all.copy(groups = setOf(albums.lowercase())))
                    oneChat.refresh()
                    assertEquals(
                        listOf("newest.png", "first.png", "second.png"),
                        oneChat.state.entries.map { it.fileName() },
                    )
                    oneChat.close()

                    val documents = all.copy(kinds = setOf(GlobalSearchContentKind.FILES_DOCUMENTS))
                    val (filesOnly, filesReads) = pager(documents, limit = 1u)
                    filesOnly.refresh()
                    while (filesOnly.state.hasMore) filesOnly.loadMore()
                    assertEquals(listOf("notes.pdf"), filesOnly.state.entries.map { it.fileName() })
                    assertTrue("a sparse filter still reads bounded pages", filesReads.calls <= 6)
                    filesOnly.close()
                }
            } finally {
                accountRef?.let { runCatching { withTimeout(CLEANUP_TIMEOUT_MS) { marmot.removeAccount(it) } } }
                try {
                    withTimeout(CLEANUP_TIMEOUT_MS) { marmot.shutdownAndClose() }
                    closed = true
                } finally {
                    if (!closed) marmot.close()
                    root.deleteRecursively()
                }
            }
        }

    /** Reads the real index with a chosen page size, counting each native page read. */
    private class CountingReader(
        private val marmot: Marmot,
        private val account: String,
        private val query: AccountAttachmentQueryFfi,
        private val limit: UInt,
    ) : AccountAttachmentReader {
        var calls = 0
            private set

        /** One native page of at most [limit] original slots. */
        override suspend fun page(cursor: AccountAttachmentCursor?): AccountAttachmentPageReadFfi {
            calls++
            return marmot.accountAttachmentHistoryPage(account, query, limit, cursor)
        }

        /** The account's constant-work change token. */
        override suspend fun version() = marmot.accountAttachmentHistoryVersion(account)
    }

    /** One sent message carrying [attachments] and no caption. */
    private fun upload(vararg attachments: MediaUploadAttachmentRequestFfi) =
        MediaUploadRequestFfi(attachments = attachments.toList(), caption = null, send = true, blossomServer = null)

    /** A one-pixel PNG named [fileName]. */
    private fun photo(fileName: String) =
        MediaUploadAttachmentRequestFfi(
            fileName = fileName,
            mediaType = "image/png",
            plaintext = Base64.getDecoder().decode(PHOTO_PNG_BASE64),
            dim = "1x1",
            thumbhash = null,
        )

    /** A minimal PDF named [fileName]. */
    private fun document(fileName: String) =
        MediaUploadAttachmentRequestFfi(
            fileName = fileName,
            mediaType = "application/pdf",
            plaintext = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n".toByteArray(),
            dim = null,
            thumbhash = null,
        )

    private companion object {
        const val PHOTO_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        const val TEST_TIMEOUT_MS = 240_000L
        const val CLEANUP_TIMEOUT_MS = 30_000L
        const val SEND_SPACING_MS = 1_100L
    }
}

/** The file name of an accepted slot, or an empty string for any other outcome. */
private fun dev.ipf.marmotkit.AccountAttachmentEntryFfi.fileName(): String =
    (entry.attachment as? dev.ipf.marmotkit.MediaAttachmentOutcomeFfi.Accepted)?.reference?.fileName.orEmpty()
