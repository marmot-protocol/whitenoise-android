package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceRecordingReview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual native attachment queue across its suspended Markdown preparation boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class VoiceReviewQueueAdmissionTest {
    @get:Rule val files = TemporaryFolder()

    /** Revocation after native parsing begins must publish no row, retained bytes, upload or retention state. */
    @Test
    fun disposalDuringNativeMarkdownPreparationRejectsQueueAndFreshOwnerStillQueues() =
        runBlocking {
            val appState = testAppState()
            val parseStarted = CompletableDeferred<Unit>()
            val releaseParse = CompletableDeferred<Unit>()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    markdownParser = {
                        parseStarted.complete(Unit)
                        releaseParse.await()
                        EMPTY_MARKDOWN_DOCUMENT
                    },
                )
            val results = mutableListOf<Boolean>()
            var dispatch: Job? = null
            val reviews = mutableListOf<VoiceRecordingReview>()

            /** Connects the actual review admission callback to the native queue without invoking network upload. */
            fun review(): VoiceRecordingReview =
                VoiceRecordingReview(
                    scope = this,
                    ownerIsCurrent = { true },
                    send = { file, _, canQueue, onQueued ->
                        val attachment = PendingAttachment(file.readBytes(), "audio/mp4", file.name)
                        dispatch =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                val queued = controller.queueAttachments(listOf(attachment), null, canQueue)
                                results += queued != null
                                onQueued(queued != null)
                            }
                    },
                ).also { reviews += it }
            try {
                controller.retryMembers()
                assertTrue(controller.canSendMessages)
                val oldReview = review()
                val oldFile = files.newFile("old.m4a").apply { writeBytes(byteArrayOf(1, 2)) }
                oldReview.offer(oldFile, 1_000L)
                oldReview.send(checkNotNull(oldReview.clip))
                assertTrue("the actual native parser must have suspended", parseStarted.isCompleted)
                assertTrue(checkNotNull(dispatch).isActive)
                assertTrue(controller.timeline.isEmpty())
                oldReview.release()
                assertFalse(oldFile.exists())
                releaseParse.complete(Unit)
                checkNotNull(dispatch).join()
                assertEquals(listOf(false), results)
                assertTrue(controller.timeline.isEmpty())
                assertTrue(appState.retainedMediaUploads(ACCOUNT_REF, GROUP_ID).keysSnapshot().isEmpty())
                assertTrue(appState.activeUploadKeys(ACCOUNT_REF, GROUP_ID).isEmpty())
                assertTrue(appState.retentionAtSend(ACCOUNT_REF, GROUP_ID).isEmpty())

                val currentReview = review()
                val currentFile = files.newFile("current.m4a").apply { writeBytes(byteArrayOf(3, 4)) }
                currentReview.offer(currentFile, 2_000L)
                currentReview.send(checkNotNull(currentReview.clip))
                checkNotNull(dispatch).join()
                assertEquals(listOf(false, true), results)
                assertNull(currentReview.clip)
                assertFalse(currentFile.exists())
                val message = controller.timeline.single()
                assertEquals(MessageStatus.Pending, message.status)
                assertEquals(60uL, message.retentionAtSendSeconds)
                assertNotNull(appState.retainedMediaUploads(ACCOUNT_REF, GROUP_ID).get(message.id))
                assertEquals(setOf(message.id), appState.activeUploadKeys(ACCOUNT_REF, GROUP_ID).toSet())
                assertEquals(1, appState.retentionAtSend(ACCOUNT_REF, GROUP_ID).size)
            } finally {
                releaseParse.complete(Unit)
                dispatch?.cancel()
                reviews.forEach { it.release() }
            }
        }
}

private const val ACCOUNT_REF = "voice-review-account"
private val ACCOUNT_ID = "a1".repeat(32)
private val GROUP_ID = "b2".repeat(32)

/** Provides the account-pinned state required by optimistic conversation sends. */
private fun testAppState(): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext<Context>(),
        draftStore = DraftStore(queueTestDraftPersistence()),
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
    )

/** Seeds verified local membership so send guards admit the fixture account. */
private fun memberSnapshot() =
    GroupMemberSnapshot(
        listOf(
            AppGroupMemberRecordFfi(
                memberIdHex = ACCOUNT_ID,
                account = ACCOUNT_REF,
                local = true,
            ),
        ),
    )

/** Creates the stable group generation shared by the controller and member fixture. */
private fun group(selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER) =
    AppGroupRecordFfi(
        groupIdHex = GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Send group",
        description = "",
        admins = listOf(ACCOUNT_ID),
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "04".repeat(32),
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
                defaultBlobEndpoints =
                    listOf(
                        AppBlobEndpointFfi(
                            locatorKind = "blossom-v1",
                            baseUrl = "https://blossom.example",
                        ),
                    ),
            ),
        disappearingMessageSecs = 60uL,
        archived = false,
        pendingConfirmation = false,
        unrecoverable = false,
        selfMembership = selfMembership,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

/** Keeps send-test drafts process-local while honoring the production persistence boundary. */
private fun queueTestDraftPersistence(): DraftPersistence =
    object : DraftPersistence {
        /** Starts every fixture without persisted composer text. */
        override fun read(): Map<String, String> = emptyMap()

        /** Accepts fixture writes without touching disk. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

/** Provides an authoritative roster through the native refresh path; cached seeds never grant send admission. */
private fun authoritativeRoster() =
    GroupRosterFfi(
        groupIdHex = GROUP_ID,
        members =
            listOf(
                GroupMemberDetailsFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT_REF,
                    local = true,
                    isAdmin = true,
                    isSelf = true,
                    npub = "npub-$ACCOUNT_ID",
                    displayName = null,
                ),
            ),
        epoch = 1uL,
        rosterRevision = 1uL,
        selfMembership = SelfMembershipFfi.MEMBER,
        memberCount = 1u,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
    )
