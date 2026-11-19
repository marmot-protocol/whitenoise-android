package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay lists are seeded from White Noise's own defaults, never from what the account's projection already holds.
 * Restore must also drop the account's read and write roles, which the per-list setters preserve.
 */
class RelayDefaultsRestoreCoverageTest {
    /** Restore publishes both White Noise lists in one role-free call, never through a role-preserving setter. */
    @Test
    fun restorePublishesBothListsInOneRoleFreeCall() {
        val body =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppStateRelayLists.kt")
                .functionBody("restoreDefaultAccountRelays")
        val call = body.callArguments("publishRelayLists(").single()

        assertTrue(call, "whiteNoiseDefaultRelays(RelayListKind.Nip65)" in call)
        assertTrue(call, "whiteNoiseDefaultRelays(RelayListKind.Inbox)" in call)
        for (preserving in listOf("setAccountNip65Relays", "setAccountInboxRelays", "publishAccountRelays")) {
            assertFalse("Restore must not use $preserving", preserving in body)
        }
        assertFalse("Restore must not read the projected defaults", projectedDefaults.containsMatchIn(body))
    }

    /**
     * Only the missing-list repair seeds a relay list from the projected `defaultRelays`, and #3818 owns that path.
     * Any other relay-list write built from them fails here.
     */
    @Test
    fun onlyTheMissingListRepairSeedsAListFromProjectedDefaults() {
        val seeded =
            appModuleFile("src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val text = file.readText()
                    relayListWrites.flatMap { call ->
                        text
                            .callSites(call)
                            .filter { (_, arguments) -> projectedDefaults.containsMatchIn(arguments) }
                            .map { (start, _) -> "${file.name}:${text.enclosingFunction(start)}" }
                    }
                }.toList()

        assertEquals(listOf("AppStateRelayLists.kt:publishMissingAccountRelayKinds"), seeded)
    }

    private companion object {
        /** Calls that publish or plan a relay list. */
        val relayListWrites =
            listOf("setAccountNip65Relays(", "setAccountInboxRelays(", "publishRelayLists(", "RelayListEditPlan(")

        /** The projection's account-derived seed, never White Noise's own `whiteNoiseDefaultRelays`. */
        val projectedDefaults = Regex("""\bdefaultRelays\b""")

        val functionDeclaration = Regex("""\bfun\s+(?:[\w.<>?, ]+\.)?(\w+)\s*\(""")

        /** Each call to [call] in this text, as its start offset and its balanced argument list. */
        fun String.callSites(call: String): List<Pair<Int, String>> =
            Regex("""(?<![\w.])${Regex.escape(call)}""")
                .findAll(this)
                .map { match ->
                    match.range.first to kotlinBlockFrom(match.range.last, call, opening = '(', closing = ')')
                }.toList()

        /** The argument lists of every call to [call] in this text. */
        fun String.callArguments(call: String): List<String> = callSites(call).map { it.second }

        /** The name of the last function declared before [offset]. */
        fun String.enclosingFunction(offset: Int): String =
            functionDeclaration
                .findAll(substring(0, offset))
                .lastOrNull()
                ?.groupValues
                ?.get(1) ?: "top level"
    }
}
