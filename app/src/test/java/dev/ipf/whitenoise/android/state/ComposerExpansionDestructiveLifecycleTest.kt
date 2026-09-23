package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.audio.ConversationDictationSendRequest
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/** Verifies retained composer geometry follows real leave and local-delete commit boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@Suppress("LargeClass") // The lifecycle tests share one native proxy and seeded-controller fixture.
class ComposerExpansionDestructiveLifecycleTest {
    @Test
    fun acceptedDictationSendClearsOnlyItsOriginDraftAndGeometry() =
        runBlocking {
            val fixture = fixture()
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            val request = dictationRequest(fixture.appState, "typed")
            retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)
            val other = retainExpansion(fixture.appState, OTHER_GROUP)

            assertTrue(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, fixture.calls.send.get())
            assertTrue(fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID).isNullOrEmpty())
            assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                other,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, OTHER_GROUP),
            )
        }

    @Test
    fun detachedNonReplyDictationSendUsesTheProcessTransport() =
        runBlocking {
            val fixture = fixture(attachConversationController = false)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            var pendingCallbacks = 0
            val request =
                dictationRequest(fixture.appState, "typed").copy(
                    onPendingShown = { pendingCallbacks += 1 },
                )
            retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)

            assertTrue(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, fixture.calls.send.get())
            assertEquals(1, pendingCallbacks)
            assertTrue(fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID).isNullOrEmpty())
            assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun detachedNonReplyEmptySummaryRetainsDraftAndGeometry() =
        runBlocking {
            val fixture = fixture(sendResult = ::emptySendSummary, attachConversationController = false)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            var pendingCallbacks = 0
            val request =
                dictationRequest(fixture.appState, "typed").copy(
                    onPendingShown = { pendingCallbacks += 1 },
                )
            val retained = retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, fixture.calls.send.get())
            assertEquals(0, pendingCallbacks)
            assertEquals("typed", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                retained,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun detachedNonReplyStaleDraftNeverClaimsOrSends() =
        runBlocking {
            val fixture = fixture(attachConversationController = false)
            val request = dictationRequest(fixture.appState)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("newer"))
            var dispatchClaims = 0
            val staleRequest =
                request.copy(
                    beginDispatch = {
                        dispatchClaims += 1
                        true
                    },
                )

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(staleRequest))

            assertEquals(0, dispatchClaims)
            assertEquals(0, fixture.calls.send.get())
            assertEquals("newer", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun detachedReplyDictationDoesNotSendWithoutItsComposerContext() =
        runBlocking {
            val fixture = fixture(attachConversationController = false)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            var dispatchClaims = 0
            val request =
                dictationRequest(fixture.appState, "typed").copy(
                    replyToMessageIdHex = REPLY_MESSAGE_ID,
                    beginDispatch = {
                        dispatchClaims += 1
                        true
                    },
                )

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(0, dispatchClaims)
            assertEquals(0, fixture.calls.send.get())
            assertEquals("typed", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun nonReplyDetachAfterDispatchClaimUsesTheProcessTransport() =
        runBlocking {
            val fixture = fixture()
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            val request =
                dictationRequest(fixture.appState, "typed").copy(
                    beginDispatch = {
                        fixture.appState.detachConversationController(fixture.conversationController)
                        true
                    },
                )

            assertTrue(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, fixture.calls.send.get())
        }

    @Test
    fun replyDetachAfterDispatchClaimRejectsStandaloneDelivery() =
        runBlocking {
            val fixture = fixture()
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            fixture.conversationController.replyingTo = timelineAppMessage(REPLY_MESSAGE_ID)
            var dispatchClaims = 0
            var preTransportRejections = 0
            val request =
                dictationRequest(fixture.appState, "typed").copy(
                    replyToMessageIdHex = REPLY_MESSAGE_ID,
                    beginDispatch = {
                        dispatchClaims += 1
                        fixture.appState.detachConversationController(fixture.conversationController)
                        true
                    },
                    onDispatchRejectedBeforeTransport = { preTransportRejections += 1 },
                )
            val retained = retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, dispatchClaims)
            assertEquals(1, preTransportRejections)
            assertEquals(0, fixture.calls.send.get())
            assertEquals("typed", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                retained,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun replyComposerMountedAfterDispatchClaimRejectsTheNonReplySend() =
        runBlocking {
            val fixture = fixture(attachConversationController = false)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            fixture.conversationController.replyingTo = timelineAppMessage(REPLY_MESSAGE_ID)
            var dispatchClaims = 0
            var preTransportRejections = 0
            val request =
                dictationRequest(fixture.appState, "typed").copy(
                    beginDispatch = {
                        dispatchClaims += 1
                        fixture.appState.attachConversationController(fixture.conversationController)
                        true
                    },
                    onDispatchRejectedBeforeTransport = { preTransportRejections += 1 },
                )
            val retained = retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)
            try {
                assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

                assertEquals(1, dispatchClaims)
                assertEquals(1, preTransportRejections)
                assertEquals(0, fixture.calls.send.get())
                assertEquals("typed", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
            } finally {
                fixture.appState.detachConversationController(fixture.conversationController)
            }
        }

    @Test
    fun terminallyRejectedDictationSendRestoresDraftAndGeometry() =
        runBlocking {
            val fixture = fixture(sendResult = { throw MarmotKitException.Publish("relay rejected event") })
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            val request = dictationRequest(fixture.appState, "typed")
            val retained = retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, fixture.calls.send.get())
            assertEquals("typed", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                retained,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun unexpectedDictationSendFailureRestoresDraftAndGeometry() =
        runBlocking {
            val fixture = fixture(sendResult = { throw IllegalStateException("send rejected") })
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            val request = dictationRequest(fixture.appState, "typed")
            val retained = retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))
            assertEquals("typed", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                retained,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun staleDictationOriginNeverDispatchesOrClearsGeometry() =
        runBlocking {
            val fixture = fixture()
            val request = dictationRequest(fixture.appState)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("newer"))
            val retained =
                retainExpansion(
                    fixture.appState,
                    draftGeneration = fixture.appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
                )

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(0, fixture.calls.send.get())
            assertEquals("newer", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                retained,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun cancelledDispatchNeverSendsOrClearsGeometry() =
        runBlocking {
            val fixture = fixture()
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            val request = dictationRequest(fixture.appState, "typed").copy(beginDispatch = { false })
            val retained = retainExpansion(fixture.appState, draftGeneration = request.expectedDraftRevision)

            assertFalse(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(0, fixture.calls.send.get())
            assertEquals("typed", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                retained,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun acceptedDictationSendAdvancesTheDraftFenceWithoutCreatingGeometry() =
        runBlocking {
            val fixture = fixture()
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("typed"))
            val request = dictationRequest(fixture.appState, "typed")
            val other = retainExpansion(fixture.appState, OTHER_GROUP)

            assertTrue(fixture.appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertTrue(fixture.appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID) > request.expectedDraftRevision)
            assertTrue(fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID).isNullOrEmpty())
            assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                other,
                fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, OTHER_GROUP),
            )
        }

    @Test
    fun newerDraftDuringDictationSendPreservesTextAfterTheOptimisticGeometryClear() =
        runBlocking {
            lateinit var appState: WhiteNoiseAppState
            val fixture =
                fixture(sendResult = {
                    appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("newer"))
                    successfulSendSummary()
                })
            appState = fixture.appState
            val request = dictationRequest(appState)
            retainExpansion(appState, draftGeneration = request.expectedDraftRevision)

            assertTrue(appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, fixture.calls.send.get())
            assertEquals("newer", appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun newerResizeDuringDictationSendPreservesTheNewGeometry() =
        runBlocking {
            lateinit var appState: WhiteNoiseAppState
            val newer = RetainedComposerExpansion(RetainedComposerExpansionMode.Manual, 320f)
            val fixture =
                fixture(sendResult = {
                    appState.composerExpansionStateRetention.update(
                        ACCOUNT_REF,
                        GROUP_ID,
                        newer,
                        appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
                    )
                    successfulSendSummary()
                })
            appState = fixture.appState
            val request = dictationRequest(appState)
            retainExpansion(appState, draftGeneration = request.expectedDraftRevision)

            assertTrue(appState.sendDictationTranscriptIfOriginUnchanged(request))

            assertEquals(1, fixture.calls.send.get())
            assertTrue(appState.draftFor(ACCOUNT_REF, GROUP_ID).isNullOrEmpty())
            assertEquals(newer, appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
        }

    private fun dictationRequest(
        appState: WhiteNoiseAppState,
        text: String = "",
    ) = ConversationDictationSendRequest(
        accountRef = ACCOUNT_REF,
        groupIdHex = GROUP_ID,
        expectedDraftRevision = appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
        expectedDraftText = text,
        payload = "$text spoken".trim(),
    )

    private fun successfulSendSummary() =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf("dictation-commit"),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    private fun emptySendSummary() =
        SendSummaryFfi(
            published = 0u,
            messageIds = emptyList(),
            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    @Test
    fun successfulChatListLeaveClearsOnlyTheRemovedConversationGeometry() =
        runBlocking {
            val fixture = fixture()
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val other = retainExpansion(fixture.appState, OTHER_GROUP)
            val controller = fixture.seededChatsController()
            try {
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
                assertTrue(controller.leaveGroup(GROUP_ID))

                assertEquals(1, fixture.calls.leave.get())
                assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
                assertEquals(
                    other,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, OTHER_GROUP),
                )
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun failedChatListLeaveRetainsTheConversationGeometry() =
        runBlocking {
            val fixture = fixture(failLeave = true)
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val controller = fixture.seededChatsController()
            try {
                assertFalse(controller.leaveGroup(GROUP_ID))

                assertEquals(1, fixture.calls.leave.get())
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun successfulLocalDeleteClearsOnlyTheRemovedConversationGeometry() =
        runBlocking {
            val fixture = fixture()
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val other = retainExpansion(fixture.appState, OTHER_GROUP)
            val controller = fixture.seededChatsController()
            try {
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
                assertTrue(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(1, fixture.calls.delete.get())
                assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
                assertEquals(
                    other,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, OTHER_GROUP),
                )
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun failedLocalDeleteRetainsTheConversationGeometry() =
        runBlocking {
            val fixture = fixture(failDelete = true)
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val controller = fixture.seededChatsController()
            try {
                assertFalse(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))

                assertEquals(1, fixture.calls.delete.get())
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun closedTransportRetriesLocalDeleteAndCleansUpOnce() =
        runBlocking {
            val fixture = fixture(deleteTransportFailures = 1)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("keep until commit"))
            retainExpansion(fixture.appState, GROUP_ID)
            val controller = fixture.seededChatsController()
            try {
                assertTrue(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, fixture.calls.delete.get())
                assertEquals(1, fixture.calls.chatList.get())
                assertTrue(controller.items.none { it.group.groupIdHex == GROUP_ID })
                assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
                assertTrue(fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID).isNullOrEmpty())
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun committedLocalDeleteWithLostResponseDoesNotRepeatWipe() =
        runBlocking {
            val fixture = fixture(deleteTransportFailures = 1, commitBeforeTransportFailure = true)
            val controller = fixture.seededChatsController()
            try {
                assertTrue(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, fixture.calls.delete.get())
                assertEquals(1, fixture.calls.chatList.get())
                assertTrue(controller.items.none { it.group.groupIdHex == GROUP_ID })
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun committedLocalDeleteStillClearsLocalArtifactsWhenNativeDraftCleanupFails() =
        runBlocking {
            val fixture = fixture(failDraftDelete = true)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("remove locally"))
            retainExpansion(fixture.appState, GROUP_ID)
            val controller = fixture.seededChatsController()
            try {
                assertTrue(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, fixture.calls.delete.get())
                assertTrue(controller.items.none { it.group.groupIdHex == GROUP_ID })
                assertTrue(fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID).isNullOrEmpty())
                assertNull(fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
            } finally {
                controller.onCleared()
            }
        }

    @Test
    fun exhaustedClosedTransportRestoresRowDraftAndGeometry() =
        runBlocking {
            val fixture = fixture(deleteTransportFailures = IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS)
            fixture.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("still drafting"))
            val retained = retainExpansion(fixture.appState, GROUP_ID)
            val controller = fixture.seededChatsController()
            try {
                assertFalse(controller.deleteGroupLocalFromChatList(GROUP_ID, notify = false))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(IDEMPOTENT_RUNTIME_MUTATION_RETRY_ATTEMPTS, fixture.calls.delete.get())
                assertEquals(1, controller.items.count { it.group.groupIdHex == GROUP_ID })
                assertEquals("still drafting", fixture.appState.draftFor(ACCOUNT_REF, GROUP_ID))
                assertEquals(
                    retained,
                    fixture.appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
                )
            } finally {
                controller.onCleared()
            }
        }

    /** Creates one isolated app/runtime pair with controllable native leave and delete commits. */
    private fun fixture(
        failLeave: Boolean = false,
        failDelete: Boolean = false,
        deleteTransportFailures: Int = 0,
        commitBeforeTransportFailure: Boolean = false,
        failDraftDelete: Boolean = false,
        sendResult: () -> SendSummaryFfi = ::successfulSendSummary,
        attachConversationController: Boolean = true,
    ): LifecycleFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(LifecycleDraftPersistence),
                accountIdHexResolver = { ACCOUNT_ID },
                accounts = listOf(account()),
                activeAccountRef = ACCOUNT_REF,
                messageDraftRepository = draftRepository(failDraftDelete),
            )
        val calls = LifecycleCalls()
        val marmot =
            lifecycleMarmot(
                failLeave,
                failDelete,
                deleteTransportFailures,
                commitBeforeTransportFailure,
                calls,
                sendResult,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredField("marmotRuntime")
            .apply { isAccessible = true }
            .set(appState, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        val conversationController = ConversationController(appState = appState, initialGroup = group())
        if (attachConversationController) appState.attachConversationController(conversationController)
        return LifecycleFixture(appState, calls, conversationController)
    }

    /** Retains one manual expansion and returns the exact value expected after a failed commit. */
    private fun retainExpansion(
        appState: WhiteNoiseAppState,
        groupIdHex: String = GROUP_ID,
        draftGeneration: Long = 1L,
    ): RetainedComposerExpansion =
        RetainedComposerExpansion(RetainedComposerExpansionMode.Manual, 240f).also { preference ->
            appState.composerExpansionStateRetention.update(
                accountRef = ACCOUNT_REF,
                groupIdHex = groupIdHex,
                preference = preference,
                draftGeneration = draftGeneration,
            )
        }

    /**
     * Implements only the native lifecycle calls exercised by these production controller paths,
     * one scripted branch per Marmot call the send path makes.
     */
    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
    private fun lifecycleMarmot(
        failLeave: Boolean,
        failDelete: Boolean,
        deleteTransportFailures: Int,
        commitBeforeTransportFailure: Boolean,
        calls: LifecycleCalls,
        sendResult: () -> SendSummaryFfi,
    ): MarmotInterface {
        var localGroupPresent = true
        return Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            /** Completes one reflected suspend call with the requested native failure. */
            fun suspendFailure(failure: Throwable): Any {
                (arguments!!.last() as Continuation<Any?>).resumeWithException(failure)
                return COROUTINE_SUSPENDED
            }

            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "selectedMessageDraft" -> SelectedMessageDraftFfi(emptyDraftRevision, null)
                "localSendStatus" -> null
                "sendTextWithClientToken" -> {
                    calls.send.incrementAndGet()
                    val summary = sendResult()
                    LocalSendAcceptanceFfi(
                        clientToken = arguments!![3] as String,
                        messageIdHex = summary.messageIds.single(),
                    )
                }
                "sendText" -> {
                    calls.send.incrementAndGet()
                    sendResult()
                }
                "groupMembers" -> members()
                "listMedia" -> emptyList<Any>()
                "chatList" -> {
                    calls.chatList.incrementAndGet()
                    if (localGroupPresent) listOf(groupRow()) else emptyList<ChatListRowFfi>()
                }
                "leaveGroup" -> {
                    calls.leave.incrementAndGet()
                    if (failLeave) {
                        suspendFailure(IllegalStateException("leave rejected"))
                    } else {
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf("leave-commit"),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    }
                }
                "deleteGroupLocal" -> {
                    val attempt = calls.delete.incrementAndGet()
                    if (failDelete) {
                        suspendFailure(IllegalStateException("delete rejected"))
                    } else if (attempt <= deleteTransportFailures) {
                        if (commitBeforeTransportFailure) localGroupPresent = false
                        suspendFailure(MarmotKitException.TransportClosed())
                    } else {
                        localGroupPresent = false
                        true
                    }
                }
                "toString" -> "ComposerExpansionLifecycleMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface
    }

    /** Creates a signed-in local account matching the self member returned by the native fixture. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Supplies a non-sole-member roster so leave uses the real remote commit path. */
    private fun members() =
        listOf(
            AppGroupMemberRecordFfi(memberIdHex = ACCOUNT_ID, account = ACCOUNT_REF, local = true),
            AppGroupMemberRecordFfi(memberIdHex = PEER_ID, account = null, local = false),
        )

    /** Wraps an injected repository whose successful draft delete cannot invoke Marmot. */
    private fun draftRepository(failDelete: Boolean = false) =
        MessageDraftRepository(
            gateway =
                if (failDelete) {
                    object : MessageDraftGateway by EmptyDraftGateway {
                        override fun read(
                            accountRef: String,
                            groupIdHex: String,
                        ): MessageDraftFfi =
                            EmptyDraftGateway.save(accountRef, groupIdHex, "persisted draft", null, emptyList())

                        override fun delete(
                            accountRef: String,
                            groupIdHex: String,
                        ): Unit = throw IllegalStateException("draft deletion rejected")
                    }
                } else {
                    EmptyDraftGateway
                },
            editorSessions = EditorSessionStore(LifecycleEditorStrings),
            ioDispatcher = Dispatchers.Unconfined,
        )

    /** Holds the production state and seeds one real chat-list controller route. */
    private class LifecycleFixture(
        val appState: WhiteNoiseAppState,
        val calls: LifecycleCalls,
        val conversationController: ConversationController,
    ) {
        /** Seeds the actual chat-list projection required by its leave and delete actions. */
        fun seededChatsController(): ChatsController =
            ChatsController(
                appState = appState,
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            ).also { controller ->
                controller.setChatListVisible(false)
                controller.applyChatListRow(groupRow())
                controller.applyLocalGroupUpdate(group())
                controller.setChatListVisible(true)
            }
    }

    /** Counts authoritative native mutations so false results cannot pass via an earlier guard. */
    private class LifecycleCalls {
        val send = AtomicInteger()
        val leave = AtomicInteger()
        val delete = AtomicInteger()
        val chatList = AtomicInteger()
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        val ACCOUNT_ID = "a1".repeat(32)
        val PEER_ID = "b2".repeat(32)
        const val GROUP_ID = "group-a"
        const val OTHER_GROUP = "group-b"
        const val REPLY_MESSAGE_ID = "reply-message"

        /** Builds the one stable group used by both leave and delete controller routes. */
        fun group() =
            AppGroupRecordFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                groupIdHex = GROUP_ID,
                protocolProfile = AppProtocolProfileFfi.LEGACY,
                profilePresent = false,
                endpoint = "endpoint",
                name = "Group",
                description = "",
                admins = listOf(PEER_ID),
                relays = emptyList(),
                nostrGroupIdHex = "nostr-group",
                avatarUrl = null,
                avatarDim = null,
                avatarThumbhash = null,
                imageHashHex = null,
                encryptedMedia = encryptedMedia(),
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

        /** Mirrors the stable row consumed by the production chat-list mutation methods. */
        fun groupRow() =
            ChatListRowFfi(
                selfMembership = SelfMembershipFfi.MEMBER,
                unreadMentionCount = 0uL,
                unreadMention = false,
                groupIdHex = GROUP_ID,
                archived = false,
                pendingConfirmation = false,
                title = "Group",
                groupName = "Group",
                avatarUrl = null,
                avatar = null,
                lastMessage = null,
                unreadCount = 0uL,
                hasUnread = false,
                firstUnreadMessageIdHex = null,
                lastReadMessageIdHex = null,
                lastReadTimelineAt = null,
                conversationCreatedAt = 1uL,
                activitySortAt = 1uL,
                updatedAt = 1uL,
                leaveRequestPending = false,
                leaveRequestedAtMs = null,
                manuallyMarkedUnread = false,
                conversationKind = ChatConversationKindFfi.GROUP,
                muted = false,
                mutedUntilMs = null,
                pinned = false,
                pinnedPosition = null,
                lifecycleState = GroupLifecycleStateFfi.STABLE,
                disbanding = false,
                disbandRequest = null,
            )

        /** Supplies the encrypted-media component required by current group records. */
        fun encryptedMedia() =
            AppGroupEncryptedMediaComponentFfi(
                componentId = 0x8008u,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                version = EncryptedMediaVersionFfi.V1,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = listOf("blossom-v1"),
                defaultBlobEndpoints =
                    listOf(
                        AppBlobEndpointFfi(
                            locatorKind = "blossom-v1",
                            baseUrl = "https://blossom.example",
                        ),
                    ),
            )
    }
}

