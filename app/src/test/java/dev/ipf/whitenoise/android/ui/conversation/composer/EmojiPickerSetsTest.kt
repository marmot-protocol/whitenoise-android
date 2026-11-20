package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.state.ConversationEmojiSource
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.installedEmojiFixture
import dev.ipf.whitenoise.android.ui.BuiltinEmojiSet
import dev.ipf.whitenoise.android.ui.ConversationEmojiPicker
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.InstalledEmojiPicker
import dev.ipf.whitenoise.android.ui.InstalledEmojiSetTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each emoji set gets its own picker section, and set emoji never repeat in the user's own section. */
class EmojiPickerSetsTest {
    private val library =
        CustomEmojiSet(
            emptyList(),
            InstalledEmojiPicker(
                mapOf(
                    ":fox:" to installedEmojiFixture("fox", "30030:author:forest"),
                    ":owl:" to installedEmojiFixture("owl", "30030:author:forest"),
                    ":cat:" to installedEmojiFixture("cat", "30030:author:pets").copy(setTitle = null),
                ),
            ) { null },
            ConversationEmojiPicker(
                mapOf(":remote:" to ConversationEmojiSource("message", 0, reference(0))),
            ) { _, _ -> null },
        )

    /** Pre-installed sets come first, then one section per installed set under its title. */
    @Test
    fun builtinThenInstalledSections() {
        val picker = emojiPickerSets(library, EmojiPickerPurpose.USE, hidden = emptySet())

        assertEquals(
            listOf(BuiltinEmojiSet.WhiteNoise, BuiltinEmojiSet.Marmot),
            picker.sets.take(2).map { it.category },
        )
        assertEquals(BuiltinEmojiSet.Marmot.shortcodes, picker.sets[1].emoji)
        val installed = picker.sets.drop(2)
        assertEquals(
            listOf(
                InstalledEmojiSetTab("30030:author:forest", "Forest friends", ":fox:"),
                InstalledEmojiSetTab("30030:author:pets", "pets", ":cat:"),
            ),
            installed.map { it.category },
        )
        assertEquals(listOf(listOf(":fox:", ":owl:"), listOf(":cat:")), installed.map { it.emoji })
        assertEquals(listOf(":remote:"), picker.custom)
    }

    /** A removed pre-installed set loses its section without its emoji moving into the user's own section. */
    @Test
    fun hiddenBuiltinSetHasNoSection() {
        val picker = emojiPickerSets(library, EmojiPickerPurpose.USE, hidden = setOf(BuiltinEmojiSet.Marmot))

        assertTrue(picker.sets.none { it.category == BuiltinEmojiSet.Marmot })
        assertEquals(BuiltinEmojiSet.WhiteNoise, picker.sets.first().category)
        assertTrue(picker.custom.none { it in BuiltinEmojiSet.Marmot.shortcodes })
    }

    /** Device-wide reaction slots keep the bundled sets but cannot use account-installed artwork. */
    @Test
    fun quickReactionsShowOnlyBuiltinSets() {
        val picker = emojiPickerSets(library, EmojiPickerPurpose.CONFIGURE_QUICK_REACTION, hidden = emptySet())

        assertEquals(BuiltinEmojiSet.entries, picker.sets.map { it.category })
        assertTrue(picker.custom.isEmpty())
    }

    /** The group image picker has no shortcode artwork at all. */
    @Test
    fun groupImageHasNoSets() {
        val picker = emojiPickerSets(library, EmojiPickerPurpose.GROUP_IMAGE, hidden = emptySet())

        assertTrue(picker.sets.isEmpty())
        assertTrue(picker.custom.isEmpty())
    }
}
