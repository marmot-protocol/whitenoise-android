package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.nostr.NostrEvent

private const val KIND_GIT_REPOSITORY = 30_617
private const val KIND_GIT_REFS = 30_618
internal const val KIND_GIT_PATCH = 1_617
private const val KIND_GIT_PULL_REQUEST = 1_618
private const val KIND_GIT_PULL_REQUEST_UPDATE = 1_619
private const val KIND_GIT_ISSUE = 1_621
private const val KIND_GIT_OPEN = 1_630
private const val KIND_GIT_APPLIED = 1_631
private const val KIND_GIT_CLOSED = 1_632
private const val KIND_GIT_DRAFT = 1_633
private const val KIND_GIT_SERVICES = 10_317
internal const val KIND_GIT_COMMENT = 1_111
private const val MAX_GIT_REFS = 8
private const val MAX_GIT_LINKS = 8
private const val MAX_GIT_LINK_CANDIDATES = 32
private const val MAX_GIT_LINK_LENGTH = 512
private const val GIT_ROOT_MARKER_INDEX = 3
private const val GIT_COORDINATE_PARTS = 3

internal fun NostrEvent.isGitCard(): Boolean =
    when (kind) {
        KIND_GIT_COMMENT -> gitCommentRoot() != null
        else -> gitKindLabel(kind) != null
    }

internal fun gitCardLabel(kind: Int): Int = gitKindLabel(kind) ?: R.string.nostr_git_comment

private fun gitKindLabel(kind: Int): Int? =
    when (kind) {
        KIND_GIT_REPOSITORY -> R.string.nostr_git_repository
        KIND_GIT_REFS -> R.string.nostr_git_refs
        KIND_GIT_PATCH -> R.string.nostr_git_patch
        KIND_GIT_PULL_REQUEST -> R.string.nostr_git_pr
        KIND_GIT_PULL_REQUEST_UPDATE -> R.string.nostr_git_update
        KIND_GIT_ISSUE -> R.string.nostr_git_issue
        KIND_GIT_OPEN -> R.string.nostr_git_open
        KIND_GIT_APPLIED -> R.string.nostr_git_applied
        KIND_GIT_CLOSED -> R.string.nostr_git_closed
        KIND_GIT_DRAFT -> R.string.nostr_git_draft
        KIND_GIT_SERVICES -> R.string.nostr_git_services
        else -> null
    }

internal fun NostrEvent.gitCardMetadata(): List<String> =
    buildList {
        gitRepositoryContext()?.let(::add)
        when (kind) {
            KIND_GIT_REPOSITORY -> gitRepositoryIdentifier()?.let(::add)
            KIND_GIT_REFS -> {
                firstSafeTag("HEAD")?.let(::add)
                // Sanitise before the blank check: a value of control characters alone would leave a dangling `·`.
                tags
                    .asSequence()
                    .filter { it.firstOrNull()?.startsWith("refs/") == true }
                    .mapNotNull { tag ->
                        val target = tag.getOrNull(1)?.safeField()?.takeIf(String::isNotBlank)
                        target?.let { "${tag[0].safeField()} · $it" }
                    }.take(MAX_GIT_REFS)
                    .forEach(::add)
            }
            KIND_GIT_PATCH -> firstSafeTag("commit")?.let { add("commit $it") }
            KIND_GIT_PULL_REQUEST, KIND_GIT_PULL_REQUEST_UPDATE -> addAll(gitPullRequestMetadata())
            in KIND_GIT_OPEN..KIND_GIT_DRAFT ->
                tags
                    .firstOrNull { it.firstOrNull() == "e" && it.getOrNull(GIT_ROOT_MARKER_INDEX) == "root" }
                    ?.getOrNull(1)
                    ?.safeField()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            KIND_GIT_COMMENT ->
                listOfNotNull(gitCommentRoot(), firstSafeTag("e") ?: firstSafeTag("a"))
                    .distinct()
                    .let(::addAll)
        }
    }

private fun NostrEvent.gitPullRequestMetadata(): List<String> =
    listOfNotNull(
        firstSafeTag("branch-name")?.let { "branch $it" },
        firstSafeTag("c")?.let { "commit $it" },
        firstSafeTag("merge-base")?.let { "merge-base $it" },
        firstSafeTag(if (kind == KIND_GIT_PULL_REQUEST_UPDATE) "E" else "e"),
    )

