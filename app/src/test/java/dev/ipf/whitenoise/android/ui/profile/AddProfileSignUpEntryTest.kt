package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshotHandoff
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ValidatedInternetNetworkTracker
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.onboarding.SignUpController
import dev.ipf.whitenoise.android.ui.onboarding.SignUpProfileDraft
import dev.ipf.whitenoise.android.ui.onboarding.SignUpStage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

/** Add Profile reaches the actual app-root form and the same native adapter used by first-account Sign Up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AddProfileSignUpEntryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alice = AccountSummaryFfi("alice", "11".repeat(32), true, false, false, true)
    private val carol = AccountSummaryFfi("carol", "33".repeat(32), true, false, false, true)
    private val bob = AccountSummaryFfi("bob", "22".repeat(32), true, false, false, true)
    private val creates = AtomicInteger()
    private val publishes = AtomicInteger()
    private val createRelease = CountDownLatch(1)
    private var holdCreate = false
    private var failCreate = false
    private var failPublishOnce = false
    private var published: UserProfileMetadataFfi? = null
    private var publishedAccount: String? = null
    private val visible = mutableStateOf(true)
    private val entryVisible = mutableStateOf(true)
    private var shell: MainShellStateHolder? = null

    /** Opening and editing the shared form preserves Alice; Back discards without any native operation. */
    @Test fun existingAccountEntryFormAndBackCreatesNothingAndRevokesOldSubmit() {
        val app = app()
        show(app)
        val controller = openForm(app)
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Bob local draft")
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        assertNull(app.pendingProfileSignUp)
        assertEquals("alice", app.activeAccountRef)
        composeRule.runOnIdle { controller.submit(SignUpProfileDraft("Late", "")) }
        assertFalse(controller.canPresent())
        assertEquals(0, creates.get())
        assertEquals(0, publishes.get())
        assertEquals(listOf(alice, carol), app.accounts)
    }

    /** A real form double-submit creates once; publication retry retains Bob and Alice without another creation. */
    @Test fun acceptedExistingAccountFormRetriesOnlyFailedPublication() {
        failPublishOnce = true
        val app = app()
        show(app)
        val controller = openForm(app)
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Bob")
        composeRule.onNodeWithTag("onboarding.sign_up.about").performTextInput("Local about")
        val click =
            composeRule
                .onNodeWithTag("onboarding.sign_up.action")
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        composeRule.runOnIdle {
            allowValidatedInternet(app)
            click()
            click()
        }
        waitFor { controller.stage == SignUpStage.PublishFailed }
        assertSame(controller, app.profileSignUpForPresentation)
        assertEquals("bob", app.activeAccountRef)
        assertEquals(setOf("alice", "carol", "bob"), app.accounts.map { it.label }.toSet())
        assertEquals(1, creates.get())
        composeRule.runOnIdle { allowValidatedInternet(app) }
        val retry = composeRule.onNodeWithTag("onboarding.sign_up.action")
        val retryBounds = retry.getUnclippedBoundsInRoot()
        val feedbackBounds =
            composeRule
                .onNodeWithText(
                    context.getString(R.string.toast_couldnt_publish_profile),
                    substring = true,
                    useUnmergedTree = true,
                ).getUnclippedBoundsInRoot()
        assertTrue(
            "Publication feedback $feedbackBounds must clear Retry $retryBounds",
            feedbackBounds.bottom <= retryBounds.top,
        )
        // Keep the actual pointer click: invoking SemanticsActions.OnClick would conceal an overlapping snackbar.
        retry.performClick()
        waitFor { controller.stage == SignUpStage.Complete }
        assertEquals(1, creates.get())
        assertEquals(2, publishes.get())
        assertEquals("bob", publishedAccount)
        assertEquals("Bob", published?.name)
        assertEquals("Local about", published?.about)
        assertEquals(AppPhase.Ready, app.phase)
        assertNull(app.profileSignUpForPresentation)
    }

    /** A callback captured for Alice cannot adopt a different live account before its first native entry. */
    @Test fun accountChangeBeforeSubmitRevokesTheOriginalController() {
        val app = app()
        app.beginProfileSignUp()
        val controller = checkNotNull(app.pendingProfileSignUp)
        changeActiveAccount(app, "carol")
        controller.submit(SignUpProfileDraft("Stale", ""))
        controller.stageDraft(SignUpProfileDraft("Stale", ""))
        assertNull(app.profileSignUpForPresentation)
        assertNull(controller.draft)
        assertEquals(0, creates.get())
        assertEquals(0, publishes.get())
        assertEquals("carol", app.activeAccountRef)
    }

    /** A late native creation receipt remains retained but cannot activate Bob over the newly selected account. */
    @Test fun accountChangeWhileCreatingRetainsReceiptWithoutActivationOrPublication() {
        holdCreate = true
        val app = app()
        app.beginProfileSignUp()
        val controller = checkNotNull(app.pendingProfileSignUp)
        controller.submit(SignUpProfileDraft("Bob", ""))
        waitFor { creates.get() == 1 }
        changeActiveAccount(app, "carol")
        createRelease.countDown()
        waitFor { controller.stage == SignUpStage.OwnerChanged }
        assertEquals(bob, controller.acceptedIdentity)
        assertEquals("carol", app.activeAccountRef)
        assertEquals(0, publishes.get())
        assertFalse(controller.canPresent())
        controller.retry()
        assertEquals(1, creates.get())
    }

    /** Retry checks the accepted account again and cannot publish after another account becomes active. */
    @Test fun accountChangeAfterAcceptanceRevokesFailedStageRetry() {
        failPublishOnce = true
        val app = app()
        app.beginProfileSignUp()
        val controller = checkNotNull(app.pendingProfileSignUp)
        controller.submit(SignUpProfileDraft("Bob", ""))
        waitFor { controller.stage == SignUpStage.PublishFailed }
        changeActiveAccount(app, "alice")
        controller.retry()
        assertEquals(SignUpStage.OwnerChanged, controller.stage)
        assertEquals("alice", app.activeAccountRef)
        assertEquals(1, creates.get())
        assertEquals(1, publishes.get())
        assertEquals(bob, controller.acceptedIdentity)
    }

    /** A failed unaccepted creation may retry explicitly, while a rapid duplicate never queues another create. */
    @Test fun createFailureRetryStaysOnTheOriginalExistingAccountUntilAcceptance() {
        failCreate = true
        val app = app()
        app.beginProfileSignUp()
        val controller = checkNotNull(app.pendingProfileSignUp)
        controller.submit(SignUpProfileDraft("Bob", ""))
        waitFor { controller.stage == SignUpStage.CreateFailed }
        assertEquals("alice", app.activeAccountRef)
        assertNull(controller.acceptedIdentity)
        failCreate = false
        controller.submit(SignUpProfileDraft("Bob", ""))
        controller.submit(SignUpProfileDraft("Wrong duplicate", ""))
        waitFor { controller.stage == SignUpStage.Complete }
        assertEquals(2, creates.get())
        assertEquals(1, publishes.get())
        assertEquals("Bob", published?.name)
        assertEquals("bob", app.activeAccountRef)
    }

    /** A foreign pending creation cannot be replaced, and refusing it must not silently dismiss this entry. */
    @Test fun staleBusyAttemptKeepsTheNewAddProfileEntryOpen() {
        holdCreate = true
        val app = app()
        app.beginProfileSignUp()
        val pending = checkNotNull(app.pendingProfileSignUp)
        pending.submit(SignUpProfileDraft("Bob", ""))
        waitFor { creates.get() == 1 }
        changeActiveAccount(app, "carol")
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible.value && entryVisible.value) AddIdentitySheet(app) { entryVisible.value = false }
            }
        }
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").performClick()
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").assertExists()
        assertTrue(entryVisible.value)
        assertSame(pending, app.pendingProfileSignUp)
        assertNull(app.profileSignUpForPresentation)
        assertEquals(1, creates.get())
        createRelease.countDown()
        waitFor { pending.stage == SignUpStage.OwnerChanged }
    }

    /** Complete pending bounded IO before removing the real app root at test teardown. */
    @After fun close() {
        createRelease.countDown()
        composeRule.runOnIdle {
            visible.value = false
            shell?.release()
        }
    }

    /** Uses the actual caller dialog and production root route, with a retained Chats handoff for Alice. */
    private fun show(app: WhiteNoiseAppState) {
        listOf("bootstrapCompleted", "networkNotificationRecoverySuppressed").forEach { name ->
            WhiteNoiseAppState::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .setBoolean(app, true)
        }
        app.markDefaultNotificationsEnableAttempted()
        val handoff = field(app, "accountSwitchHandoff") as AccountSwitchLocalSnapshotHandoff
        handoff.publish(
            handoff.beginRequest("alice"),
            AccountSwitchLocalSnapshot(
                "alice",
                alice.accountIdHex,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        )
        shell = MainShellStateHolder(app, SavedStateHandle())
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible.value) {
                    WhiteNoiseApp(app, checkNotNull(shell), 0, 0)
                    if (entryVisible.value) AddIdentitySheet(app) { entryVisible.value = false }
                }
            }
        }
    }

    /** The actual Add Profile callback closes the entry synchronously and opens the process-owned form. */
    private fun openForm(app: WhiteNoiseAppState): SignUpController {
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").performClick()
        composeRule.onNodeWithTag("onboarding.sign_up.name").assertExists()
        assertFalse(entryVisible.value)
        assertEquals(0, creates.get())
        assertEquals("alice", app.activeAccountRef)
        return checkNotNull(app.profileSignUpForPresentation)
    }

    /** Sets the same native connectivity snapshot consumed by the real form's internet gate. */
    private fun allowValidatedInternet(app: WhiteNoiseAppState) {
        WhiteNoiseAppState::class.java
            .getDeclaredField("hasActiveNetworkSnapshot")
            .apply { isAccessible = true }
            .setBoolean(app, true)
        (field(app, "validatedInternetNetworks") as ValidatedInternetNetworkTracker)
            .update(1L, true)
        assertTrue(app.hasValidatedInternet())
    }

    /** Injects a live account-owner change on the same holder; native account-switch behavior is tested separately. */
    private fun changeActiveAccount(
        app: WhiteNoiseAppState,
        value: String,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(app, value)
    }

    /** Reads only retained owners needed to establish an already-bootstrapped root fixture. */
    private fun field(
        app: WhiteNoiseAppState,
        name: String,
    ): Any? =
        WhiteNoiseAppState::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(app)

    /** Native operations execute on IO; their real adapter continuations return to the main looper. */
    private fun waitFor(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    /** Native fixture returns receipts without mutating app state or manufacturing route completion. */
    @Suppress("CyclomaticComplexMethod") // Explicit native dispatch keeps unsupported calls fail-closed.
    private fun app(): WhiteNoiseAppState {
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "createIdentity" -> {
                        creates.incrementAndGet()
                        if (holdCreate) check(createRelease.await(10, TimeUnit.SECONDS))
                        if (failCreate) error("Controlled unaccepted create failure")
                        bob
                    }
                    "accountRelayLists" ->
                        AccountRelayListsFfi(
                            complete = true,
                            missing = emptyList(),
                            defaultRelays = listOf("wss://relay.example"),
                            bootstrapRelays = emptyList(),
                            nip65 = RelayListFfi(10_002uL, listOf("wss://relay.example")),
                            inbox = RelayListFfi(10_050uL, listOf("wss://relay.example")),
                        )
                    "accountNip65Relays" -> listOf("wss://relay.example")
                    "publishUserProfile" -> {
                        publishedAccount = args!![0] as String
                        published = args[1] as UserProfileMetadataFfi
                        if (publishes.incrementAndGet() == 1 && failPublishOnce) error("Controlled publish failure")
                        Unit
                    }
                    "listAccounts" ->
                        if (creates.get() > 0 && !failCreate) listOf(alice, carol, bob) else listOf(alice, carol)
                    "toString" -> "ExistingAccountSignUpNativeFixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
                }
            } as MarmotInterface
        return WhiteNoiseAppState(
            context,
            DraftStore.forContext(context),
            { null },
            listOf(alice, carol),
            "alice",
            initialMarmotRuntime = AppMarmotRuntime(context.cacheDir.resolve("add-profile-signup").path, native),
        )
    }
}
