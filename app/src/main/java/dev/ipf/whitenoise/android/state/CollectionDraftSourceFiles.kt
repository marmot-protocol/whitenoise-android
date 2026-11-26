package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.sharedPlaintextPrefix
import java.io.File

private val sourceName = Regex("""account_[0-9a-f]{64}_collection-([0-9a-f]{32})-[0-9]+\.[a-zA-Z0-9]+(?:\.partial)?""")

/** These files have their own journal-backed expiry, separate from ordinary short-lived exports. */
internal fun isCollectionDraftSource(name: String): Boolean = sourceName.matches(name)

/** Private, operation-owned sources independent of composer presentation, keyed like the metadata journal. */
internal class CollectionDraftSourceFiles(
    cacheDir: File,
) {
    private val root = File(cacheDir, MediaCacheDirs.SHARED)

    /** Names the copy so both the collection journal and existing account wipe can release it. */
    fun fileFor(
        context: Context,
        operation: AttachmentCollectionOperation,
        position: Int,
        suffix: String,
    ): File {
        val owner = sharedPlaintextPrefix(context, operation.accountRef)
        return File(root, "${owner}collection-${journalHash(operation.id)}-$position$suffix")
    }

    /** Releases only files named by this operation's journal key. */
    fun deleteKey(key: String) {
        sources().filter { (_, owner) -> owner == key }.forEach { (file, _) -> file.delete() }
    }

    /** Bounds sources left if the process stopped before the operation's journal was committed. */
    fun sweepOrphans(
        held: Set<String>,
        cutoff: Long,
    ) {
        sources().forEach { (file, owner) ->
            if (owner !in held && file.lastModified() < cutoff) file.delete()
        }
    }

    /** Enumerates only the files owned by collection operations, including partial copies. */
    private fun sources(): List<Pair<File, String>> =
        root.listFiles().orEmpty().mapNotNull { file ->
            sourceName.matchEntire(file.name)?.groupValues?.get(1)?.let { file to it }
        }
}
