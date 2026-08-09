package dev.ipf.whitenoise.android.media.editor

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorSourceStoreTest {
    @Test
    fun stagePersistsAndReturnsSource() {
        val payloads = InMemoryPayloads()
        val records = InMemoryStrings()
        val store = store(payloads, records)
        val bytes = "private photo".encodeToByteArray()

        val result = store.stageBytes(bytes) as EditorSourceStageResult.Success

        assertTrue(payloads.values.containsKey(result.lease.id))
        assertArrayEquals(bytes, store.bytes(result.lease.id))
        assertEquals(1, records.values.size)
    }

    @Test
    fun identicalSourceReusesLeaseAndIncrementsReference() {
        val store = store()
        val bytes = byteArrayOf(1, 2, 3)
        val first = (store.stageBytes(bytes) as EditorSourceStageResult.Success).lease
        val second = (store.stageBytes(bytes) as EditorSourceStageResult.Success).lease

        assertEquals(first.id, second.id)
        assertEquals(2, second.references)
        assertTrue(store.release(first.id))
        assertEquals(1, store.lease(first.id)?.references)
    }

    @Test
    fun finalReleaseDeletesPayloadAndRecord() {
        val payloads = InMemoryPayloads()
        val records = InMemoryStrings()
        val store = store(payloads, records)
        val lease = (store.stageBytes(byteArrayOf(9)) as EditorSourceStageResult.Success).lease

        assertTrue(store.release(lease.id))

        assertNull(store.lease(lease.id))
        assertNull(store.bytes(lease.id))
        assertTrue(records.values.isEmpty())
    }

    @Test
    fun sourceAndTotalBudgetsFailClosed() {
        val ids = sequenceOf("one", "two").iterator()
        val store =
            EditorSourceStore(
                payloads = InMemoryPayloads(),
                records = InMemoryStrings(),
                newId = ids::next,
                maxSourceBytes = 3,
                maxTotalBytes = 4,
            )

        assertEquals(EditorSourceStageResult.TooLarge, store.stageBytes(byteArrayOf(1, 2, 3, 4)))
        assertTrue(store.stageBytes(byteArrayOf(1, 2, 3)) is EditorSourceStageResult.Success)
        assertEquals(EditorSourceStageResult.BudgetExceeded, store.stageBytes(byteArrayOf(4, 5)))
    }

    @Test
    fun failedMetadataCommitDoesNotPublishPayload() {
        val payloads = InMemoryPayloads()
        val records = InMemoryStrings().apply { failWrites = true }
        val store = store(payloads, records)

        assertEquals(EditorSourceStageResult.Unavailable, store.stageBytes(byteArrayOf(1)))
        assertTrue(payloads.values.isEmpty())
    }

    @Test
    fun reconciliationDeletesOnlyUnownedLeases() {
        val store = store()
        val keep = (store.stageBytes(byteArrayOf(1)) as EditorSourceStageResult.Success).lease
        val remove = (store.stageBytes(byteArrayOf(2)) as EditorSourceStageResult.Success).lease

        assertEquals(1, store.reconcile(setOf(keep.id)))
        assertTrue(store.bytes(keep.id) != null)
        assertNull(store.bytes(remove.id))
    }

    @Test
    fun reconciliationRepairsDeduplicatedReferenceCount() {
        val store = store()
        val lease = (store.stageBytes(byteArrayOf(1)) as EditorSourceStageResult.Success).lease
        store.stageBytes(byteArrayOf(1))
        assertEquals(2, store.lease(lease.id)?.references)

        store.reconcile(mapOf(lease.id to 3))

        assertEquals(3, store.lease(lease.id)?.references)
    }

    @Test
    fun stageUriMapsMissingStreamToUnavailableWithoutPublishing() {
        val payloads = InMemoryPayloads()
        val store = store(payloads = payloads)
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        val uri = Uri.fromFile(File("/definitely-missing/editor-source/photo"))
        ShadowContentResolver.reset()

        assertEquals(EditorSourceStageResult.Unavailable, store.stageUri(resolver, uri))
        assertTrue(payloads.values.isEmpty())
    }

    @Test
    fun stageUriMapsBoundedReadOverflowToTooLargeWithoutPublishing() {
        val payloads = InMemoryPayloads()
        val store = store(payloads = payloads, maxSourceBytes = 3)
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        val uri = Uri.parse("content://editor-source/large")
        ShadowContentResolver.reset()
        shadowOf(resolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }

        assertEquals(EditorSourceStageResult.TooLarge, store.stageUri(resolver, uri))
        assertTrue(payloads.values.isEmpty())
    }

    @Test
    fun stageUriMapsIoAndSecurityFailuresToUnavailableWithoutPublishing() {
        val payloads = InMemoryPayloads()
        val store = store(payloads = payloads)
        val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
        val ioUri = Uri.parse("content://editor-source/io")
        val securityUri = Uri.parse("content://editor-source/security")
        ShadowContentResolver.reset()
        shadowOf(resolver).registerInputStreamSupplier(ioUri) { throw IOException("unreadable") }
        shadowOf(resolver).registerInputStreamSupplier(securityUri) { throw SecurityException("denied") }

        assertEquals(EditorSourceStageResult.Unavailable, store.stageUri(resolver, ioUri))
        assertEquals(EditorSourceStageResult.Unavailable, store.stageUri(resolver, securityUri))
        assertTrue(payloads.values.isEmpty())
    }

    @Test
    fun generatedIdsRemainUniqueAfterAReleasedLease() {
        val store = store()
        val first = (store.stageBytes(byteArrayOf(1)) as EditorSourceStageResult.Success).lease
        assertTrue(store.release(first.id))

        val second = (store.stageBytes(byteArrayOf(2)) as EditorSourceStageResult.Success).lease

        assertTrue(first.id != second.id)
    }

    private fun store(
        payloads: InMemoryPayloads = InMemoryPayloads(),
        records: InMemoryStrings = InMemoryStrings(),
        maxSourceBytes: Int = 64 * 1024 * 1024,
    ): EditorSourceStore {
        var nextId = 0
        return EditorSourceStore(
            payloads = payloads,
            records = records,
            nowMs = { 123L },
            newId = { "lease-${nextId++}" },
            maxSourceBytes = maxSourceBytes,
        )
    }
}

private class InMemoryPayloads : EditorEncryptedPayloadStore {
    val values = linkedMapOf<String, ByteArray>()

    override fun prepare() = Unit

    override fun contains(key: String): Boolean = key in values

    override fun get(key: String): ByteArray? = values[key]?.copyOf()

    override fun put(
        key: String,
        bytes: ByteArray,
    ): Boolean {
        values[key] = bytes.copyOf()
        return true
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun clear() = values.clear()
}

private class InMemoryStrings : EditorStringStore {
    var values = linkedMapOf<String, String>()
    var failWrites = false

    override fun readAll(): Map<String, String> = values.toMap()

    override fun replaceAll(values: Map<String, String>): Boolean {
        if (failWrites) return false
        this.values = LinkedHashMap(values)
        return true
    }

    override fun clear() = values.clear()
}
