package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentHistoryChangeFfi
import dev.ipf.marmotkit.AttachmentHistoryCursor
import dev.ipf.marmotkit.AttachmentHistoryVersion
import dev.ipf.marmotkit.AttachmentPageFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.NoPointer

/** Models the native slot/cursor contract without loading JNI or pretending to test SQLite eligibility. */
internal class GroupAttachmentFixture(
    var rows: List<AttachmentEntryFfi>,
    val size: Int = 13,
) : GroupAttachmentReader {
    var additions = 0
    var removals = 0
    var calls = 0
    var failNext = false
    var failVersion = false
    var beforePage: suspend () -> Unit = {}
    val cursors = mutableListOf<FakeAttachmentCursor>()
    val versions = mutableListOf<FakeAttachmentVersion>()

    /** Pages after a native-order slot key; generation changes invalidate every outstanding cursor. */
    override suspend fun page(cursor: AttachmentHistoryCursor?): AttachmentPageReadFfi {
        calls++
        beforePage()
        if (failNext) {
            failNext = false
            error("fixture failure")
        }
        val previous = cursor as? FakeAttachmentCursor
        if (previous != null && previous.generation != removals) return AttachmentPageReadFfi.RestartRequired
        val start = previous?.let { c -> rows.indexOfFirst { it.slotKey() == c.after } + 1 } ?: 0
        val entries = rows.drop(start).take(size)
        val more = start + entries.size < rows.size
        val next =
            if (more) {
                nativeStub(FakeAttachmentCursor::class.java).also {
                    it.after = entries.last().slotKey()
                    it.generation = removals
                    cursors += it
                }
            } else {
                null
            }
        return AttachmentPageReadFfi.Page(AttachmentPageFfi(entries, versionHandle(), next, more))
    }

    /** Independent version reads can fail without advancing the pager's retained baseline. */
    override suspend fun version(): AttachmentHistoryVersion {
        if (failVersion) {
            failVersion = false
            error("version failure")
        }
        return versionHandle()
    }

    /** Allocates one independently owned version handle for each result. */
    private fun versionHandle(): FakeAttachmentVersion =
        nativeStub(FakeAttachmentVersion::class.java).also {
            it.additions = additions
            it.removals = removals
            versions += it
        }
}

/** Opaque test cursor that records deterministic native-handle disposal. */
internal class FakeAttachmentCursor : AttachmentHistoryCursor(NoPointer) {
    var after: Pair<String, UInt>? = null
    var generation = 0
    var closes = 0

    override fun close() {
        closes++
    }
}

/** Opaque test version distinguishing additions from destructive changes. */
internal class FakeAttachmentVersion : AttachmentHistoryVersion(NoPointer) {
    var additions = 0
    var removals = 0
    var closes = 0

    override fun close() {
        closes++
    }

    override fun changeSince(previous: AttachmentHistoryVersion): AttachmentHistoryChangeFfi {
        check(closes == 0)
        val old = previous as FakeAttachmentVersion
        check(old.closes == 0)
        return when {
            removals != old.removals -> AttachmentHistoryChangeFfi.RESTART_REQUIRED
            additions != old.additions -> AttachmentHistoryChangeFfi.ADDITIONS
            else -> AttachmentHistoryChangeFfi.UNCHANGED
        }
    }
}

/** Avoids the generated JNI cleaner entirely, as in other binding-contract fixtures. */
@Suppress("UNCHECKED_CAST")
private fun <T> nativeStub(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
}

/** Equal timestamps deliberately ensure tests cannot pass through timestamp sorting. */
internal fun historyEntry(
    id: Int,
    index: UInt = 0u,
    category: AttachmentCategoryFfi = AttachmentCategoryFfi.IMAGE,
) = AttachmentEntryFfi(
    "message-$id",
    "source-$id",
    "sender",
    1_700_000_000u,
    1_700_000_000u,
    1u,
    category,
    MediaAttachmentOutcomeFfi.Accepted(
        index,
        MediaAttachmentReferenceFfi(
            emptyList(),
            "11".repeat(32),
            "22".repeat(32),
            "33".repeat(12),
            "photo-$id.png",
            "image/png",
            EncryptedMediaVersionFfi.V2,
            1u,
            null,
            null,
        ),
    ),
)
