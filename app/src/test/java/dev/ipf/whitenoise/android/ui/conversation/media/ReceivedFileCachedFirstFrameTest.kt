package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.state.AttachmentTransferCoordinator
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.mediaCacheKey
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceivedFileCachedFirstFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var cacheDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: AttachmentTransferCoordinator
    private val productionCacheDirs = mutableListOf<File>()
    private val keyProvider =
        DiskByteCacheKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("received-file-first-frame").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator = AttachmentTransferCoordinator(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        cacheDir.deleteRecursively()
        productionCacheDirs.forEach(File::deleteRecursively)
    }

    @Test
    fun coldEncryptedL2HitIsAvailableOnFirstCapturedFrameForEitherDownloadPolicy() {
        val writer = DiskByteCache(cacheDir, maxBytes = 1024, keyProvider = keyProvider)
        val keys = listOf("documents-off", "documents-on")
        keys.forEachIndexed { index, key -> writer.put(key, ByteArray(40) { (index + 3).toByte() }) }
        val cold = DiskByteCache(cacheDir, maxBytes = 1024, keyProvider = keyProvider)
        val remoteCalls = AtomicInteger(0)
        val firstFrames = mutableMapOf<String, MutableList<AttachmentTransferState>>()
        val l1 = mutableMapOf<String, ByteArray>()
        val showConversation = mutableStateOf(true)

        composeRule.setContent {
            if (showConversation.value) {
                Column {
                    CachedReceivedFile(
                        key = keys[0],
                        autoDownloadAllowed = false,
                        cold = cold,
                        l1 = l1,
                        firstFrames = firstFrames,
                        remoteCalls = remoteCalls,
                    )
                    CachedReceivedFile(
                        key = keys[1],
                        autoDownloadAllowed = true,
                        cold = cold,
                        l1 = l1,
                        firstFrames = firstFrames,
                        remoteCalls = remoteCalls,
                    )
                }
            }
        }

        awaitVisible(keys)
        composeRule.runOnIdle {
            keys.forEach { key ->
                assertEquals(AttachmentTransferState.Available, firstFrames.getValue(key).first())
                assertTrue("an L2 hit must hydrate L1", l1.getValue(key).isNotEmpty())
            }
            assertEquals(0, remoteCalls.get())
            showConversation.value = false
        }
        composeRule.runOnIdle { showConversation.value = true }
        awaitVisible(keys)
        composeRule.runOnIdle {
            keys.forEach { key ->
                assertTrue(firstFrames.getValue(key).all { it == AttachmentTransferState.Available })
            }
            assertEquals("reopening must not start remote work", 0, remoteCalls.get())
        }
    }

    @Test
    fun productionFileBubblePublishesColdL2HitBeforeItsFirstVisibleFrame() {
        val cases =
            listOf(
                productionCase("documents-off", autoDownloadAllowed = false),
                productionCase("documents-on", autoDownloadAllowed = true),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Column {
                    cases.forEach { case ->
                        MediaFileBubble(
                            messageIdHex = case.messageIdHex,
                            attachmentIndex = 0,
                            reference = case.reference,
                            controller = case.controller,
                            appState = case.appState,
                            senderKey = SENDER_ID,
                            senderDisplayName = "Sender",
                            mine = false,
                        )
                    }
                }
            }
        }

        cases.forEach { case ->
            assertTrue(
                "cold hydration must start for ${case.fileName}",
                case.hydrationEntered.await(5, TimeUnit.SECONDS),
            )
            assertEquals(AttachmentTransferState.Resolving, case.transferState.value)
            composeRule
                .onNodeWithTag(fileAttachmentCardTestTag(case.messageIdHex, 0), useUnmergedTree = true)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        }
        cases.forEach { it.releaseHydration.countDown() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            cases.all { it.transferState.value == AttachmentTransferState.Available }
        }

        composeRule.waitForIdle()
        cases.forEach { case ->
            composeRule
                .onNodeWithTag(fileAttachmentCardTestTag(case.messageIdHex, 0), useUnmergedTree = true)
                .assert(
                    SemanticsMatcher("is no longer hidden after definitive cache readiness") { node ->
                        !node.config.contains(SemanticsProperties.HideFromAccessibility)
                    },
                )
            composeRule.onNodeWithText(case.fileName).assertExists()
            assertTrue(
                "an authenticated L2 hit must hydrate production L1",
                case.controller.hasCachedAttachmentInMemory(case.messageIdHex, 0),
            )
            assertEquals("a cached first frame must not cross the MDK boundary", 0, case.marmotCalls.get())
            case.controller.releaseAttachmentTransferState(case.messageIdHex, 0)
        }
    }

    @Test
    fun initiallyResolvedCardStillReconcilesItsCacheState() {
        val resolveCalls = AtomicInteger(0)
        val state = mutableStateOf(AttachmentTransferState.Available)

        composeRule.setContent {
            val resolved =
                rememberAttachmentFirstFrameCacheResolution(
                    owner = coordinator,
                    key = "outgoing-file",
                    initiallyResolved = true,
                ) {
                    resolveCalls.incrementAndGet()
                    state.value = AttachmentTransferState.Remote
                }
            if (resolved) Text(state.value.name, Modifier.testTag("outgoing-file"))
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("outgoing-file").fetchSemanticsNodes().size == 1 &&
                state.value == AttachmentTransferState.Remote
        }
        composeRule.runOnIdle { assertEquals(1, resolveCalls.get()) }
    }

    @Composable
    private fun CachedReceivedFile(
        key: String,
        autoDownloadAllowed: Boolean,
        cold: DiskByteCache,
        l1: MutableMap<String, ByteArray>,
        firstFrames: MutableMap<String, MutableList<AttachmentTransferState>>,
        remoteCalls: AtomicInteger,
    ) {
        val initiallyAvailable = l1.containsKey(key)
        val stateFlow = remember(key) { coordinator.acquireState(key, initiallyAvailable) }
        DisposableEffect(key) {
            onDispose { coordinator.releaseState(key) }
        }
        val transferState by stateFlow.collectAsState()
        val resolved =
            rememberAttachmentFirstFrameCacheResolution(
                owner = coordinator,
                key = key,
                initiallyResolved = initiallyAvailable,
            ) {
                coordinator.refresh(key) {
                    val bytes = withContext(Dispatchers.IO) { cold.get(key) }
                    if (bytes != null) l1[key] = bytes
                    bytes != null
                }
            }
        if (resolved) {
            if (
                shouldStartAttachmentDownload(
                    transferState = transferState,
                    policyAllowsDownload = autoDownloadAllowed,
                    sourceEpoch = 1uL,
                    mine = false,
                )
            ) {
                SideEffect { remoteCalls.incrementAndGet() }
            }
            SideEffect { firstFrames.getOrPut(key, ::mutableListOf).add(transferState) }
            Text(transferState.name, Modifier.testTag(key))
        }
    }

    private fun awaitVisible(keys: List<String>) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            keys.all { key -> composeRule.onAllNodesWithTag(key).fetchSemanticsNodes().size == 1 }
        }
        keys.forEach { key -> composeRule.onAllNodesWithTag(key).assertCountEquals(1) }
    }

    private fun productionCase(
        suffix: String,
        autoDownloadAllowed: Boolean,
    ): ProductionCase {
        val directory = Files.createTempDirectory("received-file-production-$suffix").toFile()
        productionCacheDirs += directory
        val messageIdHex = (if (autoDownloadAllowed) "11" else "10") + "00".repeat(31)
        val fileName = "$suffix.pdf"
        val cacheKey = mediaCacheKey(ACCOUNT_REF, GROUP_ID, messageIdHex, 0)
        DiskByteCache(directory, maxBytes = 1024, keyProvider = keyProvider).put(cacheKey, ByteArray(40) { 7 })
        val hydrationEntered = CountDownLatch(1)
        val releaseHydration = CountDownLatch(1)
        val coldCache =
            DiskByteCache(
                BlockingListFilesDir(directory, hydrationEntered, releaseHydration),
                maxBytes = 1024,
                keyProvider = keyProvider,
            )
        val marmotCalls = AtomicInteger(0)
        val appState = appState(coldCache, marmotCalls)
        appState.setActiveNetworkForTest()
        appState.setMediaAutoDownload(
            MediaAutoDownloadType.Document,
            MediaAutoDownloadNetwork.WiFi,
            autoDownloadAllowed,
        )
        assertEquals(autoDownloadAllowed, appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Document))
        val controller = ConversationController(appState = appState, initialGroup = group())
        val transferState = controller.attachmentTransferState(messageIdHex, 0, initiallyAvailable = false)
        return ProductionCase(
            appState = appState,
            controller = controller,
            messageIdHex = messageIdHex,
            fileName = fileName,
            reference = fileReference(fileName),
            transferState = transferState,
            marmotCalls = marmotCalls,
            hydrationEntered = hydrationEntered,
            releaseHydration = releaseHydration,
        )
    }

    private fun appState(
        diskMediaCache: DiskByteCache,
        marmotCalls: AtomicInteger,
    ) = WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext(),
        draftStore = DraftStore(EmptyDraftPersistence()),
        accountIdHexResolver = { ACCOUNT_ID },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = ACCOUNT_REF,
                    accountIdHex = ACCOUNT_ID,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = ACCOUNT_REF,
        marmotAccessObserver = marmotCalls::incrementAndGet,
    ).also { state ->
        WhiteNoiseAppState::class.java
            .getDeclaredField("diskMediaCache")
            .apply { isAccessible = true }
            .set(state, diskMediaCache)
    }

    private fun WhiteNoiseAppState.setActiveNetworkForTest() {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("noteActiveNetworkSnapshot", Boolean::class.javaPrimitiveType, Set::class.java)
            .apply { isAccessible = true }
            .invoke(this, true, setOf(MediaAutoDownloadNetwork.WiFi))
    }

    private fun fileReference(fileName: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi("blossom-v1", "https://media.example/$fileName")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = "application/pdf",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Cached file group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints = listOf(AppBlobEndpointFfi("blossom-v1", "https://blossom.example")),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private data class ProductionCase(
        val appState: WhiteNoiseAppState,
        val controller: ConversationController,
        val messageIdHex: String,
        val fileName: String,
        val reference: MediaAttachmentReferenceFfi,
        val transferState: kotlinx.coroutines.flow.StateFlow<AttachmentTransferState>,
        val marmotCalls: AtomicInteger,
        val hydrationEntered: CountDownLatch,
        val releaseHydration: CountDownLatch,
    )

    private class BlockingListFilesDir(
        private val delegate: File,
        private val hydrationEntered: CountDownLatch,
        private val releaseHydration: CountDownLatch,
    ) : File(delegate.path) {
        override fun mkdirs(): Boolean = delegate.mkdirs()

        override fun listFiles(): Array<File>? {
            hydrationEntered.countDown()
            releaseHydration.await(5, TimeUnit.SECONDS)
            return delegate.listFiles()
        }
    }

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "cached-file-account"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
