package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import java.util.Locale

private data object SpeakAloudTextContextMenuKey

/** Replaces a system read-aloud Process Text item with the app's queue-aware action. */
internal fun Modifier.appendSpeakAloudTextContextMenuAction(
    enabled: Boolean,
    label: String,
    systemReadAloudKeyIds: Set<Int>,
    onSpeak: () -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        filterTextContextMenuComponents { component ->
            val processTextKey = component.key as? ProcessTextKey
            processTextKey == null || processTextKey.id !in systemReadAloudKeyIds
        }.appendTextContextMenuComponents {
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

/**
 * Finds external Process Text actions that only read the selected text aloud.
 *
 * Compose assigns [ProcessTextKey.id] after applying the same availability filter below. Matching
 * that identity removes only the redundant provider even when another Process Text action has the
 * same localized label.
 */
internal fun systemReadAloudProcessTextKeyIds(context: Context): Set<Int> {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    val availableActivities =
        context.packageManager
            .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .filter { it.isAvailableTo(context) }
    return availableActivities
        .mapIndexedNotNull { index, resolveInfo -> index.takeIf { resolveInfo.isReadAloudActivity() } }
        .toSet()
}

private fun ResolveInfo.isAvailableTo(context: Context): Boolean {
    val info = activityInfo ?: return false
    return when {
        info.packageName == context.packageName -> true
        !info.exported -> false
        info.permission == null -> true
        else -> context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun ResolveInfo.isReadAloudActivity(): Boolean {
    val className = activityInfo?.name?.lowercase(Locale.ROOT).orEmpty()
    return className.contains("selecttospeak") || className.contains("readaloud")
}
