package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionFfi

/** An accepted attachment paired with its position among the message's `imeta` tags. */
typealias IndexedAttachment = IndexedValue<MediaAttachmentReferenceFfi>

/** A rejected attachment paired with the `imeta` slot it occupies. */
typealias IndexedRejection = IndexedValue<MediaAttachmentRejectionFfi>

/**
 * Bridges MarmotKit's per-attachment outcomes to the app's reference-based media surfaces.
 *
 * MDK 0.10.0 reports every `imeta` tag of a message as [MediaAttachmentOutcomeFfi.Accepted] or
 * [MediaAttachmentOutcomeFfi.Rejected], in tag order. The `attachmentIndex` counts rejected siblings, so
 * it is the same index `listMedia`, cache keys and download requests use. Callers that render or cache
 * by position must take the indexed forms here rather than re-indexing a compacted reference list.
 */
object MessageAttachments {
    /** Accepted references keyed by their protocol attachment index, rejected slots skipped but not renumbered. */
    fun accepted(outcomes: List<MediaAttachmentOutcomeFfi>): List<IndexedAttachment> =
        outcomes.mapNotNull { outcome ->
            (outcome as? MediaAttachmentOutcomeFfi.Accepted)?.let {
                IndexedValue(it.attachmentIndex.toInt(), it.reference)
            }
        }

    /** Rejected slots in tag order, for placeholders that keep the message's attachment layout truthful. */
    fun rejected(outcomes: List<MediaAttachmentOutcomeFfi>): List<IndexedRejection> =
        outcomes.mapNotNull { outcome ->
            (outcome as? MediaAttachmentOutcomeFfi.Rejected)?.let {
                IndexedValue(it.attachmentIndex.toInt(), it.rejection)
            }
        }

    /** Accepted references only, for consumers that never derive an attachment index from position. */
    fun acceptedReferences(outcomes: List<MediaAttachmentOutcomeFfi>): List<MediaAttachmentReferenceFfi> {
        val accepted = accepted(outcomes)
        return accepted.map { it.value }
    }

    /** Whether any accepted attachment is present. */
    fun hasAccepted(outcomes: List<MediaAttachmentOutcomeFfi>): Boolean {
        val accepted = accepted(outcomes)
        return accepted.isNotEmpty()
    }

    /** Wraps positional references as accepted outcomes, for optimistic rows and fixtures without rejections. */
    fun acceptedOutcomes(references: List<MediaAttachmentReferenceFfi>): List<MediaAttachmentOutcomeFfi> =
        references.mapIndexed { index, reference ->
            MediaAttachmentOutcomeFfi.Accepted(attachmentIndex = index.toUInt(), reference = reference)
        }

    /** Indexes a positional reference list the same way [accepted] indexes outcomes. */
    fun indexed(references: List<MediaAttachmentReferenceFfi>): List<IndexedAttachment> {
        val indexed = references.withIndex()
        return indexed.toList()
    }
}
