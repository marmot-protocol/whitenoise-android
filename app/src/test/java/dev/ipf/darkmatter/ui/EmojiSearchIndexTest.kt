package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiSearchIndexTest {
    @Test
    fun searchMatchesNamesAndKeywordsCaseInsensitively() {
        val index =
            EmojiSearchIndex.fromJson(
                """
                [
                  {"emoji":"🦊","name":"fox","keywords":["animal","face","fox"]},
                  {"emoji":"🔥","name":"fire","keywords":["burn","flame","hot"]},
                  {"emoji":"😀","name":"grinning face","keywords":["cheerful","smile"]},
                  {"emoji":"😄","name":"grinning face with smiling eyes","keywords":["happy","smile"]}
                ]
                """.trimIndent(),
            )

        assertEquals(listOf("🦊"), index.search("FOX").map { it.emoji })
        assertEquals(listOf("🔥"), index.search("flam").map { it.emoji })
        assertEquals(listOf("😀", "😄"), index.search("smile").map { it.emoji })
        assertEquals(emptyList<EmojiSearchEntry>(), index.search("no-match"))
    }

    @Test
    fun bundledEnglishIndexCoversIssueExamples() {
        val resourceFile =
            listOf(
                File("src/main/res/raw/emoji_annotations_en.json"),
                File("app/src/main/res/raw/emoji_annotations_en.json"),
            ).first { it.exists() }
        val index = EmojiSearchIndex.fromJson(resourceFile.readText())

        assertTrue(index.search("fox").any { it.emoji == "🦊" })
        assertTrue(index.search("fire").any { it.emoji == "🔥" })
        val smileResults = index.search("smile").map { it.emoji }
        assertTrue(smileResults.contains("😀"))
        assertTrue(smileResults.contains("😄"))
        assertEquals(emptyList<EmojiSearchEntry>(), index.search("definitely-no-emoji-here"))
    }
}
