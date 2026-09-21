package dev.ipf.whitenoise.android.state

/** Identity of one native draft attachment whose explicit removal is still in flight. */
internal data class DraftAttachmentRemovalKey(
    val accountRef: String,
    val groupIdHex: String,
    val attachmentId: String,
)

/**
 * Process-owned removal intent shared across composer instances. A composer can
 * disappear during native cleanup; a replacement must not restore the same
 * attachment until that cleanup has actually completed.
 */
internal class DraftAttachmentRemovalTombstones {
    private val pending = mutableSetOf<DraftAttachmentRemovalKey>()

    /** Records user intent before asynchronous native cleanup starts. */
    @Synchronized
    fun begin(
        accountRef: String,
        groupIdHex: String,
        attachmentId: String,
    ): DraftAttachmentRemovalKey = DraftAttachmentRemovalKey(accountRef, groupIdHex, attachmentId).also(pending::add)

    /** Clears only the exact intent whose native cleanup returned successfully. */
    @Synchronized
    fun complete(key: DraftAttachmentRemovalKey) {
        pending -= key
    }

    /** Returns attachment ids a new composer instance must exclude from restoration. */
    @Synchronized
    fun attachmentIds(
        accountRef: String,
        groupIdHex: String,
    ): Set<String> =
        pending
            .asSequence()
            .filter { it.accountRef == accountRef && it.groupIdHex == groupIdHex }
            .mapTo(mutableSetOf(), DraftAttachmentRemovalKey::attachmentId)
}
