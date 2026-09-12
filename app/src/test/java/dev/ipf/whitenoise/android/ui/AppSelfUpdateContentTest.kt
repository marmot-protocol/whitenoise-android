package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppSelfUpdateState
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import dev.ipf.whitenoise.android.updates.ZapstoreApkAsset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** UI boundaries use synthetic state; no release lookup, file write, package permission or installer is invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class AppSelfUpdateContentTest {
    @get:Rule val composeRule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val state = mutableStateOf<AppSelfUpdateState>(AppSelfUpdateState.Idle)
    private val calls = mutableListOf<String>()

    /** A store-managed distribution hides every self-update phase, even if stale state is supplied. */
    @Test fun storeManagedNeverShowsAnOffStoreDialog() {
        render(enabled = false)
        allPhases().forEach {
            composeRule.runOnIdle { state.value = it }
            composeRule.onNodeWithTag(APP_UPDATE_DIALOG_TAG).assertDoesNotExist()
        }
        assertEquals(emptyList<String>(), calls)
    }

    /** Idle owns no modal surface or external action. */
    @Test fun idleRendersNothing() {
        render()
        composeRule.onNodeWithTag(APP_UPDATE_DIALOG_TAG).assertDoesNotExist()
    }

    /** Confirmation reports the actual offered version and cannot invoke installation. */
    @Test fun confirmationOnlyStartsTheRequestedDownload() {
        state.value = AppSelfUpdateState.Confirming(updateTestAsset())
        render()
        composeRule.onNodeWithText("2099.1.2", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("appUpdate.install").assertDoesNotExist()
        composeRule.onNodeWithTag("appUpdate.remindLater").assertDoesNotExist()
        composeRule.onNodeWithTag("appUpdate.download").performClick()
        assertEquals(listOf("download"), calls)
    }

    /** Resolution announces its real operation and retains explicit cancellation. */
    @Test fun resolvingIsIndeterminateAndCancelable() {
        state.value = AppSelfUpdateState.Resolving
        render()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeRule.onNodeWithTag("appUpdate.cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    /** Native byte progress stays bounded; absent totals do not become a fictional percentage. */
    @Test fun downloadProgressClampsAndUnknownTotalStaysIndeterminate() {
        state.value = AppSelfUpdateState.Downloading(updateTestAsset(), 25, 100)
        render()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f))).assertExists()
        composeRule.runOnIdle { state.value = AppSelfUpdateState.Downloading(updateTestAsset(), 120, 100) }
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(1f, 0f..1f))).assertExists()
        composeRule.runOnIdle { state.value = AppSelfUpdateState.Downloading(updateTestAsset(), 25, null) }
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeRule.onNodeWithTag("appUpdate.install").assertDoesNotExist()
    }

    /** Reaching the byte total does not display Ready or Install before the trusted flow changes phase. */
    @Test fun completeDownloadDoesNotInferVerification() {
        state.value = AppSelfUpdateState.Downloading(updateTestAsset(), 100, 100)
        render()
        composeRule.onNodeWithText(app.getString(R.string.app_self_update_downloading)).assertIsDisplayed()
        composeRule.onNodeWithTag("appUpdate.install").assertDoesNotExist()
    }

    /** Real verification is cancelable and never exposes Install or permission before completion. */
    @Test fun verifyingIsIndeterminateAndOnlyCancelable() {
        state.value = AppSelfUpdateState.Verifying(updateTestAsset())
        render()
        composeRule.onNodeWithText(app.getString(R.string.app_self_update_verifying)).assertIsDisplayed()
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeRule.onNodeWithTag("appUpdate.install").assertDoesNotExist()
        composeRule.onNodeWithTag("appUpdate.settings").assertDoesNotExist()
        composeRule.onNodeWithTag("appUpdate.download").assertDoesNotExist()
        composeRule.onNodeWithTag("appUpdate.cancel").performClick()
        assertEquals(listOf("cancel"), calls)
    }

    /** Permission review opens only the existing settings action and offers no installation shortcut. */
    @Test fun permissionRequiredOnlyOpensSettings() {
        state.value = AppSelfUpdateState.PermissionRequired(updateTestAsset(), updateTestFile())
        render()
        composeRule.onNodeWithTag("appUpdate.install").assertDoesNotExist()
        composeRule.onNodeWithTag("appUpdate.settings").performClick()
        assertEquals(listOf("settings"), calls)
    }

    /** Install delegates to the platform owner; pressing it does not invent installed-version success. */
    @Test fun verifiedInstallIsOnlyAHandoffRequest() {
        state.value = AppSelfUpdateState.Verified(updateTestAsset(), updateTestFile())
        render()
        composeRule.onNodeWithTag("appUpdate.install").performClick()
        composeRule.onNodeWithText(app.getString(R.string.app_self_update_ready_title)).assertIsDisplayed()
        assertEquals(listOf("install"), calls)
        assertEquals(AppSelfUpdateState.Verified(updateTestAsset(), updateTestFile()), state.value)
    }

    /** A retryable failure displays the typed production error and exposes its retry callback. */
    @Test fun retryableHashFailureCannotInstall() {
        state.value = AppSelfUpdateState.Error(R.string.app_self_update_hash_mismatch, true)
        render()
        composeRule.onNodeWithText(app.getString(R.string.app_self_update_hash_mismatch)).assertIsDisplayed()
        composeRule.onNodeWithTag("appUpdate.install").assertDoesNotExist()
        composeRule.onNodeWithTag("appUpdate.retry").performClick()
        assertEquals(listOf("retry"), calls)
    }

    /** Nonretryable failures only close, retaining the existing owner's retryability policy. */
    @Test fun nonretryableErrorOnlyCloses() {
        state.value = AppSelfUpdateState.Error(R.string.app_self_update_install_failed, false)
        render()
        composeRule.onNodeWithTag("appUpdate.retry").assertDoesNotExist()
        composeRule.onNodeWithText(app.getString(R.string.close)).performClick()
        assertEquals(listOf("cancel"), calls)
    }

    /** Every active phase uses the same cancel callback, including verified files and permission review. */
    @Test fun cancelRemainsReachableAcrossAllRealPhases() {
        render()
        allPhases().filter { it != AppSelfUpdateState.Idle }.forEachIndexed { index, phase ->
            composeRule.runOnIdle { state.value = phase }
            composeRule.onNodeWithTag("appUpdate.cancel").performClick()
            assertEquals(List(index + 1) { "cancel" }, calls)
        }
    }

    /** The moved ordinary-update action preserves both backend reminder dismissal and flow cancellation. */
    @Test fun remindLaterUsesTheGuardedExistingActions() {
        state.value = AppSelfUpdateState.Confirming(updateTestAsset())
        render(remindLater = true, onRemind = {
            remindLaterAppUpdate(
                "2099.1.2",
                state.value,
                updateTestInfo(),
                true,
                onDismissLatest = { calls += "dismiss-version" },
                onCancel = { calls += "cancel" },
            )
        })
        composeRule.onNodeWithTag("appUpdate.remindLater").performClick()
        assertEquals(listOf("dismiss-version", "cancel"), calls)
    }

    /** Offline resolution and its error do not make the preserved reminder action depend on downloading a release. */
    @Test fun remindLaterRemainsAvailableBeforeAndAfterFailedResolution() {
        state.value = AppSelfUpdateState.Resolving
        render(remindLater = true, onRemind = {
            remindLaterAppUpdate(
                "2099.1.2",
                state.value,
                updateTestInfo(),
                true,
                onDismissLatest = { calls += "dismiss-version" },
                onCancel = { calls += "cancel" },
            )
        })
        composeRule.onNodeWithTag("appUpdate.remindLater").performClick()
        composeRule.runOnIdle { state.value = AppSelfUpdateState.Error(R.string.app_self_update_resolve_failed, true) }
        composeRule.onNodeWithTag("appUpdate.remindLater").performClick()
        assertEquals(listOf("dismiss-version", "cancel", "dismiss-version", "cancel"), calls)
    }

    /** A stale confirmation cannot dismiss a newer/important release or a flow already past confirmation. */
    @Test fun staleRemindLaterCannotDismissAnotherVersionOrPhase() {
        val cases =
            listOf(
                AppSelfUpdateState.Confirming(updateTestAsset()) to updateTestInfo().copy(latestVersion = "2099.1.3"),
                AppSelfUpdateState.Confirming(updateTestAsset()) to updateTestInfo().copy(releasesBehind = 3),
                AppSelfUpdateState.Downloading(updateTestAsset(), 1, 100) to updateTestInfo(),
            )
        cases.forEach { (current, info) ->
            remindLaterAppUpdate(
                "2099.1.2",
                current,
                info,
                true,
                onDismissLatest = { calls += "dismiss-version" },
                onCancel = { calls += "cancel" },
            )
        }
        remindLaterAppUpdate(
            "2099.1.2",
            AppSelfUpdateState.Confirming(updateTestAsset()),
            updateTestInfo(),
            false,
            onDismissLatest = { calls += "dismiss-version" },
            onCancel = { calls += "cancel" },
        )
        assertEquals(emptyList<String>(), calls)
    }

    /** Injects current owner state and independently observable actions into the real dialog content. */
    private fun render(
        enabled: Boolean = true,
        remindLater: Boolean = false,
        onRemind: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                AppSelfUpdateContent(
                    state.value,
                    enabled,
                    onCancel = { calls += "cancel" },
                    onDownload = { calls += "download" },
                    onInstall = { calls += "install" },
                    onOpenSettings = { calls += "settings" },
                    onRetry = { calls += "retry" },
                    canRemindLater = remindLater,
                    onRemindLater = onRemind,
                )
            }
        }
    }

    /** Enumerates the production sealed states, including both retry policies. */
    private fun allPhases(): List<AppSelfUpdateState> =
        listOf(
            AppSelfUpdateState.Idle,
            AppSelfUpdateState.Resolving,
            AppSelfUpdateState.Confirming(updateTestAsset()),
            AppSelfUpdateState.Downloading(updateTestAsset(), 25, 100),
            AppSelfUpdateState.Verifying(updateTestAsset()),
            AppSelfUpdateState.Verified(updateTestAsset(), updateTestFile()),
            AppSelfUpdateState.PermissionRequired(updateTestAsset(), updateTestFile()),
            AppSelfUpdateState.Error(R.string.app_self_update_hash_mismatch, true),
            AppSelfUpdateState.Error(R.string.app_self_update_install_failed, false),
        )
}

/** Metadata only: this synthetic asset is never resolved, fetched or verified. */
internal fun updateTestAsset() =
    ZapstoreApkAsset(
        "test-event",
        "dev.ipf.whitenoise.android",
        "2099.1.2",
        "0".repeat(64),
        "https://updates.invalid/test.apk",
        100L,
        setOf("android-arm64-v8a"),
    )

/** A path object for typed UI state; the fixture never creates or reads this file. */
internal fun updateTestFile() = File("/tmp/whitenoise-update-ui-fixture.apk")

/** Real CalVer-compatible facts permit testing ordinary, dismissed and important availability. */
internal fun updateTestInfo() = AppUpdateInfo("2099.1.1", "2099.1.2", 1L, null, 1)
