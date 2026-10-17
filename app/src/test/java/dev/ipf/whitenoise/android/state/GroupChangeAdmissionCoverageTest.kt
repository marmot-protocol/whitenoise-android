package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3289: every Android call to an MDK group change that can be refused as catching up or
 * undelivered goes through [runGroupChange], so the user is asked instead of seeing a bare failure.
 */
class GroupChangeAdmissionCoverageTest {
    /** Each guarded binding call sits inside a runGroupChange block opened a few lines above it. */
    @Test
    fun everyGuardedGroupChangeAsksTheUser() {
        val calls =
            sources().flatMap { file ->
                val lines = file.readLines()
                lines.indices
                    // Binding calls are unqualified inside marmotIo; a dotted call is a host wrapper, not MDK.
                    .filter { index -> GUARDED.any { Regex("""(?<![.\w])$it\(""").containsMatchIn(lines[index]) } }
                    .filterNot { lines[it].contains("fun ") }
                    .map { index -> Triple(file.name, index + 1, lines.subList(maxOf(0, index - WINDOW), index)) }
            }

        assertTrue("no guarded group change found; the gate is reading the wrong sources", calls.size >= MIN_CALLS)
        val unguarded = calls.filterNot { (_, _, before) -> before.any { "runGroupChange(" in it } }
        assertTrue(
            "group changes MDK can refuse must go through runGroupChange: " +
                unguarded.joinToString { "${it.first}:${it.second}" },
            unguarded.isEmpty(),
        )
    }

    /**
     * runGroupChange holds the group's commit lock for the acknowledgement and retry, so a change it runs must not take
     * that lock again, which would deadlock. Callers take it outside, as the controllers do.
     */
    @Test
    fun noGroupChangeTakesTheCommitLockInside() {
        val nested =
            sources().flatMap { file ->
                val lines = file.readLines()
                lines.indices
                    .filter { "runGroupChange(" in lines[it] && "fun " !in lines[it] }
                    .filter { index ->
                        val change = lines.subList(index + 1, minOf(lines.size, index + NESTED_WINDOW))
                        change.any { "withGroupCommitLock(" in it }
                    }.map { "${file.name}:${it + 1}" }
            }
        assertTrue("runGroupChange must not take the commit lock inside its change: $nested", nested.isEmpty())
    }

    /** The admission helper acknowledges exactly the two advisory refusals MDK lets a user override. */
    @Test
    fun admissionAcknowledgesBothAdvisoryRefusals() {
        val helper = source("state/GroupChangeAdmission.kt")

        assertTrue("acknowledgeGroupCatchingUp(" in helper)
        assertTrue("acknowledgeGroupCommitUndelivered(" in helper)
        assertTrue(
            "a declined change must stay quiet",
            "GroupChangeDeclinedException" in source("state/ErrorPresentation.kt"),
        )
    }

    /** Production Kotlin sources under the app package. */
    private fun sources(): List<File> =
        listOf(File("src/main/java"), File("app/src/main/java"))
            .first(File::isDirectory)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** Reads one production source file under the app package. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing $path")

    private companion object {
        /** Binding methods whose MDK operation runs the group-change admission check (mdk#2258, mdk#2255). */
        val GUARDED =
            listOf(
                "inviteMembers",
                "inviteMembersDetailed",
                "inviteMembersWithInitialAdmins",
                "inviteMembersDetailedWithInitialAdmins",
                "removeMembers",
                "removeMembersDetailed",
                "promoteAdmin",
                "promoteAdminDetailed",
                "demoteAdmin",
                "demoteAdminDetailed",
                "selfDemoteAdmin",
                "selfDemoteAdminDetailed",
                "enableGroupDisbanding",
                "disbandGroup",
                "updateAppComponent",
                "updateMessageRetention",
                "replaceEncryptedMediaBlobEndpoints",
                "updateGroupAvatarUrl",
                "updateGroupImage",
                "updateGroupProfile",
            )

        /** How far above a call its runGroupChange may open. */
        const val WINDOW = 8

        /** How far below a runGroupChange call its change could open the commit lock. */
        const val NESTED_WINDOW = 4

        /** Fewer matches than this means the gate stopped seeing the call sites. */
        const val MIN_CALLS = 15
    }
}
