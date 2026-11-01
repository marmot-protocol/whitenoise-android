@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.SettingsCallout
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsGroupPanel
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.net.URI
import java.util.Locale

/**
 * Group relays: the Nostr relays the native group record says carry this chat's messages.
 *
 * Renders `AppGroupRecordFfi.relays` as configuration only — MarmotKit exposes no per-relay live status, so no row
 * claims a connection. Editing the route belongs to MDK (mdk#1369), so admins see why it is not editable yet and
 * members see that admins control it. Blob/media servers are not relays and never appear here.
 */
@Composable
internal fun GroupRelaySettingsScreen(
    relays: List<String>,
    isDm: Boolean,
    isAdmin: Boolean,
    onBack: () -> Unit,
    feedback: @Composable () -> Unit = {},
) {
    val displayed = remember(relays) { groupRelayDisplayList(relays) }
    val clipboard = LocalClipboardManager.current
    // Transient copy confirmation, dropped whenever the authoritative list changes.
    var copiedUrl by remember(displayed) { mutableStateOf<String?>(null) }
    SettingsScaffold(title = stringResource(groupRelaysTitleRes(isDm)), onBack = onBack) {
        Column(Modifier.fillMaxSize().testTag(GROUP_RELAYS_SCREEN_TAG)) {
            feedback()
            SettingsList(modifier = Modifier.weight(1f)) {
                item { SettingsExplainer(stringResource(R.string.group_relays_intro)) }
                item { SettingsSection(stringResource(R.string.group_relays_configured)) }
                item {
                    GroupRelayRows(displayed, copiedUrl) { url ->
                        clipboard.setText(AnnotatedString(url))
                        copiedUrl = url
                    }
                }
                item { SettingsExplainer(stringResource(R.string.group_relays_configuration_only)) }
                item {
                    SettingsCallout(
                        text = stringResource(groupRelaysControlDetailRes(isAdmin)),
                        modifier = Modifier.testTag(if (isAdmin) GROUP_RELAYS_ADMIN_TAG else GROUP_RELAYS_MEMBER_TAG),
                        title = stringResource(R.string.group_relays_admin_control_title),
                        icon = R.drawable.ic_admin_panel_settings,
                    )
                }
            }
        }
    }
}

/** One segmented group: a row per configured relay, or a single empty-state row. */
@Composable
private fun GroupRelayRows(
    relays: List<String>,
    copiedUrl: String?,
    onCopy: (String) -> Unit,
) {
    SettingsGroup(modifier = Modifier.testTag(GROUP_RELAYS_LIST_TAG)) {
        if (relays.isEmpty()) {
            row("empty") { context -> GroupRelayEmptyRow(context) }
        } else {
            relays.forEachIndexed { index, url ->
                row(url) { context ->
                    GroupRelayRow(context, index + 1, url, copied = url == copiedUrl, onCopy = { onCopy(url) })
                }
            }
        }
    }
}

/** A configured relay: its ordinal label and the full URL, wrapped rather than truncated, with a copy action. */
@Composable
private fun GroupRelayRow(
    context: SettingsRowContext,
    number: Int,
    url: String,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    val copiedLabel = stringResource(R.string.copied)
    SettingsGroupPanel(context, Modifier.testTag("$GROUP_RELAYS_ROW_TAG_PREFIX$url")) {
        Row(
            Modifier.fillMaxWidth().padding(start = WhiteNoiseSpacing.FormField, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).padding(vertical = WhiteNoiseSpacing.Related + 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.relay_number, number),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(url, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            IconButton(
                onClick = onCopy,
                modifier =
                    Modifier
                        .testTag("$GROUP_RELAYS_COPY_TAG_PREFIX$url")
                        .semantics { if (copied) stateDescription = copiedLabel },
            ) {
                Icon(
                    painterResource(groupRelayCopyGlyph(copied)),
                    contentDescription = stringResource(R.string.group_relay_copy, number),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The empty or unavailable route: the record carries no relay for this chat. */
@Composable
private fun GroupRelayEmptyRow(context: SettingsRowContext) {
    SettingsGroupPanel(context, Modifier.testTag(GROUP_RELAYS_EMPTY_TAG)) {
        Text(
            stringResource(R.string.group_relays_empty),
            modifier =
                Modifier.padding(horizontal = WhiteNoiseSpacing.FormField, vertical = WhiteNoiseSpacing.Related + 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The destination title: a group's relays, or the chat's relays for a direct message. */
@StringRes
internal fun groupRelaysTitleRes(isDm: Boolean): Int = if (isDm) R.string.chat_relays else R.string.group_relays

/** Admins learn editing is not available yet; members learn admins control the route. */
@StringRes
internal fun groupRelaysControlDetailRes(isAdmin: Boolean): Int =
    when {
        isAdmin -> R.string.group_relays_admin_detail
        else -> R.string.group_relays_member_detail
    }

/** The copy action's glyph: a check while the last copied URL is still this row's. */
@DrawableRes
private fun groupRelayCopyGlyph(copied: Boolean): Int = if (copied) R.drawable.ic_check else R.drawable.ic_content_copy

/**
 * The record's relays in first-seen order, trimmed, without blanks or entries that differ only in letter case of the
 * scheme/host, a default port or a trailing slash; user info, path and query still distinguish entries.
 * Display-only: the original spelling of the first entry is kept and nothing is filtered by policy, so an address
 * the dialer refuses is still shown exactly as configured.
 */
internal fun groupRelayDisplayList(relays: List<String>): List<String> =
    relays
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(::groupRelayDisplayKey)

/** Comparison key for [groupRelayDisplayList]; unparsable addresses compare by their trimmed text. */
private fun groupRelayDisplayKey(url: String): String =
    runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching url
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching url
        val port =
            uri.port
                .takeUnless { it == -1 || it == defaultPort(scheme) }
                ?.let { ":$it" }
                .orEmpty()
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        // User info is kept, so an entry naming a different user is never hidden behind another spelling.
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        "$scheme://$userInfo$host$port$path$query"
    }.getOrDefault(url)

/** The WebSocket scheme's implicit port, or -1 when the scheme has none. */
private fun defaultPort(scheme: String): Int =
    when (scheme) {
        "wss" -> SECURE_WEBSOCKET_PORT
        "ws" -> PLAIN_WEBSOCKET_PORT
        else -> -1
    }

private const val SECURE_WEBSOCKET_PORT = 443
private const val PLAIN_WEBSOCKET_PORT = 80

internal const val GROUP_RELAYS_SCREEN_TAG = "group_relays.screen"
internal const val GROUP_RELAYS_LIST_TAG = "group_relays.list"
internal const val GROUP_RELAYS_EMPTY_TAG = "group_relays.empty"
internal const val GROUP_RELAYS_ADMIN_TAG = "group_relays.admin_note"
internal const val GROUP_RELAYS_MEMBER_TAG = "group_relays.member_note"
internal const val GROUP_RELAYS_ROW_TAG_PREFIX = "group_relays.row."
internal const val GROUP_RELAYS_COPY_TAG_PREFIX = "group_relays.copy."
