package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AccountSetupReadinessFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.IdentityCreationResultFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.AppProfileSignUp
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real AppState native adapters and app-root bootstrap re-entry preserve the accepted Sign Up owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class AppStateSignUpReentryTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Recreating the root during upload and after publication failure never hides Retry or creates another account. */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Single native callback/re-entry scenario.
    @Test
    fun resumedBootstrapKeepsAcceptedProfileAttemptVisibleUntilExplicitRetry() {
        val created = AccountSummaryFfi("created", "11".repeat(32), true, false, false, true)
        val creations = AtomicInteger()
        val uploads = AtomicInteger()
        val publications = AtomicInteger()
        val uploadEntered = CountDownLatch(1)
        val uploadRelease = CountDownLatch(1)
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "createIdentityWithInitialProfile" -> {
                        creations.incrementAndGet()
                        IdentityCreationResultFfi(
                            created,
                            args!![0] as UserProfileMetadataFfi,
                            AccountSetupReadinessFfi.LOCAL_READY,
                        )
                    }
                    "accountSetupReadiness" -> AccountSetupReadinessFfi.NETWORK_READY
                    "uploadProfileImage" -> {
                        assertEquals("created", args!![0])
                        uploads.incrementAndGet()
                        uploadEntered.countDown()
                        check(uploadRelease.await(10, TimeUnit.SECONDS))
                        "https://images.example/profile.jpg"
                    }
                    "accountRelayLists" ->
                        AccountRelayListsFfi(
                            complete = true,
                            missing = emptyList(),
                            defaultRelays = listOf("wss://relay.example"),
                            bootstrapRelays = emptyList(),
                            nip65 = RelayListFfi(kind = 10_002uL, relays = listOf("wss://relay.example")),
                            inbox = RelayListFfi(kind = 10_050uL, relays = listOf("wss://relay.example")),
                        )
                    "accountNip65Relays" -> listOf("wss://relay.example")
                    "publishUserProfile" -> {
                        assertEquals("created", args!![0])
                        if (publications.incrementAndGet() == 1) error("Synthetic publication failure")
                        Unit
                    }
                    "listAccounts" -> listOf(created)
                    "attachmentDownloadPolicy" -> AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                    "setAttachmentDownloadPolicy" -> Unit
                    "toString" -> "SignUpNativeTestDouble"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
                }
            } as MarmotInterface
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                emptyList(),
                "",
                initialMarmotRuntime = AppMarmotRuntime(context.cacheDir.resolve("signup-native-test").path, native),
            )
        // Match an already-completed process bootstrap; root recreation must call the real resume path.
        listOf("bootstrapCompleted", "networkNotificationRecoverySuppressed").forEach { field ->
            WhiteNoiseAppState::class.java
                .getDeclaredField(field)
                .apply { isAccessible = true }
                .setBoolean(app, true)
        }
        app.markDefaultNotificationsEnableAttempted()
        app.beginProfileSignUp()
        val controller = checkNotNull(app.pendingProfileSignUp)
        val shell = MainShellStateHolder(app, SavedStateHandle())
        val visible = mutableStateOf(true)
        try {
            composeRule.setContent { WhiteNoiseTheme { if (visible.value) WhiteNoiseApp(app, shell, 0, 0) } }
            composeRule.runOnIdle {
                controller.submit(
                    SignUpProfileDraft(
                        "Alice",
                        "Retained draft",
                        ImageUploadDraft(byteArrayOf(1, 2), "image/jpeg", null, null, null),
                    ),
                )
            }
            waitFor { uploadEntered.count == 0L }
            recreate(visible)
            waitFor { app.phase == AppPhase.Ready }
            assertSame(controller, app.profileSignUpForPresentation)
            composeRule.onNodeWithTag("onboarding.sign_up.name").assertExists()
            uploadRelease.countDown()
            waitFor { controller.stage == SignUpStage.PublishFailed }
            recreate(visible)
            assertSame(controller, app.profileSignUpForPresentation)
            composeRule.onNodeWithTag("onboarding.sign_up.action").assertExists()
            composeRule.runOnIdle { controller.retry() }
            waitFor { controller.stage == SignUpStage.Complete }
            assertNull(app.profileSignUpForPresentation)
            assertEquals(1, creations.get())
            assertEquals(1, uploads.get())
            assertEquals(2, publications.get())
        } finally {
            uploadRelease.countDown()
            composeRule.runOnIdle {
                visible.value = false
                shell.release()
            }
        }
    }

    /** A teardown during the actual adapter's setup wait stops the form without a profile save or second account. */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Single native callback scenario.
    @Test
    fun teardownDuringSetupWaitStopsTheFormWithoutPublishing() {
        val created = AccountSummaryFfi("created", "11".repeat(32), true, false, false, true)
        val creations = AtomicInteger()
        val readinessReads = AtomicInteger()
        val publications = AtomicInteger()
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "createIdentityWithInitialProfile" -> {
                        creations.incrementAndGet()
                        IdentityCreationResultFfi(
                            created,
                            args!![0] as UserProfileMetadataFfi,
                            AccountSetupReadinessFfi.LOCAL_READY,
                        )
                    }
                    "accountSetupReadiness" -> {
                        readinessReads.incrementAndGet()
                        AccountSetupReadinessFfi.PUBLISHING
                    }
                    "publishUserProfile" -> publications.incrementAndGet().let { Unit }
                    "accountRelayLists" ->
                        AccountRelayListsFfi(
                            complete = true,
                            missing = emptyList(),
                            defaultRelays = listOf("wss://relay.example"),
                            bootstrapRelays = emptyList(),
                            nip65 = RelayListFfi(kind = 10_002uL, relays = listOf("wss://relay.example")),
                            inbox = RelayListFfi(kind = 10_050uL, relays = listOf("wss://relay.example")),
                        )
                    "accountNip65Relays" -> listOf("wss://relay.example")
                    "listAccounts" -> listOf(created)
                    "attachmentDownloadPolicy" -> AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                    "setAttachmentDownloadPolicy" -> Unit
                    "toString" -> "SetupWaitTestDouble"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
                }
            } as MarmotInterface
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                emptyList(),
                "",
                initialMarmotRuntime = AppMarmotRuntime(context.cacheDir.resolve("signup-wait-test").path, native),
            )
        WhiteNoiseAppState::class.java
            .getDeclaredField("bootstrapCompleted")
            .apply { isAccessible = true }
            .setBoolean(app, true)
        app.beginProfileSignUp()
        val controller = checkNotNull(app.pendingProfileSignUp)
        controller.submit(SignUpProfileDraft("Alice", "About"))
        idleUntil { readinessReads.get() > 0 }
        app.wipeInProgress = true
        idleUntil { controller.stage == SignUpStage.OwnerChanged }
        assertEquals(1, creations.get())
        assertEquals(0, publications.get())
    }

    /** After the actual adapter's setup wait expires, Retry restarts MDK setup through start() for the same account. */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Single native callback scenario.
    @Test
    fun expiredSetupWaitRetryResumesThroughStart() {
        val created = AccountSummaryFfi("created", "11".repeat(32), true, false, false, true)
        val creations = AtomicInteger()
        val starts = AtomicInteger()
        val publications = AtomicInteger()
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "createIdentityWithInitialProfile" -> {
                        creations.incrementAndGet()
                        IdentityCreationResultFfi(
                            created,
                            args!![0] as UserProfileMetadataFfi,
                            AccountSetupReadinessFfi.LOCAL_READY,
                        )
                    }
                    // MDK's background attempts stopped; only start() resumes the journaled setup.
                    "accountSetupReadiness" ->
                        if (starts.get() == 0) {
                            AccountSetupReadinessFfi.PUBLISHING
                        } else {
                            AccountSetupReadinessFfi.NETWORK_READY
                        }
                    "start" -> starts.incrementAndGet().let { Unit }
                    "publishUserProfile" -> publications.incrementAndGet().let { Unit }
                    "accountRelayLists" ->
                        AccountRelayListsFfi(
                            complete = true,
                            missing = emptyList(),
                            defaultRelays = listOf("wss://relay.example"),
                            bootstrapRelays = emptyList(),
                            nip65 = RelayListFfi(kind = 10_002uL, relays = listOf("wss://relay.example")),
                            inbox = RelayListFfi(kind = 10_050uL, relays = listOf("wss://relay.example")),
                        )
                    "accountNip65Relays" -> listOf("wss://relay.example")
                    "listAccounts" -> listOf(created)
                    "attachmentDownloadPolicy" -> AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                    "setAttachmentDownloadPolicy" -> Unit
                    "toString" -> "SetupResumeTestDouble"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
                }
            } as MarmotInterface
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                emptyList(),
                "",
                initialMarmotRuntime = AppMarmotRuntime(context.cacheDir.resolve("signup-resume-test").path, native),
            )
        WhiteNoiseAppState::class.java
            .getDeclaredField("bootstrapCompleted")
            .apply { isAccessible = true }
            .setBoolean(app, true)
        val signUp =
            WhiteNoiseAppState::class.java
                .getDeclaredField("profileSignUp")
                .apply { isAccessible = true }
                .get(app) as AppProfileSignUp
        signUp.setupWaitTimeoutMillis = 1_000
        app.beginProfileSignUp()
        val controller = checkNotNull(app.pendingProfileSignUp)
        controller.submit(SignUpProfileDraft("Alice", "About"))
        idleUntil { controller.stage == SignUpStage.PublishFailed }
        assertEquals(0, starts.get())
        controller.retry()
        idleUntil { controller.stage == SignUpStage.Complete }
        assertEquals(1, starts.get())
        assertEquals(1, creations.get())
        assertEquals(1, publications.get())
    }

    /** The actual adapter's destructive-operation flag rejects creation before accessing native state. */
    @Test fun wipeInProgressBlocksActualCreationAdapter() {
        var nativeAccess = 0
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                emptyList(),
                "",
                marmotAccessObserver = { nativeAccess++ },
            )
        app.beginProfileSignUp()
        app.wipeInProgress = true
        checkNotNull(app.pendingProfileSignUp).submit(SignUpProfileDraft("Local", ""))
        assertEquals(SignUpStage.Editing, app.pendingProfileSignUp?.stage)
        assertNull(app.profileSignUpForPresentation)
        assertEquals(0, nativeAccess)
    }

    /** Remove/recreate the real root without replacing the app-owned state holder. */
    private fun recreate(visible: androidx.compose.runtime.MutableState<Boolean>) {
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
    }

    /** Advances the paused main looper, so the wait's poll delays fire, while IO-side adapter work completes. */
    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached" }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            Thread.sleep(10)
        }
    }

    /** Native adapters run on IO while their owner completions return to the real main looper. */
    private fun waitFor(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
}
