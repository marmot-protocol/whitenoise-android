package dev.ipf.whitenoise.android.ui.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/** Tests actual form navigation and explicit submission, not source-string wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SignUpProfileDraftSubmissionTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Welcome opens the real form; edits and Back leave no native runtime access or account receipt. */
    @Test fun welcomeToFormAndBackCreatesNothing() {
        var nativeAccess = 0
        val app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
                marmotAccessObserver = { nativeAccess++ },
            )
        composeRule.setContent { WhiteNoiseTheme { OnboardingScreen(app, hasActiveNetwork = { true }) } }
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").performClick()
        composeRule.runOnIdle { assertNotNull(app.pendingProfileSignUp) }
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Local name")
        composeRule.onNodeWithTag("onboarding.sign_up.about").performTextInput("Local description")
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").assertExists()
        composeRule.runOnIdle {
            assertNull(app.pendingProfileSignUp)
            assertEquals("", app.activeAccountRef)
            assertEquals(0, nativeAccess)
        }
    }

    /** The real Welcome/form wiring reaches native creation once after an offline retry. */
    @Test fun welcomeSignupRetriesMissingRouteThroughActualCreationAdapter() {
        assertWelcomeSignupReachesCreationAdapter(hasDefaultRoute = false)
    }

    /** An initially active unvalidated route reaches the same production adapter without a retry. */
    @Test fun welcomeSignupWithUnvalidatedRouteReachesActualCreationAdapter() {
        assertWelcomeSignupReachesCreationAdapter(hasDefaultRoute = true)
    }

    @Test fun welcomeSignupWithUnvalidatedNonVpnRouteReachesActualCreationAdapter() {
        assertWelcomeSignupReachesCreationAdapter(hasDefaultRoute = true, isVpn = false)
    }

    /** Drives Welcome → Sign Up through the production adapter to one native creation carrying the typed name. */
    private fun assertWelcomeSignupReachesCreationAdapter(
        hasDefaultRoute: Boolean,
        isVpn: Boolean = true,
    ) {
        var availability = unvalidatedSignUpAvailability(context, hasDefaultRoute, isVpn)
        val creations =
            java.util.concurrent.atomic
                .AtomicInteger()
        val native =
            java.lang.reflect.Proxy
                .newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { _, method, args ->
                    check(method.name == "createIdentityWithInitialProfile") {
                        "Unexpected native call: ${method.name}"
                    }
                    // The typed name is the account's first public profile, not a later replacement.
                    assertEquals("Retained name", (args[0] as UserProfileMetadataFfi).name)
                    creations.incrementAndGet()
                    error("Synthetic creation failure")
                } as MarmotInterface
        val app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
                initialMarmotRuntime = AppMarmotRuntime(context.cacheDir.resolve("signup-route-test").path, native),
            )
        composeRule.setContent {
            WhiteNoiseTheme { OnboardingScreen(app, hasActiveNetwork = { availability.hasActiveNetwork() }) }
        }
        composeRule.onNodeWithTag("onboarding.welcome.sign_up").performClick()
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Retained name")
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        if (!hasDefaultRoute) {
            composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertExists()
            composeRule.runOnIdle { assertEquals(0, creations.get()) }
            composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
            composeRule.runOnIdle {
                assertEquals(0, creations.get())
                availability = unvalidatedSignUpAvailability(context, hasDefaultRoute = true)
            }
            composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        }
        composeRule.waitUntil(5_000) { creations.get() == 1 }
        // The process-owned adapter resumes on Main after native IO, outside the Compose test clock.
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            app.pendingProfileSignUp?.stage == SignUpStage.CreateFailed
        }
        composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("onboarding.sign_up.name").assertTextContains("Retained name")
        composeRule.runOnIdle { assertEquals(1, creations.get()) }
    }

    /** Native field text at the explicit tap is published to the one returned account. */
    @Test fun activeRouteWithoutOsValidationSubmitsCurrentFieldsExactlyOnce() {
        val availability = unvalidatedSignUpAvailability(context, hasDefaultRoute = true)
        var owner = SignUpOwner(1, null)
        var creations = 0
        val publications = mutableListOf<UserProfileMetadataFfi>()
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SignUpController(
                        scope,
                        { owner },
                        { true },
                        create = { _, _ ->
                            creations++
                            AccountSummaryFfi("new", "11".repeat(32), true, false, false, true)
                        },
                        hasPendingIdentityReceipt = { false },
                        qualify = {},
                        accept = { account, old ->
                            owner = old.copy(accountRef = account.label)
                            true
                        },
                        upload = { _, _ -> error("No photo selected") },
                        publish = { account, value ->
                            assertEquals("new", account)
                            publications += value
                            publications.size > 1
                        },
                        finish = { _, _ -> true },
                    )
                }
            WhiteNoiseTheme { SignUpScreen(controller, availability::hasActiveNetwork, { "Quiet Otter" }, {}) }
        }
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Alice")
        composeRule.onNodeWithTag("onboarding.sign_up.about").performTextInput("Current text")
        composeRule.onNodeWithContentDescription("Suggest name").assertExists()
        composeRule.onNodeWithTag("onboarding.sign_up.suggest_name").performClick()
        composeRule.runOnIdle { assertEquals(0, creations) }
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        composeRule.runOnIdle {
            assertEquals(1, creations)
            assertEquals("Quiet Otter", publications.single().displayName)
            assertEquals("Current text", publications.single().about)
        }
        // Retry the failed publication through the same production predicate; reuse the accepted identity.
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        composeRule.runOnIdle {
            assertEquals(1, creations)
            assertEquals(2, publications.size)
            assertEquals(publications.first(), publications.last())
        }
    }

    /** A blank name publishes, shows, and on Retry reuses one generated pseudonym instead of a nameless profile. */
    @Test fun blankNameSubmitPublishesPseudonym() {
        var owner = SignUpOwner(1, null)
        val publications = mutableListOf<UserProfileMetadataFfi>()
        val pseudonyms = ArrayDeque(listOf("Quiet Otter", "Brave Fox"))
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SignUpController(
                        scope,
                        { owner },
                        { true },
                        create = { _, _ -> AccountSummaryFfi("new", "11".repeat(32), true, false, false, true) },
                        hasPendingIdentityReceipt = { false },
                        qualify = {},
                        accept = { account, old ->
                            owner = old.copy(accountRef = account.label)
                            true
                        },
                        upload = { _, _ -> error("No photo selected") },
                        publish = { _, value ->
                            publications += value
                            publications.size > 1
                        },
                        finish = { _, _ -> true },
                    )
                }
            WhiteNoiseTheme { SignUpScreen(controller, { true }, { pseudonyms.removeFirst() }, {}) }
        }
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("   ")
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("Quiet Otter", "Quiet Otter"), publications.map { it.name })
            assertEquals(listOf("Quiet Otter", "Quiet Otter"), publications.map { it.displayName })
        }
        composeRule.onNodeWithTag("onboarding.sign_up.name").assertTextContains("Quiet Otter")
    }

    /** The new form remains editable offline; neither its primary action nor Retry creates an identity. */
    @Test fun missingActiveRouteRetainsDraftAndStartsNoNativeOperation() {
        val availability = unvalidatedSignUpAvailability(context, hasDefaultRoute = false)
        var creations = 0
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val controller =
                remember {
                    SignUpController(
                        scope,
                        { SignUpOwner(1, null) },
                        { true },
                        create = { _, _ ->
                            creations++
                            error("Offline must not create")
                        },
                        hasPendingIdentityReceipt = { false },
                        qualify = {},
                        accept = { _, _ -> false },
                        upload = { _, _ -> error("Offline must not upload") },
                        publish = { _, _ -> false },
                        finish = { _, _ -> false },
                    )
                }
            WhiteNoiseTheme { SignUpScreen(controller, availability::hasActiveNetwork, { "Quiet Otter" }, {}) }
        }
        composeRule.onNodeWithTag("onboarding.sign_up.name").performTextInput("Saved locally")
        composeRule.onNodeWithTag("onboarding.sign_up.action").performClick()
        composeRule.onNodeWithTag(ONBOARDING_OFFLINE_NOTICE_TAG).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.runOnIdle { assertEquals(0, creations) }
        composeRule.onNodeWithTag("onboarding.sign_up.name").assertTextContains("Saved locally")
    }
}

/** Seed the production listener from an unvalidated VPN or non-VPN default route. */
internal fun unvalidatedSignUpAvailability(
    context: Context,
    hasDefaultRoute: Boolean,
    isVpn: Boolean = true,
): WhiteNoiseAppState {
    val app =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "",
        )
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    shadowOf(connectivity).setActiveNetworkInfo(
        if (hasDefaultRoute) {
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true,
            )
        } else {
            null
        },
    )
    connectivity.activeNetwork?.let { network ->
        val capabilities =
            ShadowNetworkCapabilities.newInstance().apply {
                shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadowOf(this).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (isVpn) {
                    shadowOf(this).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } else {
                    shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                }
            }
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
    }
    WhiteNoiseAppState::class.java
        .getDeclaredMethod("registerActiveNetworkListener")
        .apply { isAccessible = true }
        .invoke(app)
    assertFalse(app.hasValidatedInternet())
    assertEquals(hasDefaultRoute, app.hasActiveNetwork())
    assertEquals(hasDefaultRoute, app.connectivitySignals.value.hasAppNetwork)
    assertFalse(app.connectivitySignals.value.hasValidatedInternet)
    return app
}
