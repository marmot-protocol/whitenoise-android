package dev.ipf.whitenoise.android.notifications

import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class NotificationTargetTest {
    // ---- routeInboundIntent -------------------------------------------------

    private val noPending = InboundIntentRouting(notificationTarget = null, profilePayload = null, shareRequest = null)

    @Test
    fun routeInboundIntent_notificationTargetWinsAndClearsProfileLink() {
        val target = NotificationTarget("acct-a", "g1", "m1", NotificationTargetKind.MESSAGE)
        val routed =
            routeInboundIntent(
                parsedTarget = target,
                shareRequest = null,
                dataString = "whitenoise://profile/npub1abc",
                current = InboundIntentRouting(null, "whitenoise://profile/old", null),
            )
        assertEquals(target, routed.notificationTarget)
        assertNull(routed.profilePayload)
        assertEquals(1L, routed.notificationRequestId)
    }

    @Test
    fun routeInboundIntent_dataUriBecomesProfilePayload() {
        val routed =
            routeInboundIntent(
                parsedTarget = null,
                shareRequest = null,
                dataString = "whitenoise://profile/npub1abc",
                current = noPending,
            )
        assertNull(routed.notificationTarget)
        assertEquals("whitenoise://profile/npub1abc", routed.profilePayload)
    }

    @Test
    fun routeInboundIntent_datalessNonNotificationIntentPreservesPendingDeepLink() {
        // Regression for #67: a bare relaunch intent (no data, not a
        // notification) must not silently discard a queued profile deep link.
        val pending = InboundIntentRouting(null, "whitenoise://profile/npub1pending", null)
        val routed = routeInboundIntent(parsedTarget = null, shareRequest = null, dataString = null, current = pending)
        assertEquals(pending, routed)
    }

    @Test
    fun routeInboundIntent_datalessIntentPreservesPendingNotificationTarget() {
        val target = NotificationTarget("acct-a", "g1", "m1", NotificationTargetKind.MESSAGE)
        val pending = InboundIntentRouting(target, null, null, notificationRequestId = 2L)
        val routed = routeInboundIntent(parsedTarget = null, shareRequest = null, dataString = null, current = pending)
        assertEquals(pending, routed)
    }

    @Test
    fun inboundNotificationHandledMatchesCurrent_equalTargetStaleRequestId_doesNotMatch() {
        val target = NotificationTarget("acct-a", "g1", "m1", NotificationTargetKind.MESSAGE)
        assertFalse(
            inboundNotificationHandledMatchesCurrent(
                inboundTarget = target,
                inboundRequestId = 2L,
                handledTarget = target,
                handledRequestId = 1L,
            ),
        )
    }

    @Test
    fun inboundNotificationHandledMatchesCurrent_matchingTargetAndRequestId_matches() {
        val target = NotificationTarget("acct-a", "g1", "m1", NotificationTargetKind.MESSAGE)
        assertTrue(
            inboundNotificationHandledMatchesCurrent(
                inboundTarget = target,
                inboundRequestId = 2L,
                handledTarget = target,
                handledRequestId = 2L,
            ),
        )
    }

    @Test
    fun routeInboundIntent_repeatedSameNotificationTargetAdvancesRequestId() {
        val target = NotificationTarget("acct-a", "g1", "m1", NotificationTargetKind.MESSAGE)
        val first =
            routeInboundIntent(
                parsedTarget = target,
                shareRequest = null,
                dataString = null,
                current = noPending,
            )
        assertEquals(target, first.notificationTarget)
        assertEquals(1L, first.notificationRequestId)

        val second =
            routeInboundIntent(
                parsedTarget = target,
                shareRequest = null,
                dataString = null,
                current = first,
            )
        assertEquals(target, second.notificationTarget)
        assertEquals(2L, second.notificationRequestId)
    }

    // ---- fromUpdate ---------------------------------------------------------

    @Test
    fun fromUpdate_messageNotification_carriesMessageId() {
        val target = NotificationNavigation.fromUpdate(update(messageId = "m1"))
        assertEquals(
            NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE),
            target,
        )
    }

    @Test
    fun fromUpdate_invite_dropsMessageId() {
        val target =
            NotificationNavigation.fromUpdate(
                update(trigger = NotificationTriggerFfi.GROUP_INVITE, messageId = "m1"),
            )
        assertEquals(NotificationTargetKind.INVITE, target?.kind)
        assertNull("invite target should not carry a message id", target?.messageIdHex)
    }

    @Test
    fun fromUpdate_membershipTriggersRemainUnroutableUntilTheirPresentationLands() {
        listOf(
            NotificationTriggerFfi.REMOVED_FROM_GROUP,
            NotificationTriggerFfi.MADE_ADMIN,
            NotificationTriggerFfi.REMOVED_AS_ADMIN,
        ).forEach { trigger ->
            assertNull(NotificationNavigation.fromUpdate(update(trigger = trigger)))
        }
    }

    @Test
    fun fromUpdate_blankAccountOrGroup_returnsNull() {
        assertNull(NotificationNavigation.fromUpdate(update(accountRef = "")))
        assertNull(NotificationNavigation.fromUpdate(update(groupIdHex = "")))
    }

    // ---- parseExtras (untrusted intent fields) ------------------------------

    @Test
    fun parseExtras_validMessage_roundTrips() {
        val target =
            NotificationNavigation.parseExtras(
                action = NotificationNavigation.ACTION_OPEN,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                kindName = "MESSAGE",
            )
        assertEquals(NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE), target)
    }

    @Test
    fun parseExtras_wrongAction_returnsNull() {
        assertNull(
            NotificationNavigation.parseExtras("android.intent.action.VIEW", "a", "g", null, "MESSAGE"),
        )
    }

    @Test
    fun parseExtras_missingFieldsOrBadKind_returnNull() {
        val ok = NotificationNavigation.ACTION_OPEN
        assertNull(NotificationNavigation.parseExtras(ok, null, "g", null, "MESSAGE"))
        assertNull(NotificationNavigation.parseExtras(ok, "a", " ", null, "MESSAGE"))
        assertNull(NotificationNavigation.parseExtras(ok, "a", "g", null, "NONSENSE"))
        assertNull(NotificationNavigation.parseExtras(ok, "a", "g", null, null))
    }

    @Test
    fun parseExtras_inviteIgnoresMessageId() {
        val target =
            NotificationNavigation.parseExtras(
                NotificationNavigation.ACTION_OPEN,
                "a",
                "g",
                "m1",
                "INVITE",
            )
        assertNull(target?.messageIdHex)
    }

    @Test
    fun trustedNotificationTap_requiresNonBlankKeyAndTrustedToken() {
        assertFalse(NotificationNavigation.isTrustedNotificationTap(null, "trusted-token-123") { _, _ -> true })
        assertFalse(NotificationNavigation.isTrustedNotificationTap(" ", "trusted-token-123") { _, _ -> true })
        assertFalse(
            NotificationNavigation.isTrustedNotificationTap("acct-a|group-1", "trusted-token-123") { _, token ->
                token == "other-token"
            },
        )
        assertTrue(
            NotificationNavigation.isTrustedNotificationTap("acct-a|group-1", "trusted-token-123") { notificationKey, token ->
                notificationKey == "acct-a|group-1" && token == "trusted-token-123"
            },
        )
    }

    // ---- PendingIntent identity --------------------------------------------

    @Test
    fun requestCode_isStablePerKeyAndDistinctAcrossKeys() {
        assertEquals(
            NotificationNavigation.requestCode("key-1"),
            NotificationNavigation.requestCode("key-1"),
        )
        assertNotEquals(
            NotificationNavigation.requestCode("key-1"),
            NotificationNavigation.requestCode("key-2"),
        )
    }

    @Test
    fun targetUriString_isDistinctPerKey() {
        assertNotEquals(
            NotificationNavigation.targetUriString("key-1"),
            NotificationNavigation.targetUriString("key-2"),
        )
        // Blank key still yields a usable, identical URI (not an exception).
        assertEquals(
            NotificationNavigation.targetUriString(""),
            NotificationNavigation.targetUriString(""),
        )
    }

    // ---- Notification actions ----------------------------------------------

    @Test
    fun actionTarget_messageNotification_requiresMessageTarget() {
        val target =
            NotificationActions.targetFromUpdate(
                update(messageId = "m1"),
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            )
        assertEquals(
            NotificationActionTarget(
                NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE),
                "acct-a|group-1",
                0,
            ),
            target,
        )
        assertNull(
            NotificationActions.targetFromUpdate(
                update(trigger = NotificationTriggerFfi.GROUP_INVITE, messageId = "m1"),
                notificationTag = "invite-key",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.targetFromUpdate(
                update(messageId = null),
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.targetFromUpdate(
                update(messageId = "m1"),
                notificationTag = " ",
                notificationId = 0,
            ),
        )
    }

    @Test
    fun actionParseFields_validReply_roundTrips() {
        val action =
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_REPLY,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            )
        assertEquals(
            NotificationAction(
                NotificationActionKind.REPLY,
                NotificationTarget("acct-a", "group-1", "m1", NotificationTargetKind.MESSAGE),
                "acct-a|group-1",
                0,
            ),
            action,
        )
    }

    @Test
    fun actionParseFields_rejectsUntrustedOrIncompletePayloads() {
        assertNull(
            NotificationActions.parseRawFields(
                action = "android.intent.action.VIEW",
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = null,
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "INVITE",
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = " ",
                notificationId = 0,
            ),
        )
        assertNull(
            NotificationActions.parseRawFields(
                action = NotificationActions.ACTION_MARK_READ,
                accountRef = "acct-a",
                groupIdHex = "group-1",
                messageIdHex = "m1",
                targetKindName = "MESSAGE",
                notificationTag = "acct-a|group-1",
                notificationId = null,
            ),
        )
    }

    @Test
    fun actionIdentity_isDistinctPerKindAndTag() {
        assertNotEquals(
            NotificationActions.requestCode(NotificationActionKind.REPLY, "acct-a|group-1"),
            NotificationActions.requestCode(NotificationActionKind.MARK_READ, "acct-a|group-1"),
        )
        assertNotEquals(
            NotificationActions.requestCode(NotificationActionKind.REPLY, "acct-a|group-1"),
            NotificationActions.requestCode(NotificationActionKind.REPLY, "acct-a|group-2"),
        )
        assertNotEquals(
            NotificationActions.actionUriString(NotificationActionKind.REPLY, "acct-a|group-1"),
            NotificationActions.actionUriString(NotificationActionKind.MARK_READ, "acct-a|group-1"),
        )
    }

    @Test
    fun replyActionHandled_gatesOnSentOnly_markReadIsBestEffort() {
        // A successful send must dismiss the notification regardless of whether
        // the best-effort mark-read step succeeded. Gating on mark-read would
        // leave the inline RemoteInput field active and let the user re-send,
        // posting a duplicate message to the group (issue #272).
        assertTrue(notificationReplyActionHandled(sent = true))
        assertFalse(notificationReplyActionHandled(sent = false))
    }

    @Test
    fun replyWorkerRequest_roundTripsActionAndKeepsIdenticalCompletionKeysDistinct() {
        val action =
            NotificationAction(
                kind = NotificationActionKind.REPLY,
                target = NotificationTarget("acct-a", "group-1", "msg-1", NotificationTargetKind.MESSAGE),
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            )
        val firstRequestId = UUID.randomUUID()
        val secondRequestId = UUID.randomUUID()
        val firstEncryptedReply = testReplyCipher().encrypt("hello", firstRequestId, action)
        val secondEncryptedReply = testReplyCipher().encrypt("hello", secondRequestId, action)
        val firstRequest = NotificationReplyWorker.notificationReplyRequest(action, firstRequestId, firstEncryptedReply)
        val secondRequest = NotificationReplyWorker.notificationReplyRequest(action, secondRequestId, secondEncryptedReply)
        val firstCompletionKey = NotificationReplyWorker.notificationReplyCompletionKey(firstRequest.id)

        assertEquals(
            action,
            NotificationReplyWorker.notificationReplyActionFromInput(
                NotificationReplyWorker.notificationReplyInputData(action, firstEncryptedReply),
            ),
        )
        assertEquals(firstRequestId, firstRequest.id)
        assertEquals(firstCompletionKey, NotificationReplyWorker.notificationReplyCompletionKey(firstRequest.id))
        assertNotEquals(firstCompletionKey, NotificationReplyWorker.notificationReplyCompletionKey(secondRequest.id))
    }

    @Test
    fun markReadWorkerRequest_roundTripsActionAndUsesBoundedBackoff() {
        val action =
            NotificationAction(
                kind = NotificationActionKind.MARK_READ,
                target = NotificationTarget("acct-a", "group-1", "msg-1", NotificationTargetKind.MESSAGE),
                notificationTag = "acct-a|group-1",
                notificationId = 7,
            )

        val request = NotificationMarkReadWorker.notificationMarkReadRequest(action)

        assertEquals(action, NotificationActionWorkData.decode(request.workSpec.input))
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertEquals(
            NotificationMarkReadWorker.notificationMarkReadWorkName(action),
            NotificationMarkReadWorker.notificationMarkReadWorkName(action.copy(notificationId = 99)),
        )
        assertNotEquals(
            NotificationMarkReadWorker.notificationMarkReadWorkName(action),
            NotificationMarkReadWorker.notificationMarkReadWorkName(
                action.copy(target = action.target.copy(messageIdHex = "msg-2")),
            ),
        )
        assertTrue(NotificationMarkReadWorker.shouldRetryAfterFailure(0))
        assertTrue(NotificationMarkReadWorker.shouldRetryAfterFailure(1))
        assertFalse(NotificationMarkReadWorker.shouldRetryAfterFailure(2))
    }

    @Test
    fun replyWorkerInput_doesNotPersistPlaintext() {
        val action =
            NotificationAction(
                kind = NotificationActionKind.REPLY,
                target = NotificationTarget("acct-a", "group-1", "msg-1", NotificationTargetKind.MESSAGE),
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            )
        val reply = "shade reply plaintext sentinel"
        val requestId = UUID.randomUUID()
        val encryptedReply = testReplyCipher().encrypt(reply, requestId, action)

        val request = NotificationReplyWorker.notificationReplyRequest(action, requestId, encryptedReply)
        val input = request.workSpec.input
        val restoredEncryptedReply =
            (NotificationReplyWorker.notificationReplyFromInput(input) as NotificationReplyInput.Encrypted).reply

        assertEquals(requestId, request.id)
        assertFalse(input.keyValueMap.containsKey("reply"))
        assertFalse(input.keyValueMap.values.any { it == reply })
        assertFalse(input.toByteArray().toString(Charsets.ISO_8859_1).contains(reply))
        assertEquals(reply, testReplyCipher().decrypt(restoredEncryptedReply, requestId, action))
    }

    @Test
    fun encryptedReplyEquality_usesByteContent() {
        val encryptedReply =
            EncryptedNotificationReply(
                initializationVector = byteArrayOf(1, 2, 3),
                ciphertext = byteArrayOf(4, 5, 6),
            )
        val sameBytes =
            EncryptedNotificationReply(
                initializationVector = encryptedReply.initializationVector.copyOf(),
                ciphertext = encryptedReply.ciphertext.copyOf(),
            )

        assertEquals(encryptedReply, sameBytes)
        assertEquals(encryptedReply.hashCode(), sameBytes.hashCode())
    }

    @Test
    fun replyCipher_rejectsCiphertextFromAnotherWorkRequestOrRoute() {
        val action =
            NotificationAction(
                kind = NotificationActionKind.REPLY,
                target = NotificationTarget("acct-a", "group-1", "msg-1", NotificationTargetKind.MESSAGE),
                notificationTag = "acct-a|group-1",
                notificationId = 7,
            )
        val requestId = UUID.randomUUID()
        val encryptedReply = testReplyCipher().encrypt("hello", requestId, action)

        assertThrows(GeneralSecurityException::class.java) {
            testReplyCipher().decrypt(encryptedReply, UUID.randomUUID(), action)
        }
        val tamperedInitializationVector =
            encryptedReply.initializationVector.copyOf().also { bytes ->
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
            }
        assertThrows(GeneralSecurityException::class.java) {
            testReplyCipher().decrypt(
                encryptedReply.copy(initializationVector = tamperedInitializationVector),
                requestId,
                action,
            )
        }
        val tamperedCiphertext =
            encryptedReply.ciphertext.copyOf().also { bytes ->
                bytes[0] = (bytes[0].toInt() xor 1).toByte()
            }
        assertThrows(GeneralSecurityException::class.java) {
            testReplyCipher().decrypt(encryptedReply.copy(ciphertext = tamperedCiphertext), requestId, action)
        }
        val tamperedActions =
            listOf(
                action.copy(kind = NotificationActionKind.MARK_READ),
                action.copy(target = action.target.copy(accountRef = "acct-b")),
                action.copy(target = action.target.copy(groupIdHex = "group-2")),
                action.copy(target = action.target.copy(messageIdHex = "msg-2")),
                action.copy(target = action.target.copy(kind = NotificationTargetKind.INVITE)),
                action.copy(notificationTag = "acct-a|group-2"),
                action.copy(notificationId = 8),
            )
        tamperedActions.forEach { tamperedAction ->
            assertThrows(GeneralSecurityException::class.java) {
                testReplyCipher().decrypt(encryptedReply, requestId, tamperedAction)
            }
        }
    }

    @Test
    fun replyWorkerInput_supportsLegacyPlaintextWithoutRetryingIt() {
        val reply = "already queued legacy reply"
        val legacyInput =
            workDataOf(
                "action" to NotificationActions.ACTION_REPLY,
                "account_ref" to "acct-a",
                "group_id_hex" to "group-1",
                "message_id_hex" to "msg-1",
                "target_kind" to NotificationTargetKind.MESSAGE.name,
                "notification_tag" to "acct-a|group-1",
                "notification_id" to 0,
                "reply" to reply,
            )

        assertEquals(
            NotificationReplyInput.LegacyPlaintext(reply),
            NotificationReplyWorker.notificationReplyFromInput(legacyInput),
        )
        assertFalse(NotificationReplyWorker.shouldRetryAfterFailure(0, containsLegacyPlaintext = true))
        assertFalse(NotificationReplyWorker.shouldRetryAfterFailure(1, containsLegacyPlaintext = true))
        assertFalse(NotificationReplyWorker.shouldRetryAfterFailure(2, containsLegacyPlaintext = true))
    }

    @Test
    fun replyWorkerCryptoFailureRetries_onlyPotentiallyTransientFailures() {
        val transientFailure = KeyStoreException("temporarily unavailable")

        assertTrue(NotificationReplyWorker.shouldRetryAfterCryptoFailure(transientFailure, operationFailureAttempt = 0))
        assertTrue(NotificationReplyWorker.shouldRetryAfterCryptoFailure(transientFailure, operationFailureAttempt = 1))
        assertFalse(NotificationReplyWorker.shouldRetryAfterCryptoFailure(transientFailure, operationFailureAttempt = 2))
        assertFalse(
            NotificationReplyWorker.shouldRetryAfterCryptoFailure(
                AEADBadTagException("metadata or ciphertext was tampered"),
                operationFailureAttempt = 0,
            ),
        )
        assertFalse(
            NotificationReplyWorker.shouldRetryAfterCryptoFailure(
                IllegalArgumentException("malformed encrypted input"),
                operationFailureAttempt = 0,
            ),
        )
        assertSame(
            NotificationReplyInput.Malformed,
            NotificationReplyWorker.notificationReplyFromInput(workDataOf("reply_iv" to ByteArray(12))),
        )
        assertSame(
            NotificationReplyInput.Malformed,
            NotificationReplyWorker.notificationReplyFromInput(
                workDataOf(
                    "reply" to "downgrade attempt",
                    "reply_iv" to ByteArray(12),
                    "reply_ciphertext" to ByteArray(16),
                ),
            ),
        )
    }

    @Test
    fun replyWorkerFailureRetries_areBounded() {
        assertTrue(NotificationReplyWorker.shouldRetryAfterFailure(runAttemptCount = 0))
        assertTrue(NotificationReplyWorker.shouldRetryAfterFailure(runAttemptCount = 1))
        assertFalse(NotificationReplyWorker.shouldRetryAfterFailure(runAttemptCount = 2))
        assertFalse(NotificationReplyWorker.shouldRetryAfterFailure(runAttemptCount = 3))
    }

    @Test
    fun replyWorkerRetriesOnlyRetryableSendFailures() {
        assertTrue(
            NotificationReplyWorker.shouldRetryAfterSendOutcome(
                NotificationReplySendOutcome.RetryableFailure,
                operationFailureAttempt = 0,
            ),
        )
        assertFalse(
            NotificationReplyWorker.shouldRetryAfterSendOutcome(
                NotificationReplySendOutcome.NonRetryableFailure,
                operationFailureAttempt = 0,
            ),
        )
        assertFalse(
            NotificationReplyWorker.shouldRetryAfterSendOutcome(
                NotificationReplySendOutcome.RetryableFailure,
                operationFailureAttempt = 2,
            ),
        )
    }

    @Test
    fun replyWorkerMapsSendOutcomesToWorkResults() {
        assertEquals(
            ListenableWorker.Result.success(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.Sent,
                operationFailureAttempt = 0,
            ),
        )
        assertEquals(
            ListenableWorker.Result.success(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.AlreadyCommitted,
                operationFailureAttempt = 0,
            ),
        )
        assertEquals(
            ListenableWorker.Result.success(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.AcceptedPending,
                operationFailureAttempt = 0,
            ),
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.NonRetryableFailure,
                operationFailureAttempt = 0,
            ),
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.RetryableFailure,
                operationFailureAttempt = 0,
            ),
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.RetryableFailure,
                operationFailureAttempt = 0,
                containsLegacyPlaintext = true,
            ),
        )
        assertEquals(
            ListenableWorker.Result.failure(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.RetryableFailure,
                operationFailureAttempt = 2,
            ),
        )
        // Unpersisted attempt count (null) must fail closed, not retry unbounded.
        assertEquals(
            ListenableWorker.Result.failure(),
            NotificationReplyWorker.resultAfterSendOutcome(
                NotificationReplySendOutcome.RetryableFailure,
                operationFailureAttempt = null,
            ),
        )
    }

    @Test
    fun replyWorkerRequest_usesExplicitExponentialBackoff() {
        val action =
            NotificationAction(
                kind = NotificationActionKind.REPLY,
                target = NotificationTarget("acct-a", "group-1", "msg-1", NotificationTargetKind.MESSAGE),
                notificationTag = "acct-a|group-1",
                notificationId = 0,
            )

        val requestId = UUID.randomUUID()
        val encryptedReply = testReplyCipher().encrypt("hello", requestId, action)
        val request = NotificationReplyWorker.notificationReplyRequest(action, requestId, encryptedReply)

        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
    }

    private fun testReplyCipher(): NotificationReplyCipher = NotificationReplyCipher(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))

    // ---- resolveNotificationNav (routing FSM) -------------------------------

    private val target = NotificationTarget("acct-a", "group-1", null, NotificationTargetKind.MESSAGE)

    @Test
    fun nav_unknownAccount_isMissingAccount() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.MissingAccount, step)
    }

    @Test
    fun nav_backgroundAccount_switchesFirst() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a", "acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.SwitchAccount("acct-a"), step)
    }

    @Test
    fun nav_backgroundAccountWithReadyPreload_opensDirectlyWhileSwitchSettles() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a", "acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = false,
                availableGroupIds = emptySet(),
                exactPreloadReady = true,
            )
        assertEquals(NotificationNavStep.LoadMessageDirectly, step)
    }

    @Test
    fun nav_backgroundAccountWithoutReadyPreload_keepsSwitchingFirst() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a", "acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = false,
                availableGroupIds = emptySet(),
                exactPreloadReady = false,
            )
        assertEquals(NotificationNavStep.SwitchAccount("acct-a"), step)
    }

    @Test
    fun nav_inviteOnBackgroundAccountIgnoresReadyPreload() {
        val step =
            resolveNotificationNav(
                target.copy(kind = NotificationTargetKind.INVITE),
                knownAccountRefs = setOf("acct-a", "acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = false,
                availableGroupIds = emptySet(),
                exactPreloadReady = true,
            )
        assertEquals(NotificationNavStep.SwitchAccount("acct-a"), step)
    }

    @Test
    fun nav_unknownAccountOutranksReadyPreload() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-b"),
                activeAccountRef = "acct-b",
                chatListReady = false,
                availableGroupIds = emptySet(),
                exactPreloadReady = true,
            )
        assertEquals(NotificationNavStep.MissingAccount, step)
    }

    @Test
    fun nav_activeAccount_doesNotSwitch_andOpensWhenReady() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.OpenConversation("group-1", null), step)
    }

    @Test
    fun nav_messageNotificationOpen_carriesReadThroughCursor() {
        val messageIdHex = "a".repeat(64)
        val step =
            resolveNotificationNav(
                target.copy(messageIdHex = messageIdHex),
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
            )
        assertEquals(NotificationNavStep.OpenConversation("group-1", messageIdHex), step)
    }

    @Test
    fun nav_messageOnActiveAccountLoadsDirectlyBeforeChatListIsReady() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = false,
                availableGroupIds = emptySet(),
            )
        assertEquals(NotificationNavStep.LoadMessageDirectly, step)
    }

    @Test
    fun nav_inviteOnActiveAccountStillAwaitsChatList() {
        val step =
            resolveNotificationNav(
                target.copy(kind = NotificationTargetKind.INVITE),
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = false,
                availableGroupIds = emptySet(),
            )
        assertEquals(NotificationNavStep.AwaitChatList, step)
    }

    @Test
    fun directMessageLoadOpensAuthoritativeItemWithoutWaitingForChatList() =
        runTest {
            assertEquals(
                NotificationMessageDirectLoadOutcome.OpenConversation("local conversation"),
                loadNotificationMessageDirectly { "local conversation" },
            )
        }

    @Test
    fun directMessageLoadFailureKeepsTapPendingForChatListFallback() =
        runTest {
            assertEquals(
                NotificationMessageDirectLoadOutcome.AwaitChatList,
                loadNotificationMessageDirectly<String> { error("sqlite busy") },
            )
        }

    @Test
    fun failedInactivePreloadRetriesOnceAfterAccountActivation() {
        assertTrue(
            shouldRetryNotificationMessageLoadAfterActivation(
                preloadState = NotificationMessagePreloadState.Failed,
                routingRequestId = 72L,
                retriedRequestId = null,
            ),
        )
        assertFalse(
            shouldRetryNotificationMessageLoadAfterActivation(
                preloadState = NotificationMessagePreloadState.Failed,
                routingRequestId = 72L,
                retriedRequestId = 72L,
            ),
        )
        assertFalse(
            shouldRetryNotificationMessageLoadAfterActivation(
                preloadState = NotificationMessagePreloadState.Loading,
                routingRequestId = 72L,
                retriedRequestId = null,
            ),
        )
    }

    @Test(expected = CancellationException::class)
    fun directMessageLoadPropagatesCancellation() =
        runTest {
            loadNotificationMessageDirectly<String> { throw CancellationException("recomposed") }
        }

    @Test
    fun messagePreloadKeyIncludesRequestIdentity() {
        val first = notificationMessagePreloadKey(target, requestId = 41L)
        val repeated = notificationMessagePreloadKey(target, requestId = 42L)

        assertNotEquals(first, repeated)
        assertEquals("acct-a", first?.accountRef)
        assertEquals("group-1", first?.groupIdHex)
    }

    @Test
    fun inviteDoesNotStartMessagePreload() {
        assertNull(
            notificationMessagePreloadKey(
                target.copy(kind = NotificationTargetKind.INVITE),
                requestId = 41L,
            ),
        )
    }

    @Test
    fun completedPreloadIsVisibleOnlyToExactRequest() {
        val firstKey = requireNotNull(notificationMessagePreloadKey(target, requestId = 41L))
        val repeatedKey = requireNotNull(notificationMessagePreloadKey(target, requestId = 42L))
        val preload =
            NotificationMessagePreload(
                key = firstKey,
                state = NotificationMessagePreloadState.Ready("first request item"),
            )

        assertEquals(
            NotificationMessagePreloadState.Ready("first request item"),
            preload.stateFor(firstKey),
        )
        assertNull(preload.stateFor(repeatedKey))
    }

    @Test
    fun inactiveMessageRoute_warmReadStartsBeforeActivationAndRunsAlongsideIt() =
        runTest {
            val key = requireNotNull(notificationMessagePreloadKey(target, requestId = 51L))
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val published = mutableListOf<NotificationMessagePreload<String>>()

            runInactiveNotificationRouteStage(
                key = key,
                loadTarget = {
                    readStarted.complete(Unit)
                    releaseRead.await()
                    "warm conversation"
                },
                activateAccount = {
                    assertTrue("target read must enter before activation", readStarted.isCompleted)
                    releaseRead.complete(Unit)
                },
                isCurrent = { true },
                onPreload = { published += it },
            )

            assertEquals(
                listOf(
                    NotificationMessagePreload(
                        key = key,
                        state = NotificationMessagePreloadState.Ready("warm conversation"),
                    ),
                ),
                published,
            )
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun inactiveMessageRoute_readyReadPublishesBeforeActivationCompletes() =
        runTest {
            val key = requireNotNull(notificationMessagePreloadKey(target, requestId = 58L))
            val releaseActivation = CompletableDeferred<Unit>()
            var publishedWhileActivationHeld = false
            var activationFinished = false

            val stage =
                launch {
                    runInactiveNotificationRouteStage(
                        key = key,
                        loadTarget = { "instant conversation" },
                        activateAccount = {
                            releaseActivation.await()
                            activationFinished = true
                        },
                        isCurrent = { true },
                        onPreload = {
                            publishedWhileActivationHeld = !activationFinished
                        },
                    )
                }
            runCurrent()

            assertTrue(
                "a ready local read must publish while activation is still in flight",
                publishedWhileActivationHeld,
            )
            assertFalse("the stage must still await activation", stage.isCompleted)
            releaseActivation.complete(Unit)
            runCurrent()
            assertTrue(activationFinished)
            assertTrue(stage.isCompleted)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun accountActivationBoundaryDoesNotWaitForSlowerFollowUpWork() =
        runTest {
            val releaseFollowUp = CompletableDeferred<Unit>()
            var followUpFinished = false

            awaitNotificationAccountActivationBoundary { onLocalReady ->
                backgroundScope.launch {
                    onLocalReady()
                    releaseFollowUp.await()
                    followUpFinished = true
                }
            }

            assertFalse(followUpFinished)
            releaseFollowUp.complete(Unit)
            runCurrent()
            assertTrue(followUpFinished)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun notificationFirstFrameGateDefersBroadWorkUntilReadableFrame() =
        runTest {
            val gate = NotificationRouteFirstFrameGate(requestId = 71L, accountRef = "acct-b")
            var broadWorkStarted = false
            val broadWork =
                launch {
                    gate.awaitRelease()
                    broadWorkStarted = true
                }

            runCurrent()
            assertFalse("broad account work must stay outside the target-frame path", broadWorkStarted)
            assertTrue(shouldDeferNotificationChatListBind(gate, activeAccountRef = "acct-b"))
            assertFalse(shouldDeferNotificationChatListBind(gate, activeAccountRef = "acct-a"))

            gate.release()
            gate.release()
            runCurrent()

            assertTrue(broadWorkStarted)
            assertTrue(broadWork.isCompleted)
            assertFalse(shouldDeferNotificationChatListBind(gate, activeAccountRef = "acct-b"))
        }

    @Test
    fun exactPreloadPreventsBroadListAbsenceFromWinningTheRoute() {
        assertFalse(
            notificationMessageRouteChatListReady(
                chatListReady = true,
                targetPresent = false,
                preloadState = NotificationMessagePreloadState.Loading,
            ),
        )
        assertFalse(
            notificationMessageRouteChatListReady(
                chatListReady = true,
                targetPresent = false,
                preloadState = NotificationMessagePreloadState.Ready("target"),
            ),
        )
        assertTrue(
            notificationMessageRouteChatListReady(
                chatListReady = true,
                targetPresent = false,
                preloadState = NotificationMessagePreloadState.Failed,
            ),
        )
        assertTrue(
            notificationMessageRouteChatListReady(
                chatListReady = true,
                targetPresent = true,
                preloadState = NotificationMessagePreloadState.Loading,
            ),
        )
    }

    @Test
    fun inactiveMessageRoute_coldFixturePublishesOnlyFreshRequest() =
        runTest {
            val key = requireNotNull(notificationMessagePreloadKey(target, requestId = 52L))
            var result: NotificationMessagePreload<String>? = null

            runInactiveNotificationRouteStage(
                key = key,
                loadTarget = { "cold conversation" },
                activateAccount = {},
                isCurrent = { true },
                onPreload = { result = it },
            )

            assertEquals(NotificationMessagePreloadState.Ready("cold conversation"), result.stateFor(key))
        }

    @Test
    fun inactiveMessageRoute_processRecreationRejectsPriorRuntimeCompletion() =
        runTest {
            val priorRuntimeKey = requireNotNull(notificationMessagePreloadKey(target, requestId = 53L))
            val recreatedRuntimeKey = requireNotNull(notificationMessagePreloadKey(target, requestId = 54L))
            var priorRuntime: NotificationMessagePreload<String>? = null
            var recreatedRuntime: NotificationMessagePreload<String>? = null

            runInactiveNotificationRouteStage(
                key = priorRuntimeKey,
                loadTarget = { "prior runtime" },
                activateAccount = {},
                isCurrent = { false },
                onPreload = { priorRuntime = it },
            )
            runInactiveNotificationRouteStage(
                key = recreatedRuntimeKey,
                loadTarget = { "recreated runtime" },
                activateAccount = {},
                isCurrent = { true },
                onPreload = { recreatedRuntime = it },
            )

            assertNull(priorRuntime)
            assertEquals(
                NotificationMessagePreloadState.Ready("recreated runtime"),
                recreatedRuntime.stateFor(recreatedRuntimeKey),
            )
        }

    @Test
    fun inactiveMessageRoute_repeatedTapRejectsOlderSameTargetRequest() =
        runTest {
            val oldKey = requireNotNull(notificationMessagePreloadKey(target, requestId = 55L))
            val repeatedKey = requireNotNull(notificationMessagePreloadKey(target, requestId = 56L))
            var old: NotificationMessagePreload<String>? = null
            var repeated: NotificationMessagePreload<String>? = null

            runInactiveNotificationRouteStage(
                key = oldKey,
                loadTarget = { "old item" },
                activateAccount = {},
                isCurrent = { false },
                onPreload = { old = it },
            )
            runInactiveNotificationRouteStage(
                key = repeatedKey,
                loadTarget = { "repeated item" },
                activateAccount = {},
                isCurrent = { true },
                onPreload = { repeated = it },
            )

            assertNull(old)
            assertEquals(NotificationMessagePreloadState.Ready("repeated item"), repeated.stateFor(repeatedKey))
        }

    @Test
    fun inactiveMessageRoute_cancelledStageNeverPublishesAndIsReleasedAsStuckLoading() =
        runTest {
            val key = requireNotNull(notificationMessagePreloadKey(target, requestId = 59L))
            var published: NotificationMessagePreload<String>? = null
            // The shell publishes Loading synchronously before the stage runs.
            var shellState: NotificationMessagePreload<String>? =
                NotificationMessagePreload(key = key, state = NotificationMessagePreloadState.Loading)

            val stageFailure =
                runCatching {
                    runInactiveNotificationRouteStage<String>(
                        key = key,
                        loadTarget = {
                            kotlinx.coroutines.suspendCancellableCoroutine { it.cancel() }
                        },
                        activateAccount = {},
                        isCurrent = { true },
                        onPreload = { published = it },
                    )
                }.exceptionOrNull()

            assertTrue(
                "a cancelled stage must fail rather than publish",
                stageFailure is CancellationException,
            )
            assertNull(published)
            // The caller's finally must detect the never-terminalized Loading
            // state and release it, or the route pins the loading screen while
            // the request-id dedupe guard blocks a retry of the same tap.
            assertTrue(notificationPreloadStuckLoading(shellState, key))
            shellState = null
            assertFalse(notificationPreloadStuckLoading(shellState, key))
        }

    @Test
    fun stuckLoadingDetectionIgnoresTerminalStatesAndForeignRequests() {
        val key = requireNotNull(notificationMessagePreloadKey(target, requestId = 60L))
        val foreignKey = requireNotNull(notificationMessagePreloadKey(target, requestId = 61L))

        assertFalse(
            notificationPreloadStuckLoading(
                NotificationMessagePreload(key = key, state = NotificationMessagePreloadState.Ready("item")),
                key,
            ),
        )
        assertFalse(
            notificationPreloadStuckLoading(
                NotificationMessagePreload(key = key, state = NotificationMessagePreloadState.Failed),
                key,
            ),
        )
        assertFalse(
            notificationPreloadStuckLoading(
                NotificationMessagePreload(key = foreignKey, state = NotificationMessagePreloadState.Loading),
                key,
            ),
        )
        assertFalse(notificationPreloadStuckLoading(null, key))
    }

    @Test
    fun inactiveMessageRoute_missingTargetFallsBackToBroadChatList() =
        runTest {
            val key = requireNotNull(notificationMessagePreloadKey(target, requestId = 57L))
            var result: NotificationMessagePreload<String>? = null

            runInactiveNotificationRouteStage<String>(
                key = key,
                loadTarget = { error("missing local group") },
                activateAccount = {},
                isCurrent = { true },
                onPreload = { result = it },
            )

            assertEquals(NotificationMessagePreloadState.Failed, result.stateFor(key))
        }

    @Test
    fun nav_readyButGroupAbsent_isMissingConversation() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-other"),
            )
        assertEquals(NotificationNavStep.MissingConversation, step)
    }

    @Test
    fun nav_readyButInviteAbsent_awaitsInviteRow() {
        val inviteTarget = NotificationTarget("acct-a", "group-1", null, NotificationTargetKind.INVITE)
        val step =
            resolveNotificationNav(
                inviteTarget,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-other"),
                inviteRowMaterialized = false,
                inviteAuthoritativelyUnavailable = false,
            )
        assertEquals(NotificationNavStep.AwaitInviteRow, step)
    }

    @Test
    fun nav_inviteAbsentAfterReady_materializesLater() {
        val inviteTarget = NotificationTarget("acct-a", "group-1", null, NotificationTargetKind.INVITE)
        assertEquals(
            NotificationNavStep.AwaitInviteRow,
            resolveNotificationNav(
                inviteTarget,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = emptySet(),
                inviteRowMaterialized = false,
                inviteAuthoritativelyUnavailable = false,
            ),
        )
        assertEquals(
            NotificationNavStep.OpenConversation("group-1", null),
            resolveNotificationNav(
                inviteTarget,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
                inviteRowMaterialized = true,
                inviteAuthoritativelyUnavailable = false,
            ),
        )
    }

    @Test
    fun inviteAuthoritativeLoadClassification_treatsUnknownGroupAsTransientDuringProbe() {
        assertEquals(
            NotificationInviteAuthoritativeOutcome.Inconclusive,
            classifyInviteAuthoritativeLoad(
                Result.failure(MarmotKitException.UnknownGroup("group-1")),
            ),
        )
        assertEquals(
            NotificationInviteAuthoritativeOutcome.Inconclusive,
            classifyInviteAuthoritativeLoad(
                Result.failure(MarmotKitException.Runtime("sqlite busy")),
            ),
        )
    }

    @Test
    fun inviteAuthoritativeGroupAvailability_distinguishesAcceptedFromDeclinedInvite() {
        assertTrue(
            inviteAuthoritativeGroupAvailable(
                pendingConfirmation = false,
                selfMembership = SelfMembershipFfi.MEMBER,
            ),
        )
        assertFalse(
            inviteAuthoritativeGroupAvailable(
                pendingConfirmation = false,
                selfMembership = SelfMembershipFfi.LEFT,
            ),
        )
        assertFalse(
            inviteAuthoritativeGroupAvailable(
                pendingConfirmation = false,
                selfMembership = SelfMembershipFfi.REMOVED,
            ),
        )
        assertTrue(
            inviteAuthoritativeGroupAvailable(
                pendingConfirmation = true,
                selfMembership = SelfMembershipFfi.MEMBER,
            ),
        )
    }

    @Test
    fun inviteAuthoritativeLoadClassification_distinguishesAvailableFromUnavailableGroup() {
        assertEquals(
            NotificationInviteAuthoritativeOutcome.OpenConversation,
            classifyInviteAuthoritativeLoad(Result.success(true)),
        )
        assertEquals(
            NotificationInviteAuthoritativeOutcome.Unavailable,
            classifyInviteAuthoritativeLoad(Result.success(false)),
        )
    }

    @Test
    fun nav_inviteAuthoritativelyUnavailable_isMissingConversation() {
        val inviteTarget = NotificationTarget("acct-a", "group-1", null, NotificationTargetKind.INVITE)
        val step =
            resolveNotificationNav(
                inviteTarget,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = emptySet(),
                inviteRowMaterialized = false,
                inviteAuthoritativelyUnavailable = true,
            )
        assertEquals(NotificationNavStep.MissingConversation, step)
    }

    @Test
    fun nav_inviteAuthoritativelyUnavailable_dominatesStalePresentRow() {
        val inviteTarget = NotificationTarget("acct-a", "group-1", null, NotificationTargetKind.INVITE)
        val step =
            resolveNotificationNav(
                inviteTarget,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
                inviteRowMaterialized = true,
                inviteRowMembershipOpenable = true,
                inviteAuthoritativelyUnavailable = true,
            )
        assertEquals(NotificationNavStep.MissingConversation, step)
    }

    @Test
    fun inviteAuthoritativeGroupAvailability_terminalMembershipDominatesStalePendingConfirmation() {
        assertFalse(
            inviteAuthoritativeGroupAvailable(
                pendingConfirmation = true,
                selfMembership = SelfMembershipFfi.LEFT,
            ),
        )
        assertFalse(
            inviteAuthoritativeGroupAvailable(
                pendingConfirmation = true,
                selfMembership = SelfMembershipFfi.REMOVED,
            ),
        )
    }

    @Test
    fun nav_invitePresentRow_terminalMembership_isMissingConversation_notOpen() {
        val inviteTarget = NotificationTarget("acct-a", "group-1", null, NotificationTargetKind.INVITE)
        assertEquals(
            NotificationNavStep.MissingConversation,
            resolveNotificationNav(
                inviteTarget,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-1"),
                inviteRowMaterialized = true,
                inviteRowMembershipOpenable = false,
            ),
        )
    }

    @Test
    fun nav_messageAbsentGroup_unchangedWhenInviteMembershipFlagFalse() {
        val step =
            resolveNotificationNav(
                target,
                knownAccountRefs = setOf("acct-a"),
                activeAccountRef = "acct-a",
                chatListReady = true,
                availableGroupIds = setOf("group-other"),
                inviteRowMembershipOpenable = false,
            )
        assertEquals(NotificationNavStep.MissingConversation, step)
    }

    @Test
    fun inviteAuthoritativeProbeRetry_allowsBoundedInconclusiveAttempts() {
        assertTrue(inviteAuthoritativeProbeShouldRetry(probeAttempts = 0))
        assertTrue(inviteAuthoritativeProbeShouldRetry(probeAttempts = 2))
        assertFalse(inviteAuthoritativeProbeShouldRetry(probeAttempts = 3))
    }

    @Test
    fun inviteAuthoritativeProbe_transientFailureThenSuccess_retriesAndOpens() =
        runTest {
            val results =
                ArrayDeque(
                    listOf(
                        Result.failure<Boolean>(MarmotKitException.Runtime("busy")),
                        Result.success(true),
                    ),
                )
            val delays = mutableListOf<Long>()

            assertEquals(
                NotificationInviteAuthoritativeOutcome.OpenConversation,
                retryInviteAuthoritativeLoad(
                    load = { results.removeFirst() },
                    sleep = delays::add,
                ),
            )
            assertEquals(listOf(250L), delays)
            assertTrue(results.isEmpty())
        }

    @Test
    fun inviteAuthoritativeProbe_transientFailureThenAuthoritativeAbsence_retriesAndStops() =
        runTest {
            val results =
                ArrayDeque(
                    listOf(
                        Result.failure<Boolean>(MarmotKitException.Runtime("busy")),
                        Result.success(false),
                    ),
                )

            assertEquals(
                NotificationInviteAuthoritativeOutcome.Unavailable,
                retryInviteAuthoritativeLoad(
                    load = { results.removeFirst() },
                    sleep = {},
                ),
            )
            assertTrue(results.isEmpty())
        }

    @Test
    fun inviteAuthoritativeProbe_exhaustedTransientFailuresRemainInconclusive() =
        runTest {
            var calls = 0
            val delays = mutableListOf<Long>()

            assertEquals(
                NotificationInviteAuthoritativeOutcome.Inconclusive,
                retryInviteAuthoritativeLoad(
                    load = {
                        calls += 1
                        Result.failure(MarmotKitException.Runtime("busy"))
                    },
                    sleep = delays::add,
                ),
            )
            assertEquals(3, calls)
            assertEquals(listOf(250L, 500L), delays)
        }

    @Test
    fun inviteAuthoritativeProbe_unknownGroupThenSuccess_retriesAndOpens() =
        runTest {
            val results =
                ArrayDeque(
                    listOf(
                        Result.failure<Boolean>(MarmotKitException.UnknownGroup("group-1")),
                        Result.success(true),
                    ),
                )
            val delays = mutableListOf<Long>()

            assertEquals(
                NotificationInviteAuthoritativeOutcome.OpenConversation,
                retryInviteAuthoritativeLoad(
                    load = { results.removeFirst() },
                    sleep = delays::add,
                ),
            )
            assertEquals(listOf(250L), delays)
            assertTrue(results.isEmpty())
        }

    @Test
    fun inviteAuthoritativeProbe_exhaustedUnknownGroupRemainsInconclusive() =
        runTest {
            var calls = 0

            assertEquals(
                NotificationInviteAuthoritativeOutcome.Inconclusive,
                retryInviteAuthoritativeLoad(
                    load = {
                        calls += 1
                        Result.failure(MarmotKitException.UnknownGroup("group-1"))
                    },
                    sleep = {},
                ),
            )
            assertEquals(3, calls)
        }

    @Test
    fun inviteAuthoritativeProbe_persistedBudgetSurvivesCancellationDuringBackoff() =
        runTest {
            var persistedAttempts = 0
            var loadCalls = 0
            val transientFailure = Result.failure<Boolean>(MarmotKitException.Runtime("busy"))
            var cancelled = false

            try {
                retryInviteAuthoritativeLoad(
                    probeAttempts = persistedAttempts,
                    onProbeAttempt = { persistedAttempts = it },
                    load = {
                        loadCalls += 1
                        transientFailure
                    },
                    sleep = { throw kotlinx.coroutines.CancellationException("effect restarted") },
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertEquals(1, persistedAttempts)
            assertEquals(1, loadCalls)

            loadCalls = 0
            assertEquals(
                NotificationInviteAuthoritativeOutcome.Inconclusive,
                retryInviteAuthoritativeLoad(
                    probeAttempts = persistedAttempts,
                    onProbeAttempt = { persistedAttempts = it },
                    load = {
                        loadCalls += 1
                        transientFailure
                    },
                    sleep = {},
                ),
            )
            assertEquals(2, loadCalls)
            assertEquals(3, persistedAttempts)
        }

    // ---- helpers ------------------------------------------------------------

    private fun update(
        accountRef: String = "acct-a",
        groupIdHex: String = "group-1",
        messageId: String? = "m1",
        trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
    ): NotificationUpdateFfi =
        NotificationUpdateFfi(
            isMention = false,
            notificationKey = "key-1",
            conversationKey = "conv-1",
            trigger = trigger,
            trafficClass = dev.ipf.marmotkit.NotificationTrafficClassFfi.STANDARD,
            accountRef = accountRef,
            accountIdHex = "acctid-a",
            groupIdHex = groupIdHex,
            groupName = "Group",
            isDm = false,
            messageIdHex = messageId,
            sender = NotificationUserFfi("sender-hex", "Sender", null),
            receiver = NotificationUserFfi("receiver-hex", "Me", null),
            previewText = "hi",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 0L,
            isFromSelf = false,
        )
}
