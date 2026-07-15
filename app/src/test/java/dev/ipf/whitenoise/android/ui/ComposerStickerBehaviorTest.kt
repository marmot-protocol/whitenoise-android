package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.composer.canPickSticker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerStickerBehaviorTest {
    @Test
    fun stickerPickerIsAvailableOnlyForStandaloneMessages() {
        assertTrue(canPickSticker(isReplying = false, isEditing = false))
        assertFalse(canPickSticker(isReplying = true, isEditing = false))
        assertFalse(canPickSticker(isReplying = false, isEditing = true))
    }
}
