package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routes chat removal through both microphone owners and separates voice discard from other teardown. */
class VoiceDraftDiscardCoverageTest {
    /** Leave, local-delete journal cleanup and confirmed eviction all revoke this chat's capture. */
    @Test
    fun removedChatsReleaseBothCaptureOwners() {
        val owner = source("state/AppStateCaptureOwners.kt", "releaseCaptureTarget")
        assertTrue("conversationDictation.onTargetRemoved(account, groupIdHex)" in owner)
        assertTrue("voiceNotes.onTargetRemoved(account, groupIdHex)" in owner)
        assertTrue(
            "releaseCaptureTarget(accountRef, groupIdHex)" in
                source("state/AppState.kt", "removeActiveAccountFromGroupMemberSnapshot"),
        )
        assertTrue(
            "releaseCaptureTarget(account, groupIdHex)" in
                source("state/GroupLocalRemoval.kt", "finishLocalGroupDeleteCleanup"),
        )
        assertTrue(
            "appState.releaseCaptureTarget(account, group.groupIdHex)" in
                source("state/ConversationControllerRoster.kt", "markActiveAccountRemovedFromMembers"),
        )
    }

    /** The account-wide group owner forwards a confirmed eviction even while the originating chat is closed. */
    @Test
    fun accountGroupProjectionReleasesDetachedCapture() {
        val projection = "state/ChatsControllerProjection.kt"
        assertTrue("appState.releaseEvictedCapture(" in source(projection, "foldGroup"))
        assertTrue("appState.releaseEvictedCapture(" in source(projection, "installGroupRecordSnapshot"))
        val eviction = source("state/AppStateCaptureOwners.kt", "releaseEvictedCapture")
        assertTrue("current.selfMembership.isGroupEviction()" in eviction)
        assertTrue("releaseCaptureTarget(account, current.groupIdHex)" in eviction)
    }

    /** The process event handler reads native state for groups whose profile has no chat subscription. */
    @Test
    fun runtimeGroupEventsReachTheProcessCaptureOwner() {
        val events = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/AppStateNotificationContent.kt")
        assertTrue("checkCaptureEvictionOnRuntimeEvent(event, marmot, scope = this)" in events)
        val capture = source("state/AppStateCaptureOwners.kt", "checkCaptureEvictionOnRuntimeEvent")
        assertTrue("reconcileCaptureEviction(key, runtime)" in capture)
    }

    /** The composer uses the durable store outcome, including for Record Again. */
    @Test
    fun userDiscardAndRecordAgainUseTheSameDurableGate() {
        val composer = source("ui/conversation/ConversationVoiceCapture.kt", "rememberConversationVoiceCapture")
        assertTrue("deleteFile = { file -> owner.consume(key, file) }" in composer)
        val review = "ui/conversation/composer/VoiceRecordingReview.kt"
        val discard = source(review, "discardByUser")
        val commit = discard.indexOf("commitDiscard(expected)")
        assertTrue("Discards clear only after a commit", commit in 0 until discard.indexOf("if (discarded) clearClip"))
        assertTrue("deleteFile(expected.file)" in source(review, "commitDiscard"))
        assertEquals(1, Regex("""\bdiscardByUser\(expected\)""").findAll(source(review, "recordAgain")).count())
    }

    /** Wipe stops both microphone owners before transport and purges voice drafts only after native confirmation. */
    @Test
    fun wipeStopsCaptureBeforeTransportAndPurgesAfterCommit() {
        val wipe = source("state/AppState.kt", "finishRevokedAccountWipe")
        val stop = wipe.indexOf("stopCaptureOwners(wipedRef)")
        assertTrue("stop capture before wiping", stop in 0 until wipe.indexOf("wipeAccountAndPurgeVoiceDrafts"))
        val wipeDrafts = source("state/AppStateCaptureOwners.kt", "wipeAccountAndPurgeVoiceDrafts")
        assertTrue("signOutAndWipe(account)" in wipeDrafts)
        assertTrue("if (outcome.localCleanup.completed) voiceNotes.purgeAccountDrafts(account)" in wipeDrafts)
    }

    /** Reads one named production block and fails if the source or function cannot be resolved. */
    private fun source(path: String, function: String): String {
        val source = appModuleSource("src/main/java/dev/ipf/whitenoise/android/$path")
        return source.functionBody(function)
    }
}