/** Empty draft gateway keeps lifecycle tests focused on group commit ordering. */
private object EmptyDraftGateway : MessageDraftGateway {
    /** Reports no MDK draft before group removal. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = null

    /** Echoes a requested draft for interface completeness; lifecycle tests never call this path. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ) = MessageDraftFfi(
        groupIdHex = groupIdHex,
        content = content,
        replyToMessageIdHex = replyToMessageIdHex,
        mediaAttachments = mediaAttachments,
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    /** Accepts the production pre-removal draft cleanup. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) = Unit

    /** Reports no draft summaries outside the removed conversation. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

/** Empty in-memory persistence avoids test coupling to encrypted editor storage. */
private object LifecycleEditorStrings : EditorStringStore {
    /** Starts without any retained editor sessions. */
    override fun readAll(): Map<String, String> = emptyMap()

    /** Accepts the empty replacement set used during lifecycle cleanup. */
    override fun replaceAll(values: Map<String, String>): Boolean = true

    /** Clears the already-empty fixture store. */
    override fun clear() = Unit
}

/** Empty legacy persistence keeps this lifecycle suite independent of disk state. */
private object LifecycleDraftPersistence : DraftPersistence {
    /** Starts without legacy lifecycle drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Ignores legacy writes while the in-memory draft store remains observable. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

/** MDK 0.10.0 sends read the selected draft first; these cases have none, so the direct send path is taken. */
private val emptyDraftRevision: MessageDraftRevisionFfi by lazy {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = field.get(null)
    unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, MessageDraftRevisionFfi::class.java)
        as MessageDraftRevisionFfi
}
