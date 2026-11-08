package dev.ipf.whitenoise.android.media

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ui.conversation.media.ANDROID_PACKAGE_MIME
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Observes and dismisses the system installer around one APK dispatch without ever confirming an installation. */
internal object ApkInstallerScreen {
    private const val INSTALLER_TIMEOUT_MILLIS = 10_000L
    private const val STAGING_TIMEOUT_MILLIS = 60_000L
    private const val PROGRESS_BAR_CLASS = "android.widget.ProgressBar"
    private const val POLL_MILLIS = 100L
    private const val UNEXPECTED_OBSERVE_MILLIS = 1_000L
    private const val DISMISS_ATTEMPTS = 3

    /** Records actual dispatch attempts without retaining a URI, account, filename or window text. */
    internal class RecordingOpenContext(
        base: Context,
    ) : ContextWrapper(base) {
        var attempts: Int = 0
            private set
        var action: String? = null
            private set
        var mediaType: String? = null
            private set

        override fun startActivity(intent: Intent) {
            attempts++
            action = intent.action
            mediaType = intent.type
            super.startActivity(intent)
        }
    }

    /** A missing accessibility root is unknown, not proof of a clean screen. */
    internal suspend fun requireCleanInstallerBoundary(
        automation: UiAutomation,
        installers: Set<String>,
        case: String,
        boundary: String,
    ) {
        val started = SystemClock.elapsedRealtime()
        var cleanSince: Long? = null
        var clean = false
        var packageName: String? = null
        var windowClass: String? = null
        while (SystemClock.elapsedRealtime() - started < INSTALLER_TIMEOUT_MILLIS) {
            val root = automation.rootInActiveWindow
            packageName = root?.packageName?.toString()
            windowClass = root?.className?.toString()
            val now = SystemClock.elapsedRealtime()
            if (packageName != null && packageName !in installers) {
                val since = cleanSince ?: now.also { cleanSince = it }
                if (now - since >= UNEXPECTED_OBSERVE_MILLIS) {
                    clean = true
                    break
                }
            } else {
                cleanSince = null
            }
            delay(POLL_MILLIS)
        }
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "apk-ui-boundary")
                .put("case", case)
                .put("boundary", boundary)
                .put("clean", clean)
                .put("foreground_package", packageName ?: JSONObject.NULL)
                .put("foreground_window_class", windowClass ?: JSONObject.NULL)
                .put("installer_packages", JSONArray(installers.sorted())),
        )
        check(clean) { "installer boundary did not become clean: $boundary" }
    }

    /**
     * Whether the installer reached the screen after one dispatch, for how long the screen was watched, whether the
     * installer had finished staging the package before Back was sent, whether its progress indicator was ever seen,
     * and how long the wait for it lasted. A package whose indicator was never seen was not proven to have finished
     * staging, because an installer that draws it under another class name looks the same as one that never staged.
     */
    internal class InstallerObservation(
        val shown: Boolean,
        val observedMillis: Long,
        val settled: Boolean,
        val progressSeen: Boolean,
        val stagingMillis: Long,
    )

    /** The outcome of waiting for staging to finish and whether the progress indicator was seen at any poll. */
    private class StagingWait(
        val settled: Boolean,
        val progressSeen: Boolean,
    )

    /**
     * Watches the screen after any dispatch result. When an installer is expected it waits for it, and otherwise it
     * watches for [UNEXPECTED_OBSERVE_MILLIS] so a launch behind a non-Opened status is still seen. An installer that
     * appeared is given time to finish staging the package before it is dismissed with Back without installing, since
     * Back sent while a large package is still being copied from the file provider does not cancel the staging and the
     * dialog outlives the probe's process. Whatever an Opened dispatch left on screen is dismissed as well.
     */
    internal suspend fun awaitInstallerAndDismiss(
        context: Context,
        expectInstaller: Boolean,
        opened: Boolean,
    ): InstallerObservation {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val installers = installerPackages(context)
        val started = SystemClock.elapsedRealtime()
        val shown =
            if (expectInstaller) {
                runCatching {
                    withTimeout(INSTALLER_TIMEOUT_MILLIS) { pollForeground(automation, installers) }
                }.getOrDefault(false)
            } else {
                watchForInstaller(automation, installers)
            }
        val observedMillis = SystemClock.elapsedRealtime() - started
        val stagingStarted = SystemClock.elapsedRealtime()
        val staging =
            if (shown) awaitStagingFinished(automation) else StagingWait(settled = false, progressSeen = false)
        val stagingMillis = if (shown) SystemClock.elapsedRealtime() - stagingStarted else 0L
        if (shown || opened) {
            repeat(DISMISS_ATTEMPTS) {
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(POLL_MILLIS)
            }
        }
        return InstallerObservation(shown, observedMillis, staging.settled, staging.progressSeen, stagingMillis)
    }

    /** Polls for the whole observation window and reports whether an installer package owned the screen at any poll. */
    private suspend fun watchForInstaller(
        automation: UiAutomation,
        installers: Set<String>,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + UNEXPECTED_OBSERVE_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (observeInstallerForeground(automation, installers)) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    /**
     * Polls until the installer window no longer shows the progress indicator it draws while staging a package, and
     * records whether the indicator was visible at any poll, so a wait that never saw it is told apart from one that
     * watched it disappear.
     */
    private suspend fun awaitStagingFinished(automation: UiAutomation): StagingWait {
        var progressSeen = false
        val settled =
            runCatching {
                withTimeout(STAGING_TIMEOUT_MILLIS) {
                    while (automation.rootInActiveWindow?.let(::showsProgress) == true) {
                        progressSeen = true
                        delay(POLL_MILLIS)
                    }
                    true
                }
            }.getOrDefault(false)
        return StagingWait(settled, progressSeen)
    }

    /** True when this window or any descendant is a progress bar. */
    private fun showsProgress(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString() == PROGRESS_BAR_CLASS ||
            (0 until node.childCount).any { index -> node.getChild(index)?.let(::showsProgress) == true }

    /** Resolves the package that handles APK installation on this device instead of assuming a vendor package. */
    internal fun installerPackages(context: Context): Set<String> {
        val intent =
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://fixture/apk"), ANDROID_PACKAGE_MIME)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /** Polls the active window until an installer package owns it. */
    private suspend fun pollForeground(
        automation: UiAutomation,
        installers: Set<String>,
    ): Boolean {
        while (!observeInstallerForeground(automation, installers)) delay(POLL_MILLIS)
        return true
    }

    /** Accessibility root class is recorded as a window class, not mislabelled as an activity component. */
    private fun observeInstallerForeground(
        automation: UiAutomation,
        installers: Set<String>,
    ): Boolean {
        val root = automation.rootInActiveWindow
        val packageName = root?.packageName?.toString()
        if (packageName !in installers) return false
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "apk-installer-foreground")
                .put("foreground_package", packageName)
                .put("foreground_window_class", root?.className?.toString() ?: JSONObject.NULL),
        )
        return true
    }
}
