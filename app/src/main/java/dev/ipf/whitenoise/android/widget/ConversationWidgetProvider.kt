package dev.ipf.whitenoise.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ConversationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        refresh(context)
    }

    override fun onDeleted(
        context: Context,
        ids: IntArray,
    ) {
        val store = ConversationWidgetConfiguration(context)
        ids.forEach(store::remove)
    }

    private fun refresh(context: Context) {
        val pending = goAsync()
        val application = context.applicationContext as WhiteNoiseApplication
        application.applicationScope.launch {
            try {
                withTimeoutOrNull(WIDGET_BROADCAST_TIMEOUT_MS) {
                    ConversationWidgets.refresh(context, application.initializedAppState())
                }
            } finally {
                pending?.finish()
            }
        }
    }
}

internal fun conversationWidgetViews(
    context: Context,
    id: Int,
    rows: List<ConversationWidgetRow>,
    choice: ConversationWidgetChoice?,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.conversation_widget)
    views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
    views.setOnClickPendingIntent(
        R.id.widget_title,
        PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )
    views.removeAllViews(R.id.widget_rows)
    val refresh =
        Intent(context, ConversationWidgetProvider::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
    views.setOnClickPendingIntent(
        R.id.widget_refresh,
        PendingIntent.getBroadcast(
            context,
            id,
            refresh,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )
    if (rows.isEmpty()) {
        val empty = RemoteViews(context.packageName, R.layout.conversation_widget_row)
        empty.setTextViewText(R.id.widget_row_title, context.getString(R.string.widget_unavailable))
        empty.setTextViewText(R.id.widget_row_subtitle, context.getString(R.string.widget_open_app))
        views.addView(R.id.widget_rows, empty)
    }
    rows.forEach { row ->
        views.addView(R.id.widget_rows, conversationWidgetRowViews(context, id, row, choice?.allAccounts == true))
    }
    return views
}

private fun conversationWidgetRowViews(
    context: Context,
    id: Int,
    row: ConversationWidgetRow,
    allAccounts: Boolean,
): RemoteViews {
    val item = RemoteViews(context.packageName, R.layout.conversation_widget_row)
    val title = row.title.ifBlank { context.getString(R.string.widget_conversation) }
    item.setTextViewText(
        R.id.widget_row_title,
        if (allAccounts) {
            "${row.account}: $title"
        } else {
            title
        },
    )
    item.setTextViewText(
        R.id.widget_row_subtitle,
        row.preview ?: context.getString(
            if (row.unread) R.string.widget_unread else R.string.widget_recent,
        ),
    )
    val intent =
        Intent(context, ConversationWidgetTapActivity::class.java)
            .setData(
                Uri
                    .Builder()
                    .scheme("whitenoise-widget")
                    .authority("open")
                    .appendPath("$id")
                    .appendPath(row.account)
                    .appendPath(row.group)
                    .build(),
            ).putExtra("account", row.account)
            .putExtra("group", row.group)
            .putExtra("widget", id)
    item.setOnClickPendingIntent(
        R.id.widget_row,
        PendingIntent.getActivity(
            context,
            intent.data.toString().hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )
    return item
}

private const val WIDGET_BROADCAST_TIMEOUT_MS = 8_000L
