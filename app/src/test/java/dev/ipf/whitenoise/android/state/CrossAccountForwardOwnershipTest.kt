package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaDownloadResultFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaUploadAttachmentResultFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation

/**
 * Production-path account ownership for cross-account forwarding: destination
 * publish work runs only under the explicitly selected destination account,
 * the live active account is never consulted, and removal or sign-out of an
 * owner stops the operation without any fallback account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class CrossAccountForwardOwnershipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class RecordedCall(
        val method: String,
        val accountRef: String,
    )

    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private val messageIdCounter = AtomicInteger(0)
    private var timelineGate: CountDownLatch? = null
    private var onTimelineEntered: (() -> Unit)? = null
    private var onUploadEntered: (() -> Unit)? = null
    private var downloadGate: CountDownLatch? = null
    private var onDownloadEntered: (() -> Unit)? = null

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "sendText" -> {
                    calls += RecordedCall("sendText", arguments!![0] as String)
                    SendSummaryFfi(
                        published = 1u,
                        messageIds = listOf("id-${messageIdCounter.incrementAndGet()}"),
                        acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                }
                "timelineMessages" -> {
                    calls += RecordedCall("timelineMessages", arguments!![0] as String)
                    onTimelineEntered?.invoke()
                    timelineGate?.await()
                    TimelinePageFfi(messages = emptyList(), hasMoreBefore = false, hasMoreAfter = false)
                }
                "downloadMedia" -> {
                    calls += RecordedCall("downloadMedia", arguments!![0] as String)
                    onDownloadEntered?.invoke()
                    downloadGate?.await()
                    MediaDownloadResultFfi(
                        plaintext = byteArrayOf(1, 2, 3, 4),
                        fileName = "photo.png",
                        mediaType = "image/png",
                        sizeBytes = 4uL,
                    )
                }
                "uploadMedia" -> {
                    calls += RecordedCall("uploadMedia", arguments!![0] as String)
                    onUploadEntered?.invoke()
                    MediaUploadResultFfi(
                        attachments =
                            listOf(
                                MediaUploadAttachmentResultFfi(
                                    reference = mediaReference("destination.png"),
                                    encryptedSizeBytes = 4uL,
                                ),
                            ),
                        sent = null,
                    )
                }
                "sendMediaAttachments" -> {
                    calls += RecordedCall("sendMediaAttachments", arguments!![0] as String)
                    SendSummaryFfi(
                        published = 1u,
                        messageIds = listOf("media-id-${messageIdCounter.incrementAndGet()}"),
                        acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                }
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "CrossAccountForwardMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        error("Unexpected Marmot call: ${method.name}")
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    /** Builds one signed-in signing-account summary. */
    private fun account(
        label: String,
        hexSeed: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = hexSeed.repeat(32),
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    /** Builds an app state wired to the scripted engine proxy. */
    private fun appState(
        accounts: List<AccountSummaryFfi>,
        activeAccountRef: String,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = activeAccountRef,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** Builds one text payload rooted in the source group. */
    private fun textPayload(text: String = "forwarded body") =
        ForwardMessagePayload.Text(
            sourceGroupIdHex = SOURCE_GROUP,
            sourceMessageIdHex = "01".repeat(32),
            text = text,
        )

    /** Pumps the main looper until the operation leaves its active phases. */
    private fun awaitTerminal(
        appState: WhiteNoiseAppState,
        timeoutMillis: Long = 20_000,
    ): ForwardOperationSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val snapshot = appState.activeForwardOperation.value
            if (snapshot != null && !snapshot.isActive) return snapshot
            Thread.sleep(5)
        }
        error("forward operation did not reach a terminal state")
    }

    /** Pumps the main looper until the proxy records the named call. */
    private fun awaitCall(
        method: String,
        timeoutMillis: Long = 20_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (calls.any { it.method == method }) return
            Thread.sleep(5)
        }
        error("never observed a $method call")
    }

    /** All publish traffic runs under the destination while a third account is active. */
    @Test
    fun destinationPublishesRunOnlyUnderTheSelectedAccountRegardlessOfTheActiveAccount() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa"), account(ACCOUNT_B, "bb"), account(ACCOUNT_C, "cc")),
                activeAccountRef = ACCOUNT_C,
            )

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE, TARGET_TWO),
                messages = listOf(textPayload(), textPayload("second body")),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            )

        assertTrue(started)
        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Completed, terminal.phase)
        assertEquals(2, terminal.completedTargets)
        assertEquals(ACCOUNT_B, appState.activeForwardDestinationAccountRef)
        val accountsUsed = calls.map(RecordedCall::accountRef).toSet()
        assertEquals(setOf(ACCOUNT_B), accountsUsed)
        assertEquals(4, calls.count { it.method == "sendText" })
    }

    /** Start returns false when either bound owner is signed out. */
    @Test
    fun startIsRejectedWhenEitherBoundOwnerIsNotASignedInSigningAccount() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa")),
                activeAccountRef = ACCOUNT_A,
            )

        assertFalse(
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(textPayload()),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            ),
        )
        assertFalse(
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(textPayload()),
                sourceAccountRef = ACCOUNT_B,
                destinationAccountRef = ACCOUNT_A,
            ),
        )
        assertTrue(calls.isEmpty())
    }

    /** Mid-flight destination sign-out fails closed with zero sends and no retry. */
    @Test
    fun destinationSignOutMidFlightStopsBeforePublishWithoutAFallbackAccount() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa"), account(ACCOUNT_B, "bb")),
                activeAccountRef = ACCOUNT_A,
            )
        val gate = CountDownLatch(1)
        timelineGate = gate
        onTimelineEntered = { setAccounts(appState, listOf(account(ACCOUNT_A, "aa"))) }

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(textPayload()),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            )
        assertTrue(started)
        awaitCall("timelineMessages")
        gate.countDown()

        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Failed, terminal.phase)
        assertEquals(ForwardFailureStage.SessionChanged, terminal.targets.single().failureStage)
        assertFalse(terminal.canRetry)
        assertTrue(calls.none { it.method == "sendText" })
    }

    /** Builds one complete media attachment reference owned by the source group. */
    private fun mediaReference(fileName: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://media.example/$fileName")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = "image/png",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 4uL,
            dim = null,
            thumbhash = null,
        )

    /** Builds one media payload whose attachment resolves through the source group. */
    private fun mediaPayload() =
        ForwardMessagePayload.Media(
            sourceGroupIdHex = SOURCE_GROUP,
            sourceMessageIdHex = "02".repeat(32),
            caption = "caption",
            attachments = listOf(ForwardAttachmentSource(0, mediaReference("source.png"))),
        )

    /** Media materializes only under the source account; upload and publish run only under the destination. */
    @Test
    fun mediaForwardMaterializesUnderTheSourceAndUploadsAndPublishesUnderTheDestination() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa"), account(ACCOUNT_B, "bb"), account(ACCOUNT_C, "cc")),
                activeAccountRef = ACCOUNT_C,
            )

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(mediaPayload()),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            )

        assertTrue(started)
        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Completed, terminal.phase)
        val byMethod = calls.groupBy(RecordedCall::method) { it.accountRef }
        assertEquals(listOf(ACCOUNT_A), byMethod.getValue("downloadMedia").distinct())
        assertEquals(listOf(ACCOUNT_B), byMethod.getValue("uploadMedia").distinct())
        assertEquals(listOf(ACCOUNT_B), byMethod.getValue("sendMediaAttachments").distinct())
        assertEquals(listOf(ACCOUNT_B), byMethod.getValue("timelineMessages").distinct())
        assertTrue(calls.none { it.accountRef == ACCOUNT_C })
        assertTrue(calls.none { it.method == "sendText" })
    }

    /**
     * The exact regression an unrelated active-account switch used to cause:
     * switching cancels and clears the shared in-flight download pool, which
     * previously killed an uncached media forward mid-materialization. The
     * isolated path must survive that invalidation and complete under the
     * explicitly bound owners.
     */
    @Test
    fun sharedDownloadPoolInvalidationDuringMaterializationDoesNotCancelTheForward() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa"), account(ACCOUNT_B, "bb"), account(ACCOUNT_C, "cc")),
                activeAccountRef = ACCOUNT_C,
            )
        val gate = CountDownLatch(1)
        downloadGate = gate
        onDownloadEntered = { cancelSharedDownloads(appState) }

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(mediaPayload()),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            )
        assertTrue(started)
        awaitCall("downloadMedia")
        gate.countDown()

        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Completed, terminal.phase)
        assertEquals(listOf(ACCOUNT_A), calls.filter { it.method == "downloadMedia" }.map { it.accountRef })
        assertEquals(listOf(ACCOUNT_B), calls.filter { it.method == "sendMediaAttachments" }.map { it.accountRef })
    }

    /** Replays the account-switch teardown against the shared download pool. */
    @Suppress("UNCHECKED_CAST")
    private fun cancelSharedDownloads(appState: WhiteNoiseAppState) {
        val map =
            WhiteNoiseAppState::class.java
                .getDeclaredField("inFlightDownloads")
                .apply { isAccessible = true }
                .get(appState) as MutableMap<String, kotlinx.coroutines.Deferred<ByteArray>>
        synchronized(map) {
            map.values.forEach { it.cancel() }
            map.clear()
        }
    }

    /** Destination sign-out between upload and publish fails closed with no media send under any account. */
    @Test
    fun destinationSignOutDuringMediaUploadStopsBeforePublishWithoutAFallbackAccount() {
        val appState =
            appState(
                accounts = listOf(account(ACCOUNT_A, "aa"), account(ACCOUNT_B, "bb")),
                activeAccountRef = ACCOUNT_A,
            )
        onUploadEntered = { setAccounts(appState, listOf(account(ACCOUNT_A, "aa"))) }

        val started =
            appState.startForwardMessages(
                targetGroupIds = listOf(TARGET_ONE),
                messages = listOf(mediaPayload()),
                sourceAccountRef = ACCOUNT_A,
                destinationAccountRef = ACCOUNT_B,
            )

        assertTrue(started)
        val terminal = awaitTerminal(appState)
        assertEquals(ForwardOperationPhase.Failed, terminal.phase)
        assertEquals(ForwardFailureStage.SessionChanged, terminal.targets.single().failureStage)
        assertEquals(listOf(ACCOUNT_A), calls.filter { it.method == "downloadMedia" }.map { it.accountRef })
        assertTrue(calls.none { it.method == "sendMediaAttachments" })
    }

    /** Replaces the live account list through the snapshot-state delegate. */
    @Suppress("UNCHECKED_CAST")
    private fun setAccounts(
        appState: WhiteNoiseAppState,
        accounts: List<AccountSummaryFfi>,
    ) {
        val delegate =
            WhiteNoiseAppState::class.java
                .getDeclaredField("accounts\u0024delegate")
                .apply { isAccessible = true }
                .get(appState) as androidx.compose.runtime.MutableState<List<AccountSummaryFfi>>
        delegate.value = accounts
    }

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val ACCOUNT_C = "account-c"
        val SOURCE_GROUP = "aa" + "00".repeat(31)
        val TARGET_ONE = "11".repeat(32)
        val TARGET_TWO = "22".repeat(32)
    }
}
