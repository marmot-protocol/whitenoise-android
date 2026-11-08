package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.KeepMediaPeriod
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StorageAction
import dev.ipf.whitenoise.android.state.StorageUsage
import dev.ipf.whitenoise.android.ui.settings.RemoveAttachmentsDialog
import dev.ipf.whitenoise.android.ui.settings.RemoveOlderDialogs
import dev.ipf.whitenoise.android.ui.settings.StorageDialogs
import dev.ipf.whitenoise.android.ui.settings.StorageOverviewDialogs

/**
 * Manage storage confirmations rendered by their production composables. Each confirmed action reaches only the
 * fixture callback, so no attachment, cache or Keep media setting is touched.
 */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroStoragePresentation(fixture: MaestroPresentationFixture) {
    when (fixture.scenario) {
        "storage-keep-shorter-confirm" -> {
            // Forever → 30 days shortens the window, so the picker must ask before applying it.
            val dialogs = rememberFixtureStorageDialogs(keepPicker = true)
            StorageOverviewDialogs(
                state = ManageStorageUiState(usage = fixtureStorageUsage(), keepMedia = KeepMediaPeriod.Forever),
                dialogs = dialogs,
                onAction = { action ->
                    check(action == StorageAction.SetKeepMedia(KeepMediaPeriod.ThirtyDays))
                    fixture.finish("keep-media")
                },
            )
        }
        "storage-remove-older-confirm" -> {
            val dialogs = rememberFixtureStorageDialogs(olderPicker = true)
            RemoveOlderDialogs(dialogs = dialogs, groupIdHex = null) { action ->
                check(action is StorageAction.RemoveOlder && action.groupIdHex == null)
                fixture.finish("remove-older")
            }
        }
        "storage-remove-attachments-cancel" ->
            RemoveAttachmentsDialog(
                count = 3,
                bytes = 14_000_000,
                onDismiss = { fixture.finish("dismiss") },
                onConfirm = { error("Cancel fixture must not remove attachments") },
            )
        else -> error("Unknown storage presentation")
    }
}

/** Dialog state opened at a chosen picker, as the overview's rows would open it. */
@Composable
private fun rememberFixtureStorageDialogs(
    keepPicker: Boolean = false,
    olderPicker: Boolean = false,
): StorageDialogs =
    remember {
        StorageDialogs(
            keepPicker = mutableStateOf(keepPicker),
            pendingKeep = mutableStateOf(null),
            olderPicker = mutableStateOf(olderPicker),
            olderCutoff = mutableStateOf(null),
            clearCache = mutableStateOf(false),
        )
    }

/** Fixed, non-zero usage figures, so the overview dialogs render with a clearable cache. */
private fun fixtureStorageUsage(): StorageUsage =
    StorageUsage(
        attachmentBytes = 120_000_000,
        attachmentCount = 42,
        chatDataBytes = 18_000_000,
        unusedDatabaseBytes = 0,
        pictureCacheBytes = 6_000_000,
        temporaryBytes = 2_000_000,
    )
