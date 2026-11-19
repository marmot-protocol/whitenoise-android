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
    /** Every call to a platform Back handler sits inside an Android 14 check's branch, never an Android 13 one. */
    @Test
    fun platformBackHandlersAreGatedOnAndroid14() {
        val calls =
            mainSources().flatMap { (name, text) ->
                platformBackHandlers.flatMap { handler -> text.callSites(handler).map { Triple(name, text, it) } }
            }

        assertTrue("no platform Back handler call was found", calls.isNotEmpty())
        calls.forEach { (file, text, offset) ->
            val message = "$file:${text.lineAt(offset)} calls a platform Back handler outside an Android 14 check"
            assertTrue(message, text.isGatedOnAndroid14(offset))
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
        ).forEach { assertFalse(it, isAndroid14Gate(it)) }
    }

    /** A handler call counts as gated only inside the positive branch of an Android 14 check, nested or not. */
    @Test
    fun onlyCallsInsideTheGatesBranchCountAsGated() {
        val gate = "if (platformBackCallbacksEnabled())"
        mapOf(
            "$gate {\n    Handler()\n}" to true,
            "$gate {\n    if (visible) {\n        Handler (true)\n    }\n}" to true,
            "$gate { prepare() }\nHandler()" to false,
            "$gate { prepare() } else {\n    Handler()\n}" to false,
            "if (visible) {\n    Handler()\n}" to false,
        ).forEach { (source, gated) ->
            val calls = source.callSites("Handler")
            assertEquals(source, 1, calls.size)
            assertEquals(source, gated, source.isGatedOnAndroid14(calls.single()))
        }
    }

    /** Call sites are found with or without a space before `(`, and a declaration is never one. */
    @Test
    fun callSitesSkipDeclarationsAndAllowSpacedCalls() {
        val source =
            """
            internal fun Handler(enabled: Boolean) {}
            private fun Handler (enabled: Boolean) {}
            fun Scope.Handler(enabled: Boolean) {}
            Handler(true)
            Handler (false)
            OtherHandler(true)
            """.trimIndent()

        assertEquals(listOf(4, 5), source.callSites("Handler").map { source.lineAt(it) })
    }

    /** A direct platform registration lives only inside one of the gated handlers, so no new one can skip the gate. */
    @Test
    fun platformRegistrationsLiveOnlyInsideTheGatedHandlers() {
        val registrations =
            mainSources().flatMap { (name, text) ->
                text.callSites(REGISTRATION).map { Triple(name, text, it) }
            }

        assertTrue("no platform Back registration was found", registrations.isNotEmpty())
        val outside = registrations.filterNot { (_, text, offset) -> text.isInsideHandler(offset) }
        assertEquals(emptyList<String>(), outside.map { (file, text, offset) -> "$file:${text.lineAt(offset)}" })
    }

    /** Only a registration inside a handler's body counts, not one in code that merely follows a handler. */
    @Test
    fun onlyRegistrationsInsideAHandlersBodyCount() {
        val handler = "fun ${platformBackHandlers.first()}(enabled: Boolean) {\n"
        mapOf(
            "${handler}DisposableEffect(enabled) {\n    dispatcher.$REGISTRATION(0, callback)\n}\n}" to true,
            "$handler}\nclass Other {\n    init {\n        dispatcher.$REGISTRATION (0, callback)\n    }\n}" to false,
            "$handler}\nfun other() {\n    dispatcher.$REGISTRATION(0, callback)\n}" to false,
        ).forEach { (source, inside) ->
            assertEquals(source, inside, source.isInsideHandler(source.callSites(REGISTRATION).single()))
        }
    }

    private companion object {
        const val REGISTRATION = "registerOnBackInvokedCallback"

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

        val ifStatement = Regex("""\bif\s*\(""")
        val functionDeclaration = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)\s*\(""")
        val declarationPrefix = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?$""")

        /**
         * Whether the `if` [condition] holds only from Android 14: one of its `&&` clauses is exactly a positive
         * Android 14 check, and no `||` can let a lower version through.
         */
        fun isAndroid14Gate(condition: String): Boolean {
            val body = condition.trim().removePrefix("(").removeSuffix(")")
            return "||" !in body &&
                body.split("&&").map { it.trim() }.any { clause -> android14Clauses.any { it.matches(clause) } }
        }

        /** Every app source file under `src/main/java`, as its name and text. */
        fun mainSources(): List<Pair<String, String>> =
            appModuleFile("src/main/java")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.name to it.readText() }
                .toList()

        /** Offsets of calls to [name], spaced before `(` or not, leaving out its declarations. */
        fun String.callSites(name: String): List<Int> =
            Regex("""(?<!\w)${Regex.escape(name)}\s*\(""")
                .findAll(this)
                .map { it.range.first }
                .filterNot { declarationPrefix.containsMatchIn(substring(lastIndexOf('\n', it - 1) + 1, it)) }
                .toList()

        /** Whether [offset] lies in the positive branch of an `if` that holds only from Android 14. */
        fun String.isGatedOnAndroid14(offset: Int): Boolean =
            ifStatement.findAll(substring(0, offset)).any { match ->
                val condition = kotlinBlockFrom(match.range.last, "if condition", '(', ')')
                val branch = nextNonBlank(match.range.last + condition.length)
                isAndroid14Gate(condition) && getOrNull(branch) == '{' && offset in blockAt(branch)
            }

        /** Whether [offset] lies inside the braced body of one of the [platformBackHandlers]. */
        fun String.isInsideHandler(offset: Int): Boolean =
            functionDeclaration
                .findAll(substring(0, offset))
                .filter { it.groupValues[1] in platformBackHandlers }
                .any { match ->
                    val parameters = kotlinBlockFrom(match.range.last, "parameters", '(', ')')
                    val body = indexOf('{', match.range.last + parameters.length)
                    body in 0 until offset && offset in blockAt(body)
                }

        /** The offsets of the balanced `{}` block that opens at [start]. */
        fun String.blockAt(start: Int): IntRange = start until start + kotlinBlockFrom(start, "block").length

        /** The index of the first non-whitespace character from [start], or -1. */
        fun String.nextNonBlank(start: Int): Int = (start until length).firstOrNull { !this[it].isWhitespace() } ?: -1

        /** The 1-based line number of [offset]. */
        fun String.lineAt(offset: Int): Int = substring(0, offset).count { it == '\n' } + 1
    }
}
