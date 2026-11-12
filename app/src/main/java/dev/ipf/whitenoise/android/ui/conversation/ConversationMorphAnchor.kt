package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.ui.settings.SettingsMorphAnchor

/**
 * Controls a chat's full-screen routes grow out of. Details unfold from the header and composer
 * settings from the composer, in the same card morph that Settings uses between its pages.
 */
internal enum class ConversationMorphAnchor : SettingsMorphAnchor {
    Details,
    Composer,
}
