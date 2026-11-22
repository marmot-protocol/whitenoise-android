package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolve a bounded set of local images. Cards without usable artwork keep the native
 * RemoteInput row.
 */
internal suspend fun notificationReactionChipArtwork(
    context: Context,
    choices: List<String>,
): Map<String, Bitmap> {
    if (choices.none(EmojiShortcodes::isShortcode)) return emptyMap()
    return try {
        withTimeoutOrNull(NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val budget = EmojiReadBudget()
                choices
                    .filter(EmojiShortcodes::isShortcode)
                    .mapNotNull { code ->
                        notificationEmojiBitmap(context, code, budget)?.let { source ->
                            try {
                                code to centeredReactionChip(source)
                            } finally {
                                source.recycle()
                            }
                        }
                    }.toMap()
            }
        }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught")
        _: Exception,
    ) {
        emptyMap()
    } catch (_: OutOfMemoryError) {
        emptyMap()
    }
}

private fun centeredReactionChip(source: Bitmap): Bitmap {
    val tile = Bitmap.createBitmap(CHIP_TILE_PX, CHIP_TILE_PX, Bitmap.Config.ARGB_8888)
    val scale = CHIP_TILE_PX.toFloat() / maxOf(source.width, source.height)
    val halfWidth = source.width * scale / 2
    val halfHeight = source.height * scale / 2
    val destination =
        RectF(
            CHIP_TILE_CENTER_PX - halfWidth,
            CHIP_TILE_CENTER_PX - halfHeight,
            CHIP_TILE_CENTER_PX + halfWidth,
            CHIP_TILE_CENTER_PX + halfHeight,
        )
    Canvas(tile).drawBitmap(source, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
    return tile
}

private const val CHIP_TILE_PX = 64
private const val CHIP_TILE_CENTER_PX = CHIP_TILE_PX / 2f

internal const val EXTRA_QUICK_REACTION_ROWS = "dev.ipf.whitenoise.android.notification.QUICK_REACTION_ROWS"
internal const val EXTRA_QUICK_REACTION_TARGET = "dev.ipf.whitenoise.android.notification.QUICK_REACTION_TARGET"

internal fun notificationQuickReactionRows(
    context: Context,
    choices: List<String>,
    artwork: Map<String, Bitmap>,
    pendingIntent: (String) -> PendingIntent,
): RemoteViews {
    val rows = RemoteViews(context.packageName, R.layout.notification_quick_reaction_container)
    val columns =
        ((context.resources.configuration.screenWidthDp - REACTION_HORIZONTAL_INSET_DP) / CHIP_STRIDE_DP)
            .coerceIn(MIN_CHIP_COLUMNS, MAX_NOTIFICATION_QUICK_REACTIONS)
    choices.chunked(columns).forEach { values ->
        val row = RemoteViews(context.packageName, R.layout.notification_quick_reaction_row)
        values.forEach { reaction ->
            val image = artwork[reaction]
            val chip = RemoteViews(context.packageName, R.layout.notification_quick_reaction_chip)
            chip.setViewVisibility(
                R.id.notification_quick_reaction_image,
                if (image == null) View.GONE else View.VISIBLE,
            )
            chip.setViewVisibility(
                R.id.notification_quick_reaction_text,
                if (image == null) View.VISIBLE else View.GONE,
            )
            if (image == null) {
                chip.setTextViewText(R.id.notification_quick_reaction_text, reaction)
            } else {
                chip.setImageViewBitmap(R.id.notification_quick_reaction_image, image)
            }
            chip.setContentDescription(
                R.id.notification_quick_reaction_chip,
                EmojiShortcodes.plainLabel(reaction, context.resources),
            )
            chip.setOnClickPendingIntent(R.id.notification_quick_reaction_chip, pendingIntent(reaction))
            row.addView(R.id.notification_quick_reaction_slots, chip)
        }
        rows.addView(R.id.notification_quick_reaction_container, row)
    }
    return rows
}

/**
 * Build the expanded row from the latest accepted message presentation. Keep the
 * MessagingStyle extras for app-side history, edits, and preview reconciliation.
 */
@Suppress("DEPRECATION")
internal fun withQuickReactionRows(
    context: Context,
    notification: Notification,
): Notification {
    val rows =
        BundleCompat.getParcelable(notification.extras, EXTRA_QUICK_REACTION_ROWS, RemoteViews::class.java)
            ?: return notification
    val reply =
        notification.actions.orEmpty().firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
    val messaging = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    val targetMatches =
        notification.extras.getString(EXTRA_QUICK_REACTION_TARGET) == conversationCardMessageIdHex(notification)
    val hasTarget = targetMatches && messaging?.messages?.isNotEmpty() == true
    return if (reply != null && messaging != null && hasTarget) {
        decorateQuickReactionRows(context, notification, rows, messaging, reply)
    } else {
        // A deletion or another rewrite may have removed the target. Never carry its clickable layout forward.
        NotificationCompat
            .Builder(context, notification)
            .setGroupAlertBehavior(notification.groupAlertBehavior)
            .setCustomBigContentView(null)
            .apply {
                extras.remove(EXTRA_QUICK_REACTION_ROWS)
                extras.remove(EXTRA_QUICK_REACTION_TARGET)
            }.build()
    }
}

@Suppress("DEPRECATION")
private fun decorateQuickReactionRows(
    context: Context,
    notification: Notification,
    rows: RemoteViews,
    messaging: NotificationCompat.MessagingStyle,
    reply: Notification.Action,
): Notification {
    val input = reply.remoteInputs?.singleOrNull() ?: return notification
    val typingOnly = RemoteInput.Builder(input.resultKey).setLabel(input.label).build()
    val customReply =
        Notification.Action
            .Builder(reply.getIcon(), reply.title, reply.actionIntent)
            .addExtras(reply.extras)
            .addRemoteInput(typingOnly)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    val view =
        RemoteViews(context.packageName, R.layout.notification_quick_reactions).apply {
            setTextViewText(
                R.id.notification_quick_reactions_title,
                messaging.conversationTitle
                    ?: messaging.messages
                        .last()
                        .person
                        ?.name
                    ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            )
            setTextViewText(
                R.id.notification_quick_reactions_body,
                messaging.messages.takeLast(REACTION_HISTORY_LINES).joinToString("\n") { message ->
                    val text = message.text?.toString().orEmpty()
                    val sender = message.person?.name
                    if (messaging.isGroupConversation && !sender.isNullOrBlank()) "$sender: $text" else text
                },
            )
            addView(R.id.notification_quick_reactions_rows, rows)
        }
    val decorated =
        Notification.Builder
            .recoverBuilder(context, notification.clone())
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomBigContentView(view)
            .setActions(
                *notification.actions
                    .orEmpty()
                    .map { if (it === reply) customReply else it }
                    .toTypedArray(),
            ).build()
    val template = decorated.extras.getString(Notification.EXTRA_TEMPLATE)
    decorated.extras.putAll(notification.extras)
    decorated.extras.putString(Notification.EXTRA_TEMPLATE, template)
    return decorated
}

private const val MIN_CHIP_COLUMNS = 2
private const val CHIP_STRIDE_DP = 52
private const val REACTION_HORIZONTAL_INSET_DP = 115
private const val REACTION_HISTORY_LINES = 3
