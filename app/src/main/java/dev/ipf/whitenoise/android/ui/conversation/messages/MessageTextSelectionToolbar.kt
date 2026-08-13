package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.ui.Modifier

private data object SpeakAloudTextContextMenuKey

/** Adds Speak aloud to the same platform toolbar that owns Copy and Select all. */
internal fun Modifier.appendSpeakAloudTextContextMenuAction(
    enabled: Boolean,
    label: String,
    onSpeak: () -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        appendTextContextMenuComponents {
            separator()
            item(
                key = SpeakAloudTextContextMenuKey,
                label = label,
            ) {
                onSpeak()
                close()
            }
        }
    }
