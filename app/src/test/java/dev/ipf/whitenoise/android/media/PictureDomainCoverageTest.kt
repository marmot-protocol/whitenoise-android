package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every production picture decode names its worker domain (#3373). The avatar loader and the generic picture rows
 * fall back to a single-use worker, which is safe but slow, so a call that relies on the fallback is a missed
 * domain: a kind-0 picture names the public profiles, and a group's URL picture names its presentation's domain.
 */
class PictureDomainCoverageTest {
    /** Each avatar-loader call passes its domain, as the argument after the URL and any target width. */
    @Test
    fun avatarLoaderCallsNameTheirDomain() {
        val required = mapOf("load" to 2, "loadBitmap" to 2, "preWarm" to 2, "loadBanner" to 3, "retryBanner" to 3)
        val calls =
            mainSources().flatMap { file ->
                required.flatMap { (name, count) ->
                    calls(file, """(?<![A-Za-z0-9_])AvatarImageLoader\s*\.\s*$name\(""").map { it to count }
                }
            }
        assertTrue("the scan found the loader's callers", calls.size > 10)
        val undeclared =
            calls.filter { (call, count) ->
                call.arguments.size < count && call.arguments.none { it.name == "domain" }
            }
        assertEquals("loader calls without a domain", emptyList<String>(), undeclared.map { it.first.location })
    }

    /**
     * Each production photo-editor inspection, preview and render names its worker domain: its draft's shared outgoing
     * domain, or single-use for an identity or contact picture. The renderer's single-use default is never relied on.
     */
    @Test
    fun editorRendererCallsNameTheirDomain() {
        val required = mapOf("inspect" to 2, "decodePreview" to 2, "render" to 4)
        val calls =
            mainSources().flatMap { file ->
                required.flatMap { (name, count) ->
                    val receiver = """(?<![A-Za-z0-9_])(renderer|PhotoEditorRenderer\(\))"""
                    calls(file, """$receiver\s*\.\s*$name\(""").map { it to count }
                }
            }
        assertTrue("the scan found the renderer's callers", calls.size >= MIN_RENDERER_CALLS)
        val undeclared =
            calls.filter { (call, count) ->
                call.arguments.size < count && call.arguments.none { it.name == "domain" }
            }
        assertEquals("renderer calls without a domain", emptyList<String>(), undeclared.map { it.first.location })
    }

    /**
     * Every outgoing domain is made by `MediaTrustDomain.outgoing`, the one policy that maps a draft's picks to their
     * conversation's shared worker, so no preview, staging, editor, thumbhash or poster decode can disagree.
     */
    @Test
    fun outgoingDomainsAreMadeOnlyByTheirPickPolicy() {
        val construction = Regex("""(?<![A-Za-z0-9_])Outgoing\s*\(""")
        val outside =
            mainSources().flatMap { file ->
                val text = file.readText()
                construction
                    .findAll(text)
                    .filterNot { text.substring(maxOf(0, it.range.first - 6), it.range.first) == "class " }
                    .filterNot { file.name == "MediaTrustDomain.kt" && insidePickPolicy(text, it.range.first) }
                    .map { "${file.name}:${lineOf(text, it.range.first)}" }
                    .toList()
            }
        assertEquals("outgoing domains made outside the pick policy", emptyList<String>(), outside)
    }

    /** Whether [offset] lies in the body of `fun outgoing(` in the domain's own file. */
    private fun insidePickPolicy(
        text: String,
        offset: Int,
    ): Boolean {
        val start = text.indexOf("fun outgoing(")
        val end = text.indexOf("fun local(", start)
        return start in 0..offset && offset < end
    }

    /** No production code passes a decoding avatar-loader function by reference, which could only use the default. */
    @Test
    fun avatarLoaderIsNeverPassedByReference() {
        val reference =
            Regex("""(?<![A-Za-z0-9_])AvatarImageLoader\s*::\s*(load|loadBanner|retryBanner|preWarm|loadBitmap)\b""")
        val references =
            mainSources().flatMap { file ->
                val text = file.readText()
                reference.findAll(text).map { match -> "${file.name}:${lineOf(text, match.range.first)}" }
            }
        assertEquals("loader references that rely on the default domain", emptyList<String>(), references)
    }

    /** Each generic Avatar, ContactRow or group header given a picture URL names that picture's domain. */
    @Test
    fun genericPictureRowsNameTheirDomain() {
        val rows =
            listOf(
                Row("""(?<![A-Za-z0-9_])Avatar\(""", url = "pictureUrl", domain = "pictureDomain", urlPosition = 3),
                Row("""(?<![A-Za-z0-9_])ContactRow\(""", url = "avatarUrl", domain = "avatarDomain", urlPosition = 3),
                Row("""(?<![A-Za-z0-9_])GroupDetailsHeader\(""", url = "pictureUrl", domain = "pictureDomain"),
            )
        val calls = mainSources().flatMap { file -> rows.flatMap { row -> calls(file, row.pattern).map { it to row } } }
        assertTrue("the scan found the generic rows", calls.size > 30)
        val undeclared =
            calls.filter { (call, row) ->
                val named = call.arguments.firstOrNull { it.name == row.url }
                val positional = row.urlPosition?.let { call.arguments.getOrNull(it) }?.takeIf { it.name == null }
                val url = named ?: positional
                url != null && url.value != "null" && call.arguments.none { it.name == row.domain }
            }
        assertEquals("picture rows without a domain", emptyList<String>(), undeclared.map { it.first.location })
    }

    /** One generic picture row: its call pattern and the parameters that carry its URL and its domain. */
    private data class Row(
        val pattern: String,
        val url: String,
        val domain: String,
        val urlPosition: Int? = null,
    )

    /** One call site: where it is and its top-level arguments. */
    private data class Call(
        val location: String,
        val arguments: List<Argument>,
    )

    /** One top-level argument, with its parameter name when it is passed by name. */
    private data class Argument(
        val name: String?,
        val value: String,
    )

    /** Every call matching [pattern] in [file], skipping declarations, with its top-level arguments. */
    private fun calls(
        file: File,
        pattern: String,
    ): List<Call> {
        val text = file.readText()
        return Regex(pattern)
            .findAll(text)
            .filterNot { text.substring(maxOf(0, it.range.first - 4), it.range.first) == "fun " }
            .map { match ->
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                Call("${file.name}:$line", arguments(text, match.range.last + 1))
            }.toList()
    }

    /** Splits the argument list that opens just before [start] at top-level commas, skipping strings and comments. */
    private fun arguments(
        text: String,
        start: Int,
    ): List<Argument> {
        val parts = mutableListOf<String>()
        var depth = 0
        var partStart = start
        var index = start
        var closed = false
        while (index < text.length && !closed) {
            val skipped = literalEnd(text, index)
            val char = text[index]
            when {
                skipped > index -> index = skipped - 1
                char == ')' && depth == 0 -> closed = true
                char == ',' && depth == 0 -> {
                    parts += text.substring(partStart, index)
                    partStart = index + 1
                }
                else -> depth += depthChange(char)
            }
            if (closed) parts += text.substring(partStart, index)
            index++
        }
        val comments = Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        return parts.map { comments.replace(it, "").trim() }.filter(String::isNotEmpty).map { part ->
            val named = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=(?!=)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL).find(part)
            if (named == null) Argument(null, part) else Argument(named.groupValues[1], named.groupValues[2].trim())
        }
    }

    /** One for an opening bracket, minus one for a closing one, otherwise zero. */
    private fun depthChange(char: Char): Int =
        when (char) {
            '(', '[', '{' -> 1
            ')', ']', '}' -> -1
            else -> 0
        }

    /** The index just past a string literal or comment that starts at [index], or [index] when none does. */
    private fun literalEnd(
        text: String,
        index: Int,
    ): Int =
        when {
            text.startsWith("//", index) -> text.indexOf('\n', index).let { if (it < 0) text.length else it }
            text.startsWith("/*", index) -> text.indexOf("*/", index).let { if (it < 0) text.length else it + 2 }
            text[index] == '"' -> stringEnd(text, index)
            else -> index
        }

    /** The index just past the string literal whose opening quote is at [index], honouring escapes. */
    private fun stringEnd(
        text: String,
        index: Int,
    ): Int {
        var at = index + 1
        while (at < text.length && text[at] != '"') at += if (text[at] == '\\') 2 else 1
        return minOf(at + 1, text.length)
    }

    /** The one-based line of [offset] in [text]. */
    private fun lineOf(
        text: String,
        offset: Int,
    ): Int = text.substring(0, offset).count { it == '\n' } + 1

    /** The app's main sources, from either the module or the repository root. */
    private fun mainSources(): List<File> {
        val root =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android"),
                File("app/src/main/java/dev/ipf/whitenoise/android"),
            ).first(File::isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val MIN_RENDERER_CALLS = 8
    }
}
