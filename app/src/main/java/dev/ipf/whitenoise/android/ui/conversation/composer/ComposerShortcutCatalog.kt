package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ComposerShortcut

internal val ComposerShortcut.labelRes: Int
    @StringRes get() =
        when (this) {
            ComposerShortcut.SendingIdentity -> R.string.share_sending_as
            ComposerShortcut.More -> R.string.attach_options
            ComposerShortcut.Emoji -> R.string.open_emoji_picker
            ComposerShortcut.Paste -> R.string.paste_from_clipboard
            ComposerShortcut.Dictation -> R.string.dictate_text
            ComposerShortcut.VoiceNote -> R.string.voice_message_record
            ComposerShortcut.Camera -> R.string.attachment_camera
            ComposerShortcut.Gallery -> R.string.attachment_photos_videos
            ComposerShortcut.Files -> R.string.download_files
            ComposerShortcut.Location -> R.string.attach_location
            ComposerShortcut.Person -> R.string.attach_contact
            ComposerShortcut.Contact -> R.string.attachment_device_contact
            ComposerShortcut.Poll -> R.string.poll_create
        }

internal val ComposerShortcut.iconRes: Int
    @DrawableRes get() =
        when (this) {
            ComposerShortcut.SendingIdentity -> R.drawable.ic_settings_account_circle
            ComposerShortcut.More -> R.drawable.ic_add
            ComposerShortcut.Emoji -> R.drawable.ic_emoji_smileys
            ComposerShortcut.Paste -> R.drawable.ic_content_paste
            ComposerShortcut.Dictation -> R.drawable.ic_mic
            ComposerShortcut.VoiceNote -> R.drawable.ic_mic
            ComposerShortcut.Camera -> R.drawable.ic_camera
            ComposerShortcut.Gallery -> R.drawable.ic_image
            ComposerShortcut.Files -> R.drawable.ic_folder
            ComposerShortcut.Location -> R.drawable.ic_location_on
            ComposerShortcut.Person -> R.drawable.ic_person
            ComposerShortcut.Contact -> R.drawable.ic_settings_person_add
            ComposerShortcut.Poll -> R.drawable.ic_description
        }
