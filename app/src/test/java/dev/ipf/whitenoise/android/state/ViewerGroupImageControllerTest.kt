package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ViewerGroupImageControllerTest {
    /** A matching cleanup ticket cannot overrule changed/removed authoritative pixels on viewer retry. */
    @Test
    fun partialCleanupRetryReconcilesCurrentNativeImageAndRefreshesAuthoritativeState() =
        runTest {
            for (replacement in listOf(byteArrayOf(9), null, PICTURE)) {
                val native = NativeImageFixture()
                val state =
                    WhiteNoiseAppState(
                        context = ApplicationProvider.getApplicationContext(),
                        draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                        accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
                        accounts =
                            listOf(
                                AccountSummaryFfi(
                                    label = ACCOUNT,
                                    accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                                    localSigning = true,
                                    externalSigning = false,
                                    signedOut = false,
                                    running = true,
                                ),
                            ),
                        activeAccountRef = ACCOUNT,
                        initialMarmotRuntime = AppMarmotRuntime("test", native.proxy()),
                        marmotIoDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val controller = ConversationController(state, native.group, conversationTimelineMemberSnapshot())
                val draft = ImageUploadDraft(PICTURE, "image/jpeg", null, "20x20", null)
                assertFalse(controller.updateGroupImage(ScopedGroupImageMutation(draft) { true }.forViewer(false)))
                assertEquals(1, native.uploads)
                native.failCleanup = false
                native.current = replacement
                native.group = native.group.copy(imageHashHex = replacement?.let { "native-current" })
                assertTrue(controller.updateGroupImage(ScopedGroupImageMutation(draft) { true }.forViewer(true)))
                assertEquals(if (replacement?.contentEquals(PICTURE) == true) 1 else 2, native.uploads)
                assertEquals(2, native.cleanups)
                assertTrue(requireNotNull(native.current).contentEquals(PICTURE))
                assertEquals(native.group, controller.group)
                assertEquals(null, controller.group.avatarUrl)
                assertEquals(
                    2,
                    controller.group.imageHashHex
                        ?.length
                        ?.let { if (it > 0) 2 else 0 },
                )
            }
        }

    /** Holds authoritative native state and injects only the legacy-cleanup failure. */
    private class NativeImageFixture {
        var group = conversationTimelineTestGroup().copy(avatarUrl = "https://example.test/legacy.jpg")
        var current: ByteArray? = null
        var failCleanup = true
        var uploads = 0
        var cleanups = 0

        /** The adopted binding supplies group details and decrypted image bytes; no Android protocol cache is faked. */
        fun proxy(): MarmotInterface =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name.substringBefore('-')) {
                    "toString" -> "viewer-image-test-native"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "groupDetails" ->
                        GroupDetailsFfi(
                            mlsState =
                                AppGroupMlsStateFfi(
                                    group.groupIdHex,
                                    AppProtocolProfileFfi.LEGACY,
                                    GroupLifecycleStateFfi.STABLE,
                                    0uL,
                                    1u,
                                    false,
                                    emptyList(),
                                    false,
                                    false,
                                    emptyList(),
                                    null,
                                ),
                            group = group,
                            members = conversationTimelineGroupRoster().members,
                        )
                    "downloadGroupBlossomImage" -> requireNotNull(current)
                    "updateGroupImage" -> {
                        uploads++
                        current = arguments?.get(2) as ByteArray
                        group = group.copy(imageHashHex = "encrypted-picture")
                        null
                    }
                    "updateGroupAvatarUrl" -> {
                        cleanups++
                        if (failCleanup) error("Controlled cleanup failure")
                        group = group.copy(avatarUrl = null)
                        null
                    }
                    else -> error("Unexpected image native method: ${method.name}")
                }
            } as MarmotInterface
    }

    private companion object {
        val PICTURE = byteArrayOf(1, 2, 3)
        const val ACCOUNT = ConversationTimelineTestIds.ACCOUNT_REF
    }
}
