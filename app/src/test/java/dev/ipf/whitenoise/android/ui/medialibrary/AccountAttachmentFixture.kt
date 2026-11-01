package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AccountAttachmentCursor
import dev.ipf.marmotkit.AccountAttachmentEntryFfi
import dev.ipf.marmotkit.AccountAttachmentPageFfi
import dev.ipf.marmotkit.AccountAttachmentPageReadFfi
import dev.ipf.marmotkit.AccountAttachmentVersion
import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentHistoryChangeFfi
import dev.ipf.marmotkit.AttachmentRoleFfi
import dev.ipf.marmotkit.NoPointer

/** Models MDK's account cursor/version contract without JNI: bounded pages, empty continuations, generations. */
internal class AccountAttachmentFixture(
    var rows: List<AccountAttachmentEntryFfi>,
    val size: Int = 100,
) : AccountAttachmentReader {
    var additions = 0
    var removals = 0
    var calls = 0
    var failNext = false
    var invalidQuery = false
    var expiries: List<ULong?> = emptyList()
    val cursors = mutableListOf<FakeAccountCursor>()
    val versions = mutableListOf<FakeAccountVersion>()

    /** Pages after a slot key; a destructive generation change invalidates every outstanding cursor. */
    @Suppress("ReturnCount") // Failure, refusal and a stale cursor each answer before paging.
    override suspend fun page(cursor: AccountAttachmentCursor?): AccountAttachmentPageReadFfi {
        val call = calls++
        if (failNext) {
            failNext = false
            error("fixture failure")
        }
        if (invalidQuery) return AccountAttachmentPageReadFfi.InvalidQuery
        val previous = cursor as? FakeAccountCursor
        if (previous != null && previous.generation != removals) return AccountAttachmentPageReadFfi.RestartRequired
        val start = previous?.let { c -> rows.indexOfFirst { it.slotKey() == c.after } + 1 } ?: 0
        val entries = rows.drop(start).take(size)
        val next =
            if (start + entries.size < rows.size) {
                accountStub(FakeAccountCursor::class.java).also {
                    it.after = entries.last().slotKey()
                    it.generation = removals
                    cursors += it
                }
            } else {
                null
            }
        return AccountAttachmentPageReadFfi.Page(
            AccountAttachmentPageFfi(entries, versionHandle(), next, expiries.getOrNull(call)),
        )
    }

    /** A constant-work token reflecting the fixture's current generation. */
    override suspend fun version(): AccountAttachmentVersion = versionHandle()

    /** Allocates one independently owned version handle for each result. */
    private fun versionHandle(): FakeAccountVersion =
        accountStub(FakeAccountVersion::class.java).also {
            it.additions = additions
            it.removals = removals
            versions += it
        }
}

/** Opaque test cursor recording deterministic disposal. */
internal class FakeAccountCursor : AccountAttachmentCursor(NoPointer) {
    var after: Triple<String, String, UInt>? = null
    var generation = 0
    var closes = 0

    /** Counts closes so tests can require exactly one. */
    override fun close() {
        closes++
    }
}

/** Opaque test version distinguishing additions from destructive changes. */
internal class FakeAccountVersion : AccountAttachmentVersion(NoPointer) {
    var additions = 0
    var removals = 0
    var closes = 0

    /** Counts closes so tests can require exactly one. */
    override fun close() {
        closes++
    }

    /** Compares generations, refusing to compare a handle that was already released. */
    override fun changeSince(previous: AccountAttachmentVersion): AttachmentHistoryChangeFfi {
        check(closes == 0)
        val old = previous as FakeAccountVersion
        check(old.closes == 0)
        return when {
            removals != old.removals -> AttachmentHistoryChangeFfi.RESTART_REQUIRED
            additions != old.additions -> AttachmentHistoryChangeFfi.ADDITIONS
            else -> AttachmentHistoryChangeFfi.UNCHANGED
        }
    }
}

/** Avoids the generated JNI cleaner entirely, as the per-chat fixture does. */
@Suppress("UNCHECKED_CAST")
private fun <T> accountStub(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
}

/** One account slot in [group]; equal timestamps keep tests from passing through timestamp sorting. */
internal fun accountEntry(
    id: Int,
    group: String = "aa",
    index: UInt = 0u,
    category: AttachmentCategoryFfi = AttachmentCategoryFfi.IMAGE,
    role: AttachmentRoleFfi = AttachmentRoleFfi.SHARED,
    metadataLimited: Boolean = false,
) = AccountAttachmentEntryFfi(metadataLimited, group, historyEntry(id, index, category, role))
