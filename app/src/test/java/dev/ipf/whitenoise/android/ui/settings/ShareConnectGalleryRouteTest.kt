package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.profile.ProfileQrGalleryProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** Drives the production screen and saver; only native identity reads and the gallery provider are substituted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ShareConnectGalleryRouteTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = ProfileQrGalleryProvider(context)
    private val current = AtomicBoolean(true)
    private lateinit var appState: WhiteNoiseAppState

    @After
    fun releaseFixture() {
        if (::appState.isInitialized) {
            current.set(false)
            appState.mutationsScope.cancel()
            val profileScope = WhiteNoiseAppState::class.java.getDeclaredField("profileScope")
            profileScope.isAccessible = true
            (profileScope.get(appState) as CoroutineScope).cancel()
        }
        ShadowContentResolver.reset()
    }

    @Test
    fun realGalleryPublicationPresentsSavedNotice() {
        mount()
        saveAndWait()
        composeRule.runOnIdle {
            assertEquals(AppText.Resource(R.string.media_saved), appState.transientNotice?.title)
            assertNull(appState.toast)
            assertEquals(0, provider.completed?.getAsInteger(android.provider.MediaStore.Images.Media.IS_PENDING))
        }
    }

    @Test
    fun realGalleryFailurePresentsTheExistingError() {
        provider.updateResult = 0
        mount()
        saveAndWait()
        composeRule.runOnIdle {
            assertEquals(AppText.Resource(R.string.media_save_failed), appState.toast?.title)
            assertNull(appState.transientNotice)
        }
    }

    @Test
    fun replacedOriginDoesNotReceiveCompletion() {
        provider.beforeInsert = { current.set(false) }
        mount()
        saveAndWait()
        composeRule.runOnIdle {
            assertNull(appState.transientNotice)
            assertNull(appState.toast)
        }
    }

    @Test
    fun cancelledGalleryWorkPresentsNoError() {
        provider.beforeInsert = { throw CancellationException("cancelled") }
        mount()
        saveAndWait()
        composeRule.runOnIdle {
            assertNull(appState.transientNotice)
            assertNull(appState.toast)
        }
    }

    @Test
    fun productionScreenDisablesSaveWithoutAProfileLink() {
        mount(validLink = false)
        composeRule.onNodeWithTag("profile.qr.save").assertIsNotEnabled()
        assertEquals(0, provider.insertCount)
    }

    private fun saveAndWait() {
        composeRule.onNodeWithTag("profile.qr.save").performClick()
        composeRule.waitUntil(10_000) {
            provider.insertCount > 0 &&
                !composeRule
                    .onNodeWithTag("profile.qr.save")
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Disabled)
        }
    }

    private fun mount(validLink: Boolean = true) {
        ShadowContentResolver.registerProviderInternal(android.provider.MediaStore.AUTHORITY, provider)
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "npub" -> if (validLink) "npub1" + "q".repeat(58) else "npub1invalid"
                    "recordHostPerformance" -> Unit
                    "userProfile" -> null
                    "toString" -> "GalleryRouteNativeFixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            } as MarmotInterface
        appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = listOf(AccountSummaryFfi("gallery-screen", "ab".repeat(32), true, false, false, true)),
                activeAccountRef = "gallery-screen",
                preferences = context.getSharedPreferences("gallery-route-${UUID.randomUUID()}", Context.MODE_PRIVATE),
                initialMarmotRuntime = AppMarmotRuntime(rootPath = "test", marmot = native),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ShareConnectScreen(appState, onBack = {}, isCurrent = current::get)
            }
        }
    }
}
