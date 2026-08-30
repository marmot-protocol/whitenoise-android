package dev.ipf.whitenoise.android.share

import android.net.Uri
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareInboundStagerTest {
    private val draftStore = DraftStore(InMemoryDraftPersistence())
    private val shareStaging = ShareStagingStore()
    private val stager =
        ShareInboundStager(
            draftStore = draftStore,
            shareStaging = shareStaging,
            resolveMime = { _, uri ->
                when (uri.lastPathSegment) {
                    "photo.jpg" -> "image/jpeg"
                    "doc.pdf" -> "application/pdf"
                    else -> ""
                }
            },
        )

    @Test
    fun stagesTextToMultipleDraftKeysWithoutSendCollaborators() {
        val context = RuntimeEnvironment.getApplication()
        stager.stageToChats(
            context = context,
            accountIdHex = "acct",
            groupIds = listOf("g1", "g2"),
            payload = SharePayload("shared text", emptyList(), "text/plain"),
            draftAccountRef = "acct",
        )
        assertEquals("shared text", draftStore.get("acct", "g1"))
        assertEquals("shared text", draftStore.get("acct", "g2"))
        assertEquals(
            "text-only shares must invalidate an already-mounted composer",
            1,
            shareStaging.revision,
        )
    }

    @Test
    fun mergePolicyPreservesExistingDraft() {
        val context = RuntimeEnvironment.getApplication()
        draftStore.mergeText("acct", "g1", "existing")
        stager.stageToChats(
            context = context,
            accountIdHex = "acct",
            groupIds = listOf("g1"),
            payload = SharePayload("incoming", emptyList(), "text/plain"),
            draftAccountRef = "acct",
        )
        assertEquals("existing\nincoming", draftStore.get("acct", "g1"))
    }

    @Test
    fun stagesStreamsIntoShareStagingStore() {
        val context = RuntimeEnvironment.getApplication()
        val image = Uri.parse("content://example/photo.jpg")
        val doc = Uri.parse("content://example/doc.pdf")
        stager.stageToChats(
            context = context,
            accountIdHex = "acct",
            groupIds = listOf("g1"),
            payload = SharePayload(null, listOf(image, doc), "image/*"),
            draftAccountRef = "acct",
        )
        val staged = shareStaging.consume("acct", "g1")
        assertEquals(listOf(image), staged?.mediaUris)
        assertEquals(listOf(doc), staged?.documentUris)
        assertNull(draftStore.get("acct", "g1"))
    }

    /** Provider preparation is side-effect free until the Main-thread apply boundary. */
    @Test
    fun preparedStreamsDoNotMutateDraftOrComposerStateBeforeApply() {
        val context = RuntimeEnvironment.getApplication()
        val image = Uri.parse("content://example/photo.jpg")

        val prepared = stager.prepare(context, SharePayload("caption", listOf(image), "image/*"))

        assertNull(draftStore.get("acct", "g1"))
        assertNull(shareStaging.consume("acct", "g1"))
        stager.stagePreparedToChats(
            accountIdHex = "acct",
            groupIds = listOf("g1"),
            prepared = prepared,
            draftAccountRef = "acct",
        )
        assertEquals("caption", draftStore.get("acct", "g1"))
        assertEquals(listOf(image), shareStaging.consume("acct", "g1")?.mediaUris)
    }
}

private class InMemoryDraftPersistence : DraftPersistence {
    private val map = mutableMapOf<String, String>()

    override fun read(): Map<String, String> = map.toMap()

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