/**
 * The `d` identifier of an announcement whose card title is a different, human-readable name. When no name is
 * present the identifier already is the title.
 */
private fun NostrEvent.gitRepositoryIdentifier(): String? {
    val identifier = firstSafeTag("d") ?: return null
    val title = firstSafeTag("subject") ?: firstSafeTag("title") ?: firstSafeTag("name")
    return identifier.takeIf { title != null && title != it }
}

/** The repository named by the first valid `30617:<pubkey>:<d>` coordinate in an `a` tag, if any. */
private fun NostrEvent.gitRepositoryContext(): String? =
    tags
        .asSequence()
        .filter { it.firstOrNull() == "a" }
        .mapNotNull { tag ->
            val parts = tag.getOrNull(1)?.split(':', limit = GIT_COORDINATE_PARTS)
            parts
                ?.takeIf { it.size == GIT_COORDINATE_PARTS && it[0] == KIND_GIT_REPOSITORY.toString() }
                ?.takeIf { gitEventId.matches(it[1]) }
                ?.get(2)
                ?.safeField()
                ?.takeIf(String::isNotBlank)
        }.firstOrNull()

/**
 * Exact advertised URLs for the reader's Copy and confirmed Open actions, taken tag by tag in priority order so a
 * long relay list cannot push the clone and web links out, and clone mirrors leave a slot for a web link. A link is
 * kept only when it can be copied safely or opened. Rendering never opens them or starts a Git operation.
 */
internal fun NostrEvent.gitCardLinks(): List<String> {
    val tagsByPriority =
        when (kind) {
            KIND_GIT_REPOSITORY -> listOf("clone", "web", "relays")
            KIND_GIT_PULL_REQUEST, KIND_GIT_PULL_REQUEST_UPDATE -> listOf("clone")
            KIND_GIT_SERVICES -> listOf("g")
            else -> return emptyList()
        }
    val byTag =
        tagsByPriority.map { name ->
            // Only a tag's first values are considered, so a crafted event with thousands of them costs no more.
            tags
                .asSequence()
                .filter { it.firstOrNull() == name }
                .flatMap { it.asSequence().drop(1) }
                .take(MAX_GIT_LINK_CANDIDATES)
                .filter { it.length <= MAX_GIT_LINK_LENGTH && it == it.safeReaderBody() }
                // Only links the reader can act on take a slot: Copy needs a shell-safe one, Open one it can open.
                .filter { isCopyableCloneAddress(it) && (isShellSafeGitLink(it) || isOpenableGitLink(it)) }
                .distinct()
                // Copyable addresses lead their tag, so open-only mirrors cannot push a usable one out.
                .sortedBy { !isShellSafeGitLink(it) }
                .toList()
        }
    // The first tag leaves one slot for the second (clone mirrors for a web link); later tags only fill what is left.
    val first = byTag.first()
    val second = byTag.getOrElse(1) { emptyList() }
    val ordered = first.take(MAX_GIT_LINKS - 1) + second + first.drop(MAX_GIT_LINKS - 1) + byTag.drop(2).flatten()
    return ordered.distinct().take(MAX_GIT_LINKS)
}

private val gitObjectId = Regex("[0-9a-fA-F]{40}")
private val gitEventId = Regex("[0-9a-fA-F]{64}")

private fun NostrEvent.gitCommentRoot(): String? {
    if (firstTagValue("K")?.toIntOrNull() !in setOf(KIND_GIT_PATCH, KIND_GIT_PULL_REQUEST, KIND_GIT_ISSUE)) return null
    return firstTagValue("E")?.takeIf(gitEventId::matches)
}

/** Repository addresses carry a public key between the kind and repository name. */
internal fun gitPreviewField(value: String): String? {
    if (gitEventId.matches(value) || gitObjectId.matches(value)) return null
    val prefix = "$KIND_GIT_REPOSITORY:"
    val repo =
        value.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let { address ->
            val pubkey = address.substringBefore(':')
            address.substringAfter(':', "").takeIf { it.isNotBlank() && gitEventId.matches(pubkey) }
        }
    return repo ?: value
}
