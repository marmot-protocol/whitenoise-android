package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import java.util.Locale

private data object SpeakAloudTextContextMenuKey

/** Replaces a system read-aloud Process Text item with the app's queue-aware action. */
internal fun Modifier.appendSpeakAloudTextContextMenuAction(
    enabled: Boolean,
    label: String,
    systemReadAloudLabels: Set<String>,
    onSpeak: () -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        filterTextContextMenuComponents { component -> !component.isSystemReadAloud(systemReadAloudLabels) }
            .appendTextContextMenuComponents {
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
 * The provider's localized label is matched only on Process Text actions, preserving Copy, Select
 * all, Translate, Search, and other integrations.
 */
internal fun systemReadAloudActionLabels(context: Context): Set<String> {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
    val packageManager = context.packageManager
    return packageManager
        .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        .filter { it.isReadAloudActivity() }
        .mapNotNull { resolveInfo ->
            resolveInfo
                .loadLabel(packageManager)
                .toString()
                .takeIf(String::isNotBlank)
        }.toSet()
}

private fun ResolveInfo.isReadAloudActivity(): Boolean {
    val className = activityInfo?.name?.lowercase(Locale.ROOT).orEmpty()
    return className.contains("selecttospeak") || className.contains("readaloud")
}

private fun TextContextMenuComponent.isSystemReadAloud(labels: Set<String>): Boolean =
    when (this) {
        is TextContextMenuItem -> key is ProcessTextKey && label in labels
        else -> false
    }
