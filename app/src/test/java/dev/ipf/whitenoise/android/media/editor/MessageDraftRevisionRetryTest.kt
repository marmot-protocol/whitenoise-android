package dev.ipf.whitenoise.android.media.editor

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageDraftRevisionRetryTest {
    private fun kotlinx.coroutines.test.TestScope.repositoryFor(gateway: MessageDraftGateway) =
        MessageDraftRepository(
            gateway = gateway,
            editorSessions = EditorSessionStore(RetryEditorStrings()),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    /** A lost revision race re-reads the fresh draft under the lock and applies the edit on top of it. */
    @Test
    fun conflictingSaveRetriesAgainstTheFreshSelectedDraft() =
        runTest {
            val gateway = RevisionGateway(initialContent = "old", conflictsBeforeSuccess = 1)
            val repository = repositoryFor(gateway)

            val result = repository.saveText("acct", "group", "new text")

            assertTrue(result is MessageDraftMutationResult.Success)
            assertEquals("new text", gateway.current?.content)
            assertEquals(2, gateway.conditionalSaves)
            assertEquals(listOf(1L, 2L), gateway.quotedRevisions)
        }

    /** A second consecutive conflict is reported instead of looping. */
    @Test
    fun repeatedConflictIsReportedAsFailure() =
        runTest {
            val gateway = RevisionGateway(initialContent = "old", conflictsBeforeSuccess = 5)
            val repository = repositoryFor(gateway)

            val result = repository.saveText("acct", "group", "new text")

            assertTrue(result is MessageDraftMutationResult.Failure)
            val cause = (result as MessageDraftMutationResult.Failure).cause
            assertTrue(cause is MarmotKitException.MessageDraftRevisionConflict)
            assertEquals("old", gateway.current?.content)
        }

    /** Clearing an empty draft also goes through the selected revision. */
    @Test
    fun deletingUsesTheSelectedRevision() =
        runTest {
            val gateway = RevisionGateway(initialContent = "old", conflictsBeforeSuccess = 0)
            val repository = repositoryFor(gateway)

            val result = repository.delete("acct", "group")

            assertTrue(result is MessageDraftMutationResult.Success)
            assertEquals(1, gateway.conditionalClears)
            assertEquals(null, gateway.current)
        }
}

/** In-memory editor string store for the retry cases. */
private class RetryEditorStrings : EditorStringStore {
    private var values = linkedMapOf<String, String>()

    override fun readAll(): Map<String, String>? = values.toMap()

    override fun replaceAll(values: Map<String, String>): Boolean {
        this.values = LinkedHashMap(values)
        return true
    }

    override fun clear() = values.clear()
}

/** Gateway whose revision advances per write; the first [conflictsBeforeSuccess] conditional saves lose the race. */
private class RevisionGateway(
    initialContent: String,
    private var conflictsBeforeSuccess: Int,
) : MessageDraftGateway {
    private var revision = 1L
    var current: MessageDraftFfi? = MessageDraftFfi("group", initialContent, null, emptyList(), 1L, 1L)
    var conditionalSaves = 0
    var conditionalClears = 0
    val quotedRevisions = mutableListOf<Long>()
    private val revisionHandles = mutableMapOf<Long, MessageDraftRevisionFfi>()

    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = current

    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi = error("unconditional save must not be used when revisions are available")

    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) = error("unconditional delete must not be used when revisions are available")

    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()

    override fun selected(
        accountRef: String,
        groupIdHex: String,
    ): SelectedMessageDraftFfi =
        SelectedMessageDraftFfi(
            revision = handle(revision),
            draft =
                current?.let {
                    SelectedMessageDraftContentFfi(
                        it.groupIdHex,
                        it.content,
                        it.replyToMessageIdHex,
                        emptyList(),
                        it.createdAtMs,
                        it.updatedAtMs,
                    )
                },
        )

    override fun saveIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi {
        conditionalSaves += 1
        quotedRevisions += revisionOf(revision)
        if (conflictsBeforeSuccess > 0) {
            conflictsBeforeSuccess -= 1
            this.revision += 1
            throw MarmotKitException.MessageDraftRevisionConflict()
        }
        this.revision += 1
        val saved = MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, this.revision)
        current = saved
        return saved
    }

    override fun clearIfRevision(
        accountRef: String,
        groupIdHex: String,
        revision: MessageDraftRevisionFfi,
    ) {
        conditionalClears += 1
        this.revision += 1
        current = null
    }

    private fun handle(value: Long): MessageDraftRevisionFfi {
        val handle = revisionHandles.getOrPut(value) { nativeStub(MessageDraftRevisionFfi::class.java) }
        return handle
    }

    private fun revisionOf(handle: MessageDraftRevisionFfi): Long {
        val entry = revisionHandles.entries.first { it.value === handle }
        return entry.key
    }
}

/** Allocates a native handle stub without its constructor, so records embedding one can be built in JVM tests. */
private fun <T> nativeStub(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null)
    @Suppress("UNCHECKED_CAST")
    return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
}
