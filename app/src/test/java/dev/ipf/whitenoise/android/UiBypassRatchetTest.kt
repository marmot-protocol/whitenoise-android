package dev.ipf.whitenoise.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stops new raw UI primitives from creeping back in next to the shared components and theme tokens.
 * For every Kotlin file under `ui/`, counts each pattern in [UiBypassRule] and compares it with the
 * checked-in `ui-bypass/baseline.txt` (one `path|rule=count,...` line per file, sorted, zero counts
 * omitted). A count may only go down; it is not forced down, so parallel changes do not conflict.
 * The shared wrapper and theme files that own a primitive are exempt for that rule.
 */
class UiBypassRatchetTest {
    @Test
    fun rawUiPrimitivesOnlyShrink() {
        val actual = currentCounts()
        proposedBaselineFile().apply { parentFile.mkdirs() }.writeText(render(actual))
        val baseline = readBaseline()
        val failures =
            actual.entries.sortedBy { it.key }.flatMap { (path, counts) ->
                counts.entries.sortedBy { it.key.name }.mapNotNull { (rule, have) ->
                    val limit = baseline[path]?.get(rule) ?: 0
                    if (have > limit) "$path: $have x ${rule.label} (baseline $limit). ${rule.useInstead}" else null
                }
            }
        assertTrue(report(failures), failures.isEmpty())
    }

    private fun report(failures: List<String>): String =
        failures.joinToString("\n", prefix = "$HEADER\n") { "- $it" } +
            "\nThe baseline (app/src/test/resources/ui-bypass/baseline.txt) may only shrink. Replace the " +
            "flagged code with the shared component or token above. Never raise a number or add a line. " +
            "When you remove bypasses, lower the numbers in the same change: the test writes the current " +
            "counts to app/build/ui-bypass/baseline.proposed.txt, copy that over the baseline once it is " +
            "no higher anywhere."

    private fun render(actual: Map<String, Map<UiBypassRule, Int>>): String =
        actual.toSortedMap().entries.joinToString("") { (path, counts) ->
            "$path|" + counts.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}=${it.value}" } + "\n"
        }

    private fun repoFile(path: String): File = File(invariantGateRepositoryRoot(), path)

    private fun proposedBaselineFile(): File = repoFile("app/build/ui-bypass/baseline.proposed.txt")

    private fun baselineFile(): File = repoFile("app/src/test/resources/ui-bypass/baseline.txt")

    private fun readBaseline(): Map<String, Map<UiBypassRule, Int>> =
        baselineFile()
            .readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val (path, counts) = line.split('|', limit = 2)
                path to
                    counts.split(',').associate {
                        val (rule, n) = it.split('=')
                        UiBypassRule.valueOf(rule) to n.toInt()
                    }
            }

    private fun currentCounts(): Map<String, Map<UiBypassRule, Int>> {
        val root = appModuleFile("src/main/java")
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { (path, _) -> "/android/ui/" in path }
            .mapNotNull { (path, file) ->
                val code = stripComments(file.readText())
                val counts =
                    UiBypassRule.entries
                        .filterNot { rule -> rule.isExempt(path) }
                        .associateWith { rule -> rule.pattern.findAll(code).count() }
                        .filterValues { it > 0 }
                if (counts.isEmpty()) null else path to counts
            }.toMap()
    }

    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""(?m)^\s*//.*$"""), "")

    private companion object {
        const val HEADER = "UI bypass ratchet (docs/code-structure.md): raw primitives bypass shared components."
    }
}

private const val UI_THEME = "/android/ui/theme/"
private const val UI_COMMON = "/android/ui/common/"

/** A raw primitive, how to count it, which files own it, and what to use instead. */
internal enum class UiBypassRule(
    val label: String,
    regex: String,
    val useInstead: String,
    private val ownerFiles: Set<String> = emptySet(),
) {
    BOTTOM_SHEET(
        "ModalBottomSheet(",
        """\bModalBottomSheet\(""",
        "Use the shared sheet in ui/common/WhiteNoiseSheets.kt.",
        setOf("${UI_COMMON}WhiteNoiseSheets.kt"),
    ),
    TOP_BAR(
        "TopAppBar(",
        """\b(?:CenterAlignedTopAppBar|MediumTopAppBar|LargeTopAppBar|TopAppBar)\(""",
        "Use WhiteNoiseTopBar from ui/common/WhiteNoiseTopBar.kt.",
        setOf("${UI_COMMON}WhiteNoiseTopBar.kt"),
    ),
    SCAFFOLD(
        "Scaffold(",
        """\bScaffold\(""",
        "Use the screen scaffold in ui/common/WhiteNoiseScaffold.kt.",
        setOf("${UI_COMMON}WhiteNoiseScaffold.kt"),
    ),
    TOAST(
        "Toast.makeText",
        """\bToast\.makeText""",
        "Show feedback through the in-app snackbar or transient presenter, not a system Toast.",
    ),
    DIVIDER(
        "HorizontalDivider(",
        """\bHorizontalDivider\(""",
        "Use AppDivider from ui/common/AppDivider.kt.",
        setOf("${UI_COMMON}AppDivider.kt"),
    ),
    TEXT_FIELD(
        "TextField(",
        """(?<![A-Za-z])(?:Outlined)?TextField\(""",
        "Use WhiteNoiseTextField (ui/common/WhiteNoiseTextField.kt) or ProseTextField.",
        setOf("${UI_COMMON}WhiteNoiseTextField.kt", "${UI_COMMON}ProseTextField.kt"),
    ),
    ROUNDED_SHAPE(
        "RoundedCornerShape(<n>.dp)",
        """RoundedCornerShape\(\s*\d+(?:\.\d+)?\.dp\s*\)""",
        "Use a Radii token (RoundedCornerShape(Radii.md)) or a MaterialTheme.shapes role.",
    ),
    RAW_COLOR(
        "Color(0x",
        """\bColor\(0x""",
        "Use a MaterialTheme.colorScheme role or MaterialTheme.statusColors; new palette values go in ui/theme.",
    ),
    MENU(
        "AnimatedDropdownMenu( / DropdownMenu(",
        """\b(?:AnimatedDropdownMenu|DropdownMenu)\(""",
        "Use WhiteNoiseDropdownMenu for toolbar and row menus, or WhiteNoiseAnchoredMenu for a held row " +
            "(docs/ui-patterns.md, item menus and toolbar menus).",
        setOf(
            "${UI_COMMON}WhiteNoiseMenus.kt",
            "/android/ui/design/AnimatedDropdownMenu.kt",
            "/android/ui/design/AppMenus.kt",
        ),
    ),
    FONT_SIZE(
        "fontSize =",
        """\bfontSize\s*=""",
        "Use a MaterialTheme.typography style; adjust it with copy() only when no style fits.",
    ),
    ;

    val pattern = Regex(regex)

    fun isExempt(path: String): Boolean = UI_THEME in path || ownerFiles.any { path.endsWith(it) }
}
