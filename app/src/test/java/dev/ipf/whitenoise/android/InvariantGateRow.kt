package dev.ipf.whitenoise.android

import java.io.File

/** One row of the invariant-gate registry in `docs/invariant-gates.md`, with a repository-relative path. */
internal data class InvariantGateRow(
    val name: String,
    val path: String,
    val invariant: String,
    val owner: String,
    val line: Int,
)

internal const val INVARIANT_GATE_REGISTRY_PATH = "docs/invariant-gates.md"
private const val REGISTRY_START = "<!-- invariant-gates:start -->"
private const val REGISTRY_END = "<!-- invariant-gates:end -->"
private const val COVERAGE_TEST_SUFFIX = "CoverageTest.kt"
private const val REGISTRY_COLUMNS = 3
private val gateCell = Regex("""^\[`([A-Za-z0-9_]+)`]\(\.\./([^)\s]+)\)$""")
private val skippedDirectories = setOf("build", "node_modules")

/**
 * Parses the marked registry table into rows, failing on any malformed row so a typo cannot silently
 * unregister a gate. Gate links are relative to `docs/`, and the returned paths are repository-relative.
 */
internal fun parseInvariantGateRegistry(markdown: String): List<InvariantGateRow> {
    val lines = markdown.lines()
    val start = lines.indexOf(REGISTRY_START)
    val end = lines.indexOf(REGISTRY_END)
    require(start >= 0 && end > start) { "registry must sit between $REGISTRY_START and $REGISTRY_END" }
    return lines
        .withIndex()
        .toList()
        .subList(start + 1, end)
        .filter { (_, text) -> text.isNotBlank() && !text.startsWith("| Gate |") && !text.startsWith("| ---") }
        .map { (index, text) -> parseRegistryRow(text, line = index + 1) }
}

/** Parses one `| [`Name`](../path) | invariant | owner |` table row reported at its one-based [line]. */
private fun parseRegistryRow(
    text: String,
    line: Int,
): InvariantGateRow {
    val cells =
        text
            .trim()
            .removePrefix("|")
            .removeSuffix("|")
            .split(" | ")
            .map(String::trim)
    require(cells.size == REGISTRY_COLUMNS) { "line $line: expected $REGISTRY_COLUMNS cells, found ${cells.size}" }
    val gate =
        gateCell.matchEntire(cells[0])
            ?: error("line $line: gate cell must be [`Name`](../path), got ${cells[0]}")
    return InvariantGateRow(
        name = gate.groupValues[1],
        path = gate.groupValues[2],
        invariant = cells[1],
        owner = cells[2],
        line = line,
    )
}

/**
 * Returns every consistency violation between [rows] and the discovered `*CoverageTest.kt` paths in
 * [coverageTests]. [exists] answers whether a repository-relative path is present on disk.
 */
internal fun invariantGateRegistryErrors(
    rows: List<InvariantGateRow>,
    coverageTests: Set<String>,
    exists: (String) -> Boolean,
): List<String> {
    val duplicateNames = rows.groupBy { it.name }.filterValues { it.size > 1 }.keys
    val duplicatePaths = rows.groupBy { it.path }.filterValues { it.size > 1 }.keys
    val unregistered = coverageTests - rows.map { it.path }.toSet()
    return duplicateNames.map { "duplicate registry entry for gate $it" } +
        duplicatePaths.map { "duplicate registry entry for path $it" } +
        rows.mapNotNull { row -> row.rowError(exists)?.let { "line ${row.line}: $it" } } +
        unregistered.sorted().map { "unregistered coverage test $it, add it to $INVARIANT_GATE_REGISTRY_PATH" }
}

/** Returns the first problem local to this row — a missing file, a name mismatch or a blank cell — or null. */
private fun InvariantGateRow.rowError(exists: (String) -> Boolean): String? =
    when {
        !exists(path) -> "registered test $path does not exist"
        File(path).name != "$name.kt" -> "$name does not match its file $path"
        invariant.isBlank() || owner.isBlank() -> "$name needs an invariant and an owner"
        else -> null
    }

/** Finds every `*CoverageTest.kt` under [root], skipping hidden and generated directories, as relative paths. */
internal fun discoverCoverageTests(root: File): Set<String> =
    root
        .walkTopDown()
        .onEnter { it == root || (!it.name.startsWith(".") && it.name !in skippedDirectories) }
        .filter { it.isFile && it.name.endsWith(COVERAGE_TEST_SUFFIX) }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toSet()

/** Resolves the repository root from either the app-module or repository test working directory. */
internal fun invariantGateRepositoryRoot(): File =
    listOf(File(".."), File("."))
        .map(File::getCanonicalFile)
        .first { File(it, INVARIANT_GATE_REGISTRY_PATH).isFile }
