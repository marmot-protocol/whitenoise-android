package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.ui.settings.SettingsMorphAnchor

/**
 * The composer outline a chat's full-screen routes grow out of. Details and composer settings both
 * unfold from it, in the same card morph that Settings uses between its pages.
 */
internal enum class ConversationMorphAnchor : SettingsMorphAnchor {
    Composer,
}
