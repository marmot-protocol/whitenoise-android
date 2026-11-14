package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountKeyPackageLocalStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DateTimeDisplay
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.currentDateTimeDisplay
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import java.time.format.FormatStyle
import java.util.Locale

/** A relay record with explicit provenance, full-value copy and a validated deletion target. */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun PublishedKeyPackage(
    kp: AccountKeyPackageFfi,
    actionsEnabled: Boolean,
    localState: AccountKeyPackageLocalStateFfi =
        if (kp.local) AccountKeyPackageLocalStateFfi.OTHER_OWNED else AccountKeyPackageLocalStateFfi.NOT_LOCAL,
    onDelete: () -> Unit,
) {
    val sources =
        keyPackageSourceLabels(
            kp,
            stringResource(R.string.local),
            stringResource(R.string.relay),
            stringResource(R.string.unknown),
        )
    val deleteLabel = stringResource(R.string.delete_key_package)
    SettingsGroup(modifier = Modifier.testTag("key_packages.published.${kp.eventIdHex}")) {
        row("id") { context ->
            KeyPackageCopyValue(
                context,
                stringResource(R.string.developer_package_id),
                kp.keyPackageId,
                R.drawable.ic_fingerprint,
                stringResource(R.string.explanation_relays_key_package_id),
            )
        }
        row("published") { context ->
            SettingsValue(
                context,
                stringResource(R.string.published),
                formatPublishedAt(
                    kp.publishedAt,
                    stringResource(R.string.unknown_publish_time),
                    LocalConfiguration.current.locales[0],
                    currentDateTimeDisplay(),
                ),
                leading = { SettingsLeadingIcon(R.drawable.ic_calendar_month) },
            )
        }
        row("source") { context ->
            SettingsValue(
                context,
                stringResource(R.string.developer_package_source),
                sources.joinToString(" · "),
                leading = { SettingsLeadingIcon(R.drawable.ic_input) },
            )
        }
        row("local-state") { context ->
            SettingsValue(
                context,
                stringResource(R.string.key_package_local_state),
                stringResource(localState.labelResource()),
                leading = { SettingsLeadingIcon(R.drawable.ic_lock) },
                explanation = stringResource(R.string.explanation_relays_key_package_local_state),
            )
        }
        row("relays") { context ->
            SettingsValue(
                context,
                stringResource(R.string.developer_seen_on),
                kp.sourceRelays.joinToString("\n").ifBlank { stringResource(R.string.unknown) },
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_cell_tower) },
                explanation = stringResource(R.string.explanation_relays_key_package_seen_on),
            )
        }
        row("event") { context ->
            KeyPackageCopyValue(
                context,
                stringResource(R.string.event),
                kp.eventIdHex,
                R.drawable.ic_data_object,
                stringResource(R.string.explanation_relays_key_package_event),
            )
        }
        row("ref") { context ->
            KeyPackageCopyValue(
                context,
                stringResource(R.string.ref),
                kp.keyPackageRefHex,
                R.drawable.ic_link,
                stringResource(R.string.explanation_relays_key_package_ref),
            )
        }
        row("size") { context ->
            SettingsValue(
                context,
                stringResource(R.string.size),
                stringResource(R.string.bytes_count, kp.keyPackageBytes.toLong()),
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_hard_drive) },
            )
        }
        if (kp.isRelayDeletionTarget()) {
            row("delete") { context ->
                SettingsGroupPanel(context) {
                    TextButton(
                        onClick = onDelete,
                        enabled = actionsEnabled,
                        modifier =
                            Modifier
                                .semantics { contentDescription = deleteLabel }
                                .explainOnLongPress(
                                    Explanation(
                                        stringResource(R.string.developer_delete_package),
                                        stringResource(R.string.explanation_relays_key_package_delete),
                                    ),
                                ),
                    ) {
                        Text(stringResource(R.string.developer_delete_package), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Local material stays explicitly separate from relay publication and offers no deletion action. */
@Composable
@Suppress("FunctionNaming")
internal fun RetainedKeyPackage(
    kp: AccountKeyPackageFfi,
    localState: AccountKeyPackageLocalStateFfi = AccountKeyPackageLocalStateFfi.RETAINED_PRIVATE_MATERIAL,
) {
    SettingsGroup(modifier = Modifier.testTag("key_packages.retained.${kp.keyPackageRefHex}")) {
        row("id") { context ->
            KeyPackageCopyValue(
                context,
                stringResource(R.string.developer_package_id),
                kp.keyPackageId,
                R.drawable.ic_fingerprint,
                stringResource(R.string.explanation_relays_key_package_id),
            )
        }
        row("source") { context ->
            SettingsValue(
                context,
                stringResource(R.string.developer_package_source),
                stringResource(R.string.local),
                leading = { SettingsLeadingIcon(R.drawable.ic_input) },
            )
        }
        row("local-state") { context ->
            SettingsValue(
                context,
                stringResource(R.string.key_package_local_state),
                stringResource(localState.labelResource()),
                leading = { SettingsLeadingIcon(R.drawable.ic_lock) },
                explanation = stringResource(R.string.explanation_relays_key_package_local_state),
            )
        }
        row("size") { context ->
            SettingsValue(
                context,
                stringResource(R.string.size),
                stringResource(R.string.bytes_count, kp.keyPackageBytes.toLong()),
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_hard_drive) },
            )
        }
    }
}

/** Localized diagnostic label for MarmotKit's durable ownership state. */
@StringRes
private fun AccountKeyPackageLocalStateFfi.labelResource(): Int =
    when (this) {
        AccountKeyPackageLocalStateFfi.NOT_LOCAL -> R.string.key_package_local_state_not_local
        AccountKeyPackageLocalStateFfi.CURRENT -> R.string.key_package_local_state_current
        AccountKeyPackageLocalStateFfi.PENDING_REPLACEMENT -> R.string.key_package_local_state_pending_replacement
        AccountKeyPackageLocalStateFfi.RETAINED_PRIVATE_MATERIAL -> R.string.key_package_local_state_retained
        AccountKeyPackageLocalStateFfi.OTHER_OWNED -> R.string.key_package_local_state_other_owned
    }

/** Short diagnostic identifiers retain their complete clipboard value when tapped. */
@Composable
@Suppress("FunctionNaming")
private fun KeyPackageCopyValue(
    context: SettingsRowContext,
    title: String,
    value: String,
    @DrawableRes icon: Int,
    explanation: String,
) {
    val clipboard = LocalClipboardManager.current
    val label = stringResource(R.string.copy)
    SettingsValue(
        context,
        title,
        IdentityFormatter.short(value),
        modifier =
            Modifier.clickable(onClickLabel = label, role = Role.Button) {
                clipboard.setText(AnnotatedString(value))
            },
        leading = { SettingsLeadingIcon(icon) },
        explanation = explanation,
    )
}

/** Resolves native local/relay provenance without inferring publication from retained material. */
private fun keyPackageSourceLabels(
    kp: AccountKeyPackageFfi,
    localLabel: String,
    relayLabel: String,
    unknownLabel: String,
): List<String> {
    val out = mutableListOf<String>()
    if (kp.local) out += localLabel
    if (kp.relay) out += relayLabel
    if (out.isEmpty()) out += unknownLabel
    return out
}

/**
 * Formats a valid relay timestamp in the user's date and time format, falling back for absent or
 * hostile out-of-range values (the formatter renders those empty instead of throwing).
 */
private fun formatPublishedAt(
    unixSeconds: ULong,
    unknown: String,
    locale: Locale,
    display: DateTimeDisplay,
): String =
    IdentityFormatter
        .dateTime(unixSeconds, locale, display = display, dateStyle = FormatStyle.SHORT)
        .ifEmpty { unknown }
