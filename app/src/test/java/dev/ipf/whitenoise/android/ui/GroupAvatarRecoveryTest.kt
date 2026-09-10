package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

/** Real group composition and account-switch teardown own pending requests and cached plaintext. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GroupAvatarRecoveryTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Starts each synthetic runtime with an empty process-only avatar cache. */
    @Before
    fun clearCache() = GroupAvatarImageLoader.clear()

    /** Retires detached requests without changing persisted application data. */
    @After
    fun retireRequests() = clearCache()

    /** A failed encrypted group avatar retries in its existing composition on validated recovery. */
    @Test
    fun groupAvatarRecoversWithoutNavigation() {
        val online = AtomicBoolean(false)
        val attempted = CompletableDeferred<Unit>()
        val state =
            appState { _ ->
                attempted.complete(Unit)
                check(online.get()) { "synthetic offline request" }
                imageBytes()
            }
        try {
            composeRule.setContent { GroupAvatar(state, group(), "Group Avatar", "group", 64.dp) }
            composeRule.waitUntil(5_000) { attempted.isCompleted }
            composeRule.onNodeWithText("GA").assertExists()
            composeRule.runOnIdle {
                online.set(true)
                AvatarLoadRecovery.onNetworkRestored()
            }
            composeRule.waitUntil(5_000) { GroupAvatarImageLoader.peek(cacheKey(ACCOUNT_A)) != null }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("GA").assertDoesNotExist()
        } finally {
            state.ttsController.detachEngine()
        }
    }

    /** The production switch clears all old pixels before a retained group slot consumes the next owner. */
    @Test
    fun accountSwitchRejectsLateOldFetchWithoutSyntheticDisposal() {
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val state =
            appState { account ->
                if (account == ACCOUNT_A) {
                    withContext(NonCancellable) {
                        oldStarted.complete(Unit)
                        releaseOld.await()
                    }
                } else {
                    newStarted.complete(Unit)
                    releaseNew.await()
                }
                imageBytes()
            }
        GroupAvatarImageLoader.putCached(cacheKey(ACCOUNT_B), ImageBitmap(2, 2))
        try {
            composeRule.setContent { GroupAvatar(state, group(), "Group Avatar", "group", 64.dp) }
            composeRule.waitUntil(5_000) { oldStarted.isCompleted }
            val oldFetchJob = currentDetachedFetch()
            activateAccountB(state, scope)
            composeRule.onNodeWithText("GA").assertExists()
            assertNull(GroupAvatarImageLoader.peek(cacheKey(ACCOUNT_B)))
            composeRule.runOnIdle { releaseOld.complete(Unit) }
            // Admission of the new owner proves the old detached fetch has released its permit.
            composeRule.waitUntil(5_000) { newStarted.isCompleted }
            composeRule.waitUntil(5_000) { oldFetchJob.isCompleted }
            assertNull(GroupAvatarImageLoader.peek(cacheKey(ACCOUNT_A)))
            composeRule.onNodeWithText("GA").assertExists()
            composeRule.runOnIdle { releaseNew.complete(Unit) }
            composeRule.waitUntil(5_000) { GroupAvatarImageLoader.peek(cacheKey(ACCOUNT_B)) != null }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("GA").assertDoesNotExist()
            assertNull(GroupAvatarImageLoader.peek(cacheKey(ACCOUNT_A)))
        } finally {
            releaseOld.complete(Unit)
            releaseNew.complete(Unit)
            scope.cancel()
            state.ttsController.detachEngine()
        }
    }

    /** Runs the production activation boundary and exposes early failures instead of timing out on them. */
    private fun activateAccountB(
        state: WhiteNoiseAppState,
        scope: CoroutineScope,
    ) {
        lateinit var accountSwitch: Deferred<Boolean>
        composeRule.runOnIdle {
            accountSwitch =
                scope.async {
                    state.setActiveAccount(
                        ACCOUNT_B,
                        preloadPolicy = AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST,
                        awaitPostActivationWork = { awaitCancellation() },
                    )
                }
        }
        composeRule.waitUntil(5_000) {
            // AppState resumes its IO work on Android's looper, not Compose's frame clock.
            ShadowLooper.idleMainLooper()
            if (accountSwitch.isCompleted) {
                check(runBlocking { accountSwitch.await() }) { "Account switch declined activation" }
            }
            state.activeAccountRef == ACCOUNT_B
        }
    }

    /** Injects only native avatar bytes and signed-in onboarding reads; account switching stays real. */
    private fun appState(download: suspend (String) -> ByteArray): WhiteNoiseAppState {
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name.substringBefore('-')) {
                    "downloadGroupBlossomImage" -> {
                        val operation: suspend () -> ByteArray = { download(arguments!![0] as String) }
                        @Suppress("UNCHECKED_CAST")
                        operation.startCoroutine(arguments!!.last() as Continuation<ByteArray>)
                        COROUTINE_SUSPENDED
                    }
                    "onboardingRecoveryRequired" -> false
                    "onboardingSnapshot" -> null
                    "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                    "toString" -> "AvatarTestRuntime"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected native operation: ${method.name}")
                }
            } as MarmotInterface
        val context = RuntimeEnvironment.getApplication().applicationContext
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { it },
            accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
            activeAccountRef = ACCOUNT_A,
            initialMarmotRuntime = AppMarmotRuntime("avatar-test", marmot),
        )
    }

    /** Two signed-in local labels exercise the real account-switch boundary without onboarding. */
    private fun account(label: String) = AccountSummaryFfi(label, label, true, false, false, true)

    /** Uses the same encrypted metadata for both owners, so only account isolation separates their bytes. */
    private fun group() =
        AppGroupRecordFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            groupIdHex = "group",
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            profilePresent = false,
            endpoint = "endpoint",
            name = "Group Avatar",
            description = "",
            admins = emptyList(),
            relays = emptyList(),
            nostrGroupIdHex = "nostr-group",
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = "avatar-hash",
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = emptyList(),
                    defaultBlobEndpoints = emptyList(),
                ),
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
            disappearingMessageSecs = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbanded = false,
            disbandRequest = null,
        )

    /** Uses the production encrypted-content identity. */
    private fun cacheKey(account: String) = encryptedGroupAvatarCacheKey(account, group())!!

    /** Tiny valid encoded pixels keep these concurrency tests independent of network and large allocations. */
    private fun imageBytes(): ByteArray = Base64.getDecoder().decode(PNG)

    /** Captures the existing worker job so assertions wait for publication, not merely permit release. */
    private fun currentDetachedFetch(): Job {
        val field = GroupAvatarImageLoader::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = field.get(GroupAvatarImageLoader) as CoroutineScope
        return checkNotNull(scope.coroutineContext[Job]).children.single()
    }

    private companion object {
        const val ACCOUNT_A = "avatar-owner-a"
        const val ACCOUNT_B = "avatar-owner-b"
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
