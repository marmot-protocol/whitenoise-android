package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.ui.common.Explanation

internal val ComposerShortcut.labelRes: Int
    @StringRes get() =
        when (this) {
            ComposerShortcut.SendingIdentity -> R.string.share_sending_as
            ComposerShortcut.More -> R.string.attach_options
            ComposerShortcut.Emoji -> R.string.open_emoji_picker
            ComposerShortcut.Paste -> R.string.paste_from_clipboard
            ComposerShortcut.Dictation -> R.string.dictate_text
            ComposerShortcut.Translate -> R.string.translation_composer_toggle
            ComposerShortcut.VoiceNote -> R.string.voice_message_record
            ComposerShortcut.Camera -> R.string.attachment_camera
            ComposerShortcut.Gallery -> R.string.attachment_photos_videos
            ComposerShortcut.Files -> R.string.download_files
            ComposerShortcut.Location -> R.string.attach_location
            ComposerShortcut.Person -> R.string.attach_contact
            ComposerShortcut.Contact -> R.string.attachment_device_contact
            ComposerShortcut.Poll -> R.string.poll_create
            ComposerShortcut.Sticker -> R.string.stickers_title
        }

internal val ComposerShortcut.iconRes: Int
    @DrawableRes get() =
        when (this) {
            ComposerShortcut.SendingIdentity -> R.drawable.ic_settings_account_circle
            ComposerShortcut.More -> R.drawable.ic_add
            ComposerShortcut.Emoji -> R.drawable.ic_emoji_smileys
            ComposerShortcut.Paste -> R.drawable.ic_content_paste
            ComposerShortcut.Dictation -> R.drawable.ic_mic
            ComposerShortcut.Translate -> R.drawable.ic_translate
            ComposerShortcut.VoiceNote -> R.drawable.ic_mic
            ComposerShortcut.Camera -> R.drawable.ic_camera
            ComposerShortcut.Gallery -> R.drawable.ic_image
            ComposerShortcut.Files -> R.drawable.ic_folder
            ComposerShortcut.Location -> R.drawable.ic_location_on
            ComposerShortcut.Person -> R.drawable.ic_person
            ComposerShortcut.Contact -> R.drawable.ic_settings_person_add
            ComposerShortcut.Poll -> R.drawable.ic_description
            ComposerShortcut.Sticker -> R.drawable.ic_sticker
        }

/** What holding each shortcut explains; every toolbar button and + menu row has one. */
internal val ComposerShortcut.explanationRes: Int
    @StringRes get() =
        when (this) {
            ComposerShortcut.SendingIdentity -> R.string.explanation_composer_sending_identity
            ComposerShortcut.More -> R.string.explanation_composer_more
            ComposerShortcut.Emoji -> R.string.explanation_composer_emoji
            ComposerShortcut.Paste -> R.string.explanation_composer_paste
            ComposerShortcut.Dictation -> R.string.explanation_composer_dictation
            ComposerShortcut.Translate -> R.string.explanation_composer_translate
            ComposerShortcut.VoiceNote -> R.string.explanation_composer_voice_note
            ComposerShortcut.Camera -> R.string.explanation_composer_camera
            ComposerShortcut.Gallery -> R.string.explanation_composer_gallery
            ComposerShortcut.Files -> R.string.explanation_composer_files
            ComposerShortcut.Location -> R.string.explanation_composer_location
            ComposerShortcut.Person -> R.string.explanation_composer_person
            ComposerShortcut.Contact -> R.string.explanation_composer_contact
            ComposerShortcut.Poll -> R.string.explanation_composer_poll
            ComposerShortcut.Sticker -> R.string.explanation_composer_sticker
        }

/** The shortcut's explanation; Customize composer lives only in the + menu and Settings. */
@Composable
internal fun composerShortcutExplanation(shortcut: ComposerShortcut): Explanation =
    composerExplanation(shortcut.labelRes, shortcut.explanationRes)

@Composable
internal fun composerExplanation(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
): Explanation = Explanation(title = stringResource(titleRes), body = stringResource(bodyRes))

/** TalkBack keeps a direct Customize composer action on toolbar buttons. */
@Composable
internal fun composerCustomizeAccessibilityActions(onCustomize: (() -> Unit)?): List<CustomAccessibilityAction> {
    val latestCustomize by rememberUpdatedState(onCustomize)
    val label = stringResource(R.string.composer_customize)
    if (onCustomize == null) return emptyList()
    return listOf(
        CustomAccessibilityAction(label) {
            latestCustomize?.invoke()
            true
        },
    )
}
