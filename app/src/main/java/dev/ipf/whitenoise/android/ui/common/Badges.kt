package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R

@Composable
internal fun UnreadCountBadge(
    unreadCount: ULong,
    modifier: Modifier = Modifier,
    actionColors: AccountActionColors? = null,
) {
    val accessibleCount = unreadCount.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    val description = pluralStringResource(R.plurals.unread_messages_count, accessibleCount, accessibleCount)
    // Default Badge is error-red, which reads as an alert not a count.
    Badge(
        modifier = modifier.semantics { contentDescription = description },
        containerColor = actionColors?.container ?: MaterialTheme.colorScheme.primary,
        contentColor = actionColors?.content ?: MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(if (unreadCount > 99uL) "99+" else unreadCount.toString())
    }
}
