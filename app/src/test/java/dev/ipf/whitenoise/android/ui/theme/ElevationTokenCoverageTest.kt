package dev.ipf.whitenoise.android.ui.theme

import dev.ipf.whitenoise.android.appModuleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shadows and tonal tints come from one scale (#4101). Feature code names the kind of surface through
 * [WhiteNoiseElevation] — or a Material default such as `MenuDefaults.ShadowElevation` — and never writes a
 * number, so floating controls, menus and dialogs lift the same distance everywhere.
 */
class ElevationTokenCoverageTest {
    /** No elevation literal remains outside the theme package. */
    @Test
    fun featureCodeTakesEveryElevationFromTheScale() {
        val root = appModuleFile(SOURCE_ROOT)
        val literals =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && THEME_PACKAGE !in it.invariantSeparatorsPath }
                .flatMap { file ->
                    val lines = file.readLines().map { it.substringBefore("//") }
                    lines.mapIndexedNotNull { index, line ->
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                            .takeIf {
                                elevationLiteral.containsMatchIn(line.withWrappedValue(lines.getOrNull(index + 1)))
                            }
                    }
                }.toList()
        assertEquals(
            "Use a WhiteNoiseElevation token (ui/theme/WhiteNoiseElevation.kt) instead of an elevation literal:\n" +
                literals.joinToString("\n"),
            emptyList<String>(),
            literals,
        )
    }

    /** The pattern catches arguments, constants, branches and elevation calls, so the scan cannot pass vacuously. */
    @Test
    fun literalPatternFindsArgumentsAndConstants() {
        listOf(
            "shadowElevation = 2.dp,",
            "tonalElevation = 0.dp",
            "elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),",
            "private val SettingsBottomActionElevation = 2.dp",
            "tonalElevation = if (morphing) Dp(0f) else tonalElevation,",
            "tonalElevation = if (flat) WhiteNoiseElevation.None else 2.dp,",
            "modifier = Modifier.shadow(4.dp, CircleShape),",
            "color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),",
            "elevation = CardDefaults.cardElevation(2.dp),",
            "shadowElevation =".withWrappedValue("    2.dp,"),
        ).forEach { assertTrue(it, elevationLiteral.containsMatchIn(it)) }
        listOf(
            "shadowElevation = WhiteNoiseElevation.FloatingControl,",
            "tonalElevation = MenuDefaults.TonalElevation,",
            "val elevated = 2.dp",
        ).forEach { assertTrue(it, !elevationLiteral.containsMatchIn(it)) }
    }

    /** The scale stays on Material 3's six levels, with floating controls on level 3 and menus on level 2. */
    @Test
    fun scaleUsesMaterialLevels() {
        val levels =
            listOf(
                WhiteNoiseElevation.Level0,
                WhiteNoiseElevation.Level1,
                WhiteNoiseElevation.Level2,
                WhiteNoiseElevation.Level3,
                WhiteNoiseElevation.Level4,
                WhiteNoiseElevation.Level5,
            ).map { it.value }
        assertEquals(listOf(0f, 1f, 3f, 6f, 8f, 12f), levels)
        assertEquals(WhiteNoiseElevation.Level3, WhiteNoiseElevation.FloatingControl)
        assertEquals(WhiteNoiseElevation.Level2, WhiteNoiseElevation.Menu)
    }

    private companion object {
        const val SOURCE_ROOT = "src/main/java/dev/ipf/whitenoise/android"
        const val THEME_PACKAGE = "/ui/theme/"

        /** An elevation assignment or elevation call whose value, before the next comma, holds a literal. */
        val elevationLiteral =
            Regex(
                """([Ee]levation\w*\s*(:\s*Dp\s*)?=|\.shadow\(|surfaceColorAtElevation\(|cardElevation\()""" +
                    """[^,]*?(\b[0-9.]+\.dp\b|\bDp\()""",
            )

        /** This line with the next one appended when it ends in `=` or `(`, so a wrapped value is read too. */
        fun String.withWrappedValue(next: String?): String =
            if (next != null && trimEnd().let { it.endsWith("=") || it.endsWith("(") }) "$this ${next.trim()}" else this
    }
}
