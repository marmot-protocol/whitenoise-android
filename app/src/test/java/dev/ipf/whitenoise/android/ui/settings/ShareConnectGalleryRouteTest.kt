package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the production screen and saver through the Share menu's Save to Gallery entry; only native identity reads
 * and the gallery provider are substituted.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ShareConnectGalleryRouteTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = ProfileQrGalleryProvider(context)
    private val current = AtomicBoolean(true)
    private var screenVisible by mutableStateOf(true)
    private lateinit var appState: WhiteNoiseAppState

    /** Cancels the fixture's scopes and clears the registered gallery provider. */
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

    /** A successful publication through the menu entry presents the saved notice once the write completes. */
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

    /** A rejected gallery write presents the existing save error and no success notice. */
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

    /** Losing screen ownership before the insert leaves no notice behind. */
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

    /** Cancelled gallery work is not reported as a save failure. */
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

    /** Without a parsed profile link the entry is disabled, and activating it never reaches the gallery. */
    @Test
    fun productionScreenDisablesSaveWithoutAProfileLink() {
        mount(validLink = false)
        openShareMenu()
        composeRule.onNodeWithTag(SAVE_ENTRY).assertIsNotEnabled().performClick()
        assertEquals(0, provider.insertCount)
    }

    /** Closing the menu by choosing the entry must not cancel the save that the entry started. */
    @Test
    fun closingTheMenuWhileSavingDoesNotCancelTheSave() {
        val gate = holdInsert()
        mount()
        openShareMenu()
        composeRule.onNodeWithTag(SAVE_ENTRY).performClick()
        composeRule.onNodeWithText("Save to Gallery").assertDoesNotExist()
        composeRule.waitUntil(10_000) { provider.insertCount == 1 }
        composeRule.onNodeWithTag("share_connect.share").assertIsNotEnabled()
        composeRule.runOnIdle { assertNull(appState.transientNotice) }
        gate.countDown()
        waitForCompletion()
        composeRule.runOnIdle {
            assertEquals(AppText.Resource(R.string.media_saved), appState.transientNotice?.title)
            assertEquals(1, provider.insertCount)
        }
    }

    /** While the save runs the trigger cannot reopen the menu, and the finished save leaves the menu closed. */
    @Test
    fun menuCannotReopenWhileSavingAndStaysClosedAfterwards() {
        val gate = holdInsert()
        mount()
        openShareMenu()
        composeRule.onNodeWithTag(SAVE_ENTRY).performClick()
        composeRule.waitUntil(10_000) { provider.insertCount == 1 }
        composeRule.onNodeWithTag("share_connect.share").performClick()
        composeRule.onNodeWithText("Save to Gallery").assertDoesNotExist()
        gate.countDown()
        waitForCompletion()
        composeRule.onNodeWithText("Save to Gallery").assertDoesNotExist()
        composeRule.onNodeWithTag("share_connect.share").assertIsEnabled()
        assertEquals(1, provider.insertCount)
    }

    /** Two activations of one captured menu action, as in a same-frame double tap, save exactly once. */
    @Test
    fun rapidDoubleActivationSavesOnce() {
        mount()
        openShareMenu()
        composeRule.onNodeWithTag(SAVE_ENTRY).performSemanticsAction(SemanticsActions.OnClick) {
            it()
            it()
        }
        waitForCompletion()
        composeRule.runOnIdle {
            assertEquals(1, provider.insertCount)
            assertEquals(AppText.Resource(R.string.media_saved), appState.transientNotice?.title)
        }
    }

    /** Leaving the screen mid-save presents no completion feedback, and a write already begun may finish. */
    @Test
    fun leavingTheScreenWhileSavingPresentsNoFeedback() {
        val gate = holdInsert()
        mount()
        openShareMenu()
        composeRule.onNodeWithTag(SAVE_ENTRY).performClick()
        composeRule.waitUntil(10_000) { provider.insertCount == 1 }
        composeRule.runOnIdle { screenVisible = false }
        composeRule.onNodeWithTag("share_connect.share").assertDoesNotExist()
        gate.countDown()
        composeRule.waitUntil(10_000) { provider.completed != null }
        composeRule.runOnIdle {
            assertNull(appState.transientNotice)
            assertNull(appState.toast)
        }
    }

    /** Holds the gallery insert on a worker thread until the returned latch opens. */
    private fun holdInsert(): CountDownLatch {
        val gate = CountDownLatch(1)
        provider.beforeInsert = { gate.await(10, TimeUnit.SECONDS) }
        return gate
    }

    /** Opens the Share menu and activates its Save to Gallery entry, then waits for the export to finish. */
    private fun saveAndWait() {
        openShareMenu()
        composeRule.onNodeWithTag(SAVE_ENTRY).performClick()
        waitForCompletion()
    }

    /** Opens the Share menu from its top-bar trigger. */
    private fun openShareMenu() {
        composeRule.onNodeWithTag("share_connect.share").performClick()
    }

    /** Waits until the gallery saw an insert and the screen released its export guard. */
    private fun waitForCompletion() {
        composeRule.waitUntil(10_000) {
            provider.insertCount > 0 &&
                !composeRule
                    .onNodeWithTag("share_connect.share")
                    .fetchSemanticsNode()
                    .config
                    .contains(SemanticsProperties.Disabled)
        }
    }

    /** Mounts the production screen over a minimal native identity fixture. */
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
                if (screenVisible) ShareConnectScreen(appState, onBack = {}, isCurrent = current::get)
            }
        }
    }

    private companion object {
        const val SAVE_ENTRY = "share_connect.save_to_gallery"
    }
}
