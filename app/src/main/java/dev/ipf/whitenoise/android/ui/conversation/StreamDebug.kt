package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.MessageDebugCategory
import dev.ipf.whitenoise.android.core.MessageDebugStyle
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

// Category -> accent color for the streaming-debug row. Kept out of the
// Compose-free MessageDebugStyle classifier and resolved here so the
// pure-Kotlin classifier stays JVM-testable. Literal hues stay legible across
// the light/dark/amoled themes the app ships.
private fun MessageDebugCategory.accentColor(): Color =
    when (this) {
        MessageDebugCategory.UserVisible -> Color(0xFF2E7D32)
        MessageDebugCategory.StreamSignaling -> Color(0xFFEF6C00)
        MessageDebugCategory.AgentChrome -> Color(0xFF7B1FA2)
        MessageDebugCategory.GroupSystem -> Color(0xFF607D8B)
        MessageDebugCategory.Control -> Color(0xFFB28900)
        MessageDebugCategory.Unknown -> Color(0xFFC62828)
    }

/**
 * Inline streaming-debug row. Renders a non-user-visible signaling record
 * (agent-stream-start, reaction, delete, group-system, unknown) with debug
 * chrome: a category-accented header (category label + kind label), the
 * kind-specific detail, and a multi-line tag summary, all in a small monospace
 * caption. Display-only — wires NO reply/long-press gestures and does no
 * read-marking. Only rendered when [WhiteNoiseAppState.streamingDebugEnabled]
 * is true.
 */
@Composable
internal fun MessageDebugRow(
    style: MessageDebugStyle,
    record: AppMessageRecordFfi,
) {
    val accent = style.category.accentColor()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = accent.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                    ).border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    ).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            val idSuffix =
                record.messageIdHex
                    .takeIf { it.isNotBlank() }
                    ?.take(8)
                    ?.let { " · $it" } ?: ""
            Text(
                text = "${style.category.label} · ${style.kindLabel}$idSuffix",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
            if (style.detailText.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = style.detailText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = style.tagsSummary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Inline row for one live QUIC agent-stream update shown during streaming
 * debug. Surfaces the chunk / status / progress / record / finished / failed
 * events the conversation otherwise drops. Display-only: wires no gestures and
 * does no read-marking. Only rendered for synthetic `dbg:stream:` timeline
 * rows, which exist only while [WhiteNoiseAppState.streamingDebugEnabled] is
 * true.
 */
@Composable
internal fun StreamDebugEventRow(record: AppMessageRecordFfi) {
    val accent = MessageDebugCategory.StreamSignaling.accentColor()
    val eventKind =
        record.tags
            .firstOrNull { it.values.firstOrNull() == "dbg" }
            ?.values
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: "event"
    val streamId = MessageProjector.streamId(record).orEmpty()
    val detail = record.plaintext
    val tagsSummary =
        record.tags
            .joinToString(" · ") { tag -> tag.values.joinToString(" ") }
            .ifBlank { "tags: (none)" }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = accent.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                    ).border(
                        width = 1.dp,
                        color = accent.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                    ).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = "QUIC · $eventKind · ${MessageDebugCategory.StreamSignaling.label}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "stream ${shortStreamId(streamId)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = tagsSummary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Abbreviate a long stream id to head…tail, leaving short ids untouched so
// they stay copy-comparable.
private fun shortStreamId(streamId: String): String {
    if (streamId.length <= 16) return streamId.ifBlank { "(none)" }
    return "${streamId.take(8)}…${streamId.takeLast(8)}"
}
