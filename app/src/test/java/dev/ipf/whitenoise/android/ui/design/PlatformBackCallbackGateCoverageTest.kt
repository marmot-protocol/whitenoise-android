package dev.ipf.whitenoise.android.ui.design

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's own `OnBackInvokedCallback`s are registered only where the platform delivers them. MainActivity opts in
 * with the activity-level `android:enableOnBackInvokedCallback`, which Android honours from 14. Android 13 drops a
 * callback registered behind an Android 13 check, so Back falls through to the screen under an open overlay.
 */
class PlatformBackCallbackGateCoverageTest {
    /** Every call to a platform Back handler sits behind an Android 14 check, never an Android 13 one. */
    @Test
    fun platformBackHandlersAreGatedOnAndroid14() {
        val calls =
            mainSources().flatMap { (name, text) ->
                platformBackHandlers.flatMap { handler -> text.callSites(handler).map { name to text.gateBefore(it) } }
            }

        assertTrue("no platform Back handler call was found", calls.isNotEmpty())
        calls.forEach { (file, gate) ->
            assertTrue("$file gates a platform Back handler on `$gate`", isAndroid14Gate(gate))
        }
    }

    /** Only a positive Android 14 check counts: a negated, lower-version or OR-ed condition does not. */
    @Test
    fun onlyPositiveAndroid14ChecksCountAsTheGate() {
        listOf(
            "(platformBackCallbacksEnabled())",
            "(overlayBack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)",
        ).forEach { assertTrue(it, isAndroid14Gate(it)) }
        listOf(
            "(!platformBackCallbacksEnabled())",
            "(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)",
            "(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)",
            "(platformBackCallbacksEnabled() || legacyBack)",
            "no if",
        ).forEach { assertFalse(it, isAndroid14Gate(it)) }
    }

    /** A direct platform registration lives only inside one of the gated handlers, so no new one can skip the gate. */
    @Test
    fun platformRegistrationsLiveOnlyInsideTheGatedHandlers() {
        val owners =
            mainSources().flatMap { (_, text) ->
                text.callSites("registerOnBackInvokedCallback").map { text.enclosingFunction(it) }
            }

        assertTrue("no platform Back registration was found", owners.isNotEmpty())
        assertEquals(emptyList<String>(), owners.filterNot { it in platformBackHandlers })
    }

    private companion object {
        /** The composables that register a platform `OnBackInvokedCallback`. */
        val platformBackHandlers =
            listOf(
                "KeyboardSafePopupPlatformBackHandler",
                "ComposerPlatformPreImeBackHandler",
                "ShareChatPickerOverlayBackHandler",
            )

        /** Clauses that are true only from Android 14, where MainActivity's opt-in takes effect. */
        val android14Clauses =
            listOf(
                Regex("""platformBackCallbacksEnabled\(\)"""),
                Regex("""(Build\.VERSION\.)?SDK_INT\s*>=\s*(Build\.VERSION_CODES\.)?UPSIDE_DOWN_CAKE"""),
            )

        /**
         * Whether the `if` [condition] holds only from Android 14: one of its `&&` clauses is exactly a positive
         * Android 14 check, and no `||` can let a lower version through.
         */
        fun isAndroid14Gate(condition: String): Boolean {
            val body = condition.trim().removePrefix("(").removeSuffix(")")
            return "||" !in body &&
                body.split("&&").map { it.trim() }.any { clause -> android14Clauses.any { it.matches(clause) } }
        }

        val functionDeclaration = Regex("""\bfun\s+(?:[\w.<>?, ]+\.)?(\w+)\s*\(""")

        /** Every app source file under `src/main/java`, as its name and text. */
        fun mainSources(): List<Pair<String, String>> =
            appModuleFile("src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.name to it.readText() }
                .toList()

        /** Offsets of calls to [name], leaving out its declaration. */
        fun String.callSites(name: String): List<Int> =
            Regex("""(?<!\w)${Regex.escape(name)}\(""")
                .findAll(this)
                .map { it.range.first }
                .filterNot { substring(maxOf(0, it - 4), it) == "fun " }
                .toList()

        /** The condition of the nearest `if` before [offset]. */
        fun String.gateBefore(offset: Int): String {
            val condition = lastIndexOf("if (", offset)
            return if (condition < 0) "no if" else kotlinBlockFrom(condition + 3, "if condition", '(', ')')
        }

        /** The name of the last function declared before [offset]. */
        fun String.enclosingFunction(offset: Int): String =
            functionDeclaration
                .findAll(substring(0, offset))
                .lastOrNull()
                ?.groupValues
                ?.get(1) ?: "top level"
    }
}
