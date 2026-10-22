package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
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
                val admittedBodies = groupChangeBodies(lines)
                lines.indices
                    // Binding calls are unqualified inside marmotIo; a dotted call is a host wrapper, not MDK.
                    .filter { index -> GUARDED.any { Regex("""(?<![.\w])$it\(""").containsMatchIn(lines[index]) } }
                    .filterNot { lines[it].contains("fun ") }
                    .map { index ->
                        Triple(file.name, index + 1, admittedBodies.filter { index - it.first in 1..WINDOW })
                    }
            }

        assertTrue("no guarded group change found; the gate is reading the wrong sources", calls.size >= MIN_CALLS)
        val unguarded = calls.filter { (_, _, bodies) -> bodies.isEmpty() }
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
                        val body = groupChangeBodies(lines).first { it.second == index }.first
                        val change = lines.subList(body + 1, minOf(lines.size, body + NESTED_WINDOW))
                        change.any { "withGroupCommitLock(" in it }
                    }.map { "${file.name}:${it + 1}" }
            }
        assertTrue("runGroupChange must not take the commit lock inside its change: $nested", nested.isEmpty())
    }

    /** Every user-visible retry keeps its action; membership actions also keep the exact affected people. */
    @Test
    fun retryQuestionsKeepActionAndAffectedPeople() {
        sources().forEach { file ->
            val lines = file.readLines()
            groupChangeBodies(lines).forEach { (body, start) ->
                val header = lines.subList(start, body + 1).joinToString(" ")
                assertTrue("${file.name}:${start + 1} loses the action", "actionLabel =" in header)
                val native = lines.subList(body + 1, minOf(lines.size, body + WINDOW + 1)).joinToString(" ")
                if (MEMBER_CHANGES.any { Regex("""(?<![.\w])$it\(""").containsMatchIn(native) }) {
                    assertTrue("${file.name}:${start + 1} loses affected people", "affectedMemberIds =" in header)
                }
            }
        }
    }

    /** Start the existing distance bound at the callback body, excluding a multiline metadata argument list. */
    private fun groupChangeBodies(lines: List<String>): List<Pair<Int, Int>> =
        lines.indices.filter { "runGroupChange(" in lines[it] && "fun " !in lines[it] }.map { start ->
            var argumentDepth = 0
            val body =
                (start until minOf(lines.size, start + HEADER_WINDOW)).firstOrNull { index ->
                    val line =
                        lines[index]
                            .replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "")
                            .substringBefore("//")
                    argumentDepth += line.count { it == '(' } - line.count { it == ')' }
                    argumentDepth == 0 && line.trimEnd().endsWith(") {")
                } ?: error("Unbounded runGroupChange header at ${start + 1}")
            body to start
        }

    /** Metadata may contain an if block; admission starts after the entire argument list closes. */
    @Test
    fun metadataConditionDoesNotStartTheGuardedCallback() {
        val lines =
            listOf(
                "appState.runGroupChange(",
                "    account, group,",
                "    actionLabel = if (draft == null) {",
                "        R.string.remove_photo",
                "    } else { R.string.change_photo },",
                ") {",
                "    updateGroupImage(account, group, bytes, mediaType)",
                "}",
            )
        assertEquals(listOf(5 to 0), groupChangeBodies(lines))
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
                "clearGroupImage",
                "updateGroupProfile",
            )

        val MEMBER_CHANGES =
            listOf(
                "inviteMembersDetailed",
                "inviteMembersDetailedWithInitialAdmins",
                "removeMembersDetailed",
                "promoteAdminDetailed",
                "demoteAdminDetailed",
            )

        /** Maximum metadata header size; the callback's original body window remains unchanged. */
        const val HEADER_WINDOW = 32

        /** How far above a call its runGroupChange callback may open. */
        const val WINDOW = 8

        /** How far below a runGroupChange call its change could open the commit lock. */
        const val NESTED_WINDOW = 4

        /** Fewer matches than this means the gate stopped seeing the call sites. */
        const val MIN_CALLS = 15
    }
}
