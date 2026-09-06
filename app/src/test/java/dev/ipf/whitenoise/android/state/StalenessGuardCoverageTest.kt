package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StalenessGuardCoverageTest {
    /** Keeps the audited atomic guard as the only owner of latest-wins counters. */
    @Test
    fun latestWinsStateUsesTheSharedPrimitive() {
        val sources = productionSources()
        val joined = sources.values.joinToString("\n")

        listOf(
            "NotificationPostEpoch",
            "GroupRosterRefreshGeneration",
            "StartupUnreadRevisionGuard",
        ).forEach { legacyType ->
            assertFalse("legacy staleness primitive must stay deleted: $legacyType", legacyType in joined)
        }

        val migratedOwners =
            mapOf(
                "AppState.kt" to
                    listOf(
                        "accountListLifetime",
                        "profileCacheLifetime",
                        "mediaUploadSessionLifetime",
                        "notificationRuntimeRecovery",
                        "notificationPostEpoch",
                    ),
                "MessageForwarding.kt" to listOf("dismissals"),
                "Controllers.kt" to
                    listOf(
                        "bindLifetime",
                        "memberCacheLifetime",
                        "memberRosterRefreshGeneration",
                        "timelineWindowGeneration",
                        "managementStateLifetime",
                    ),
                "AccountUnreadStore.kt" to listOf("refreshes"),
                "AttachmentOpenCoordinator.kt" to listOf("openRequests"),
                "AttachmentTransferCoordinator.kt" to listOf("terminalLifetimes", "refreshLifetimes"),
                "ConversationInitialPresentationWarm.kt" to listOf("preparations"),
                "ConversationCardPostSynchronizer.kt" to listOf("dismissals", "shows"),
                "TtsController.kt" to listOf("engineQueueLifetime"),
                "MessageDraftRepository.kt" to listOf("lifetimes"),
            )
        migratedOwners.forEach { (fileName, owners) ->
            val source = sources.getValue(fileName)
            owners.forEach { owner ->
                assertTrue("$fileName must route $owner through StalenessGuard", owner in source)
            }
        }

        assertTrue(
            "the guard must state its one atomic concurrency posture",
            "AtomicLong" in sources.getValue("StalenessGuard.kt") &&
                "publicationLock" in sources.getValue("StalenessGuard.kt"),
        )
    }

    /** Pins every audited suspend-then-publish boundary to a guard or a reasoned exemption. */
    @Suppress("LongMethod") // Keep guarded paths beside their complete per-entry exemption audit.
    @Test
    fun awaitThenPublishPathsAreGuardedOrExplicitlyExempt() {
        val appState = productionSource("AppState.kt")
        val controllers = productionSource("Controllers.kt")
        val guardedPaths =
            mapOf(
                "AppState.kt:refreshAccountUnreadCounts" to
                    listOf("accountListLifetime.capture", "accountListLifetime.isCurrent"),
                "AppState.kt:refreshAccountSnapshot" to
                    listOf("accountListLifetime.advance", "accountListLifetime.runIfCurrent"),
                "AppState.kt:recordStartupLocalSnapshotRendered" to
                    listOf("accountListLifetime.isCurrent", "stillCurrent = accountListIsCurrent"),
                "AppState.kt:refreshProfile" to
                    listOf("profileCacheLifetime.capture", "profileCacheLifetime.isCurrent"),
                "AppState.kt:materializeProfileLocally" to
                    listOf("profileCacheLifetime.capture", "profileCacheLifetime.isCurrent"),
                "AppState.kt:processNotificationUpdate" to
                    listOf("notificationPostEpoch.capture", "epoch = postEpoch", "postInitialNotificationUpdate"),
                "Controllers.kt:bind" to listOf("bindLifetime.advance", "bindEpoch"),
                "Controllers.kt:schedulePendingMemberFetches" to
                    listOf("bindEpoch", "memberCacheEpoch", "memberCacheLifetime.isCurrent"),
                "Controllers.kt:applyFetchedMemberSnapshot" to
                    listOf("isActiveBindEpoch(epoch)", "memberCacheLifetime.isCurrent"),
                "Controllers.kt:resolveDirectChatGroup" to
                    listOf("accountStillBound", "isActiveBindEpoch(epoch)"),
                "Controllers.kt:refreshCurrentTimeline" to
                    listOf("timelineWindowGeneration.advance", "timelineWindowGeneration.isCurrent"),
                "Controllers.kt:beginMemberRosterRefresh" to
                    listOf(
                        "accountTeardownRequested",
                        "controllerCleared",
                        "memberRosterRefreshGeneration.advance",
                    ),
                "Controllers.kt:refreshMembers" to
                    listOf(
                        "beginMemberRosterRefresh",
                        "memberRosterRefreshGeneration.runIfCurrent",
                        "memberRosterRefreshGeneration.isCurrent",
                    ),
                "Controllers.kt:performMediaUpload" to
                    listOf("shouldAcceptMediaUploadForAccount", "mediaUploadSessionEpoch"),
                "Controllers.kt:refreshManagementState" to
                    listOf("managementStateLifetime.advance", "managementStateLifetime.runIfCurrent"),
            )
        guardedPaths.forEach { (path, markers) ->
            val (fileName, functionName) = path.split(':', limit = 2)
            val source = if (fileName == "AppState.kt") appState else controllers
            val body = source.functionSection(functionName)
            markers.forEach { marker ->
                assertTrue("$path must retain $marker", marker in body)
            }
        }

        val exemptions =
            mapOf(
                // NotificationJobSlot coalesces every canceller behind one monitor-owned completion.
                "AppState.kt:cancelAndJoin" to "monitor-owned single cancellation completion",
                // BootstrapAttemptCoordinator admits only one process-owned attempt.
                "AppState.kt:bootstrap" to "single-flight bootstrap coordinator",
                // Explicit retry only changes presentation, then delegates to the same single-flight coordinator.
                "AppState.kt:retryBootstrap" to "user-owned presentation transition before single-flight bootstrap",
                // bootstrapLocked is called only by the coordinator's sole attempt.
                "AppState.kt:bootstrapLocked" to "single-flight bootstrap body",
                // BootstrapRuntimeCoordinator serializes construct/configure/start and terminal cleanup.
                "AppState.kt:startBootstrapRuntime" to "mutex-owned runtime lifecycle",
                // Identity creation is an accepted engine mutation, not a replaceable cache read.
                "AppState.kt:createIdentity" to "authoritative identity command result",
                // One external-signer login completion creates and activates its own account.
                "AppState.kt:loginWithAmber" to "authoritative identity command result",
                // Durable per-account clears are idempotent and fenced against later contact writes.
                "AppState.kt:clearContactPrivateDetailsForAccount" to "idempotent account-private clear",
                // Sign-out completion is an accepted destructive command whose engine result is authoritative.
                "AppState.kt:signOutActiveAccount" to "authoritative destructive command result",
                // The wipe owns a cancellation-safe lifecycle bracket and serialized native-push teardown.
                "AppState.kt:signOutAndWipeActiveAccount" to "serialized destructive lifecycle",
                // Existing settings snapshot refresh is outside the audited stale-counter migration inventory.
                "AppState.kt:refreshSecurityPrivacySettings" to "pre-existing settings snapshot path",
                // Telemetry toggle completion is an authoritative engine command, not a replaceable cache read.
                "AppState.kt:setTelemetryEnabled" to "authoritative settings command result",
                // Setter completions are authoritative engine commands; every accepted toggle must apply.
                "AppState.kt:setLocalNotificationsEnabled" to "authoritative settings command result",
                // Foreground-service and preference side effects belong to every accepted toggle.
                "AppState.kt:setBackgroundConnectionEnabled" to "authoritative settings command result",
                // The caller holds nativePushSyncMutex and re-reads the runtime setting before caching.
                "AppState.kt:syncPushForAccount" to "serialized sync with authoritative re-read",
                // Enable/disable is an authoritative command and rollback is transactionally explicit.
                "AppState.kt:setNativePushEnabled" to "authoritative settings command with rollback",
                // Foreground catch-up is lifecycle-admitted and clears only the durable generation it observed.
                "AppState.kt:catchUpAfterForegroundActivation" to "lifecycle admission with generation-checked clear",
                // The durable attempted flag makes this a one-shot orchestration, not a refresh race.
                "AppState.kt:enableDefaultNotificationsIfReady" to "durable one-shot orchestration",
                // Follow/unfollow is an accepted server mutation; the revision only notifies Compose afterward.
                "AppState.kt:setProfileFollowing" to "authoritative relationship command result",
                // Search returns a value to its caller and does not publish shared controller state.
                "Controllers.kt:searchOneChat" to "pure returned search result",
                // Both leave entry points serialize the engine mutation through the group commit lock.
                "Controllers.kt:leaveGroup" to "serialized authoritative group mutation",
                // One subscription attempt owns its handles and consults account teardown under a monitor.
                "Controllers.kt:runConversationSubscriptionIteration" to "attempt-owned subscription state machine",
                // Handle identity is compared under the subscription monitor before clearing.
                "Controllers.kt:closeTimelineSubscriptionSafely" to "identity-checked handle teardown",
                // Delete rollback is conditional on its optimistic tombstone and the commit is group-serialized.
                "Controllers.kt:deleteMessageResult" to "serialized optimistic command with conditional rollback",
                // Retry resumes one retained optimistic mutation; media retries also recheck the account session.
                "Controllers.kt:retryFailedSend" to "retained optimistic command ownership",
                // The conversation mutation mutex serializes decline and its local projection update.
                "Controllers.kt:declineInvite" to "conversation mutation mutex",
                // Both archive entry points serialize commits and reconcile by optimistic-intent identity.
                "Controllers.kt:setArchived" to "serialized command with identity reconciliation",
                // The conversation mutation mutex serializes this group profile command.
                "Controllers.kt:updateGroupAvatarUrl" to "conversation mutation mutex",
                // The conversation mutation mutex serializes public/encrypted image replacement.
                "Controllers.kt:updateGroupImage" to "conversation mutation mutex",
                // Retention update and its timeline refresh are one serialized group mutation.
                "Controllers.kt:updateMessageRetention" to "conversation mutation mutex",
                // Export returns a temporary file and does not publish controller state.
                "Controllers.kt:exportConversationTranscriptFile" to "pure returned export result",
                // MDK advances read state monotonically and rollback only restores the matching optimistic id.
                "Controllers.kt:markReadUpTo" to "monotonic engine cursor with conditional rollback",
                // activeStreamIds admits one watcher per stream; removal is a separate terminal tombstone.
                "Controllers.kt:watchAgentTextStream" to "keyed single-owner stream lifecycle",
            )
        assertTrue(
            "every exemption must explain why no latest-wins guard is required",
            exemptions.values.all { it.isNotBlank() },
        )

        val guardedNames = guardedPaths.keys
        val candidates =
            asyncPublicationCandidates("AppState.kt", appState) +
                asyncPublicationCandidates("Controllers.kt", controllers)
        val unclassified = candidates - guardedNames - exemptions.keys
        assertTrue(
            "suspend-then-publish paths need a StalenessGuard or a commented exemption: $unclassified",
            unclassified.isEmpty(),
        )
    }

    /** Requires every retained generation-like field to explain why it is not a guard owner. */
    @Test
    fun retainedCountersDocumentTheirDifferentConcern() {
        val requiredExemptions =
            mapOf(
                "AppState.kt" to
                    listOf(
                        "mediaCacheRevisionState",
                        "transientNoticeSequence",
                        "relationshipRevision",
                        "runtimeGeneration",
                        "profileRevision",
                        "contactNicknameRevision",
                        "profileAccountRevisionEpoch",
                        "profileAccountRevisionSequence",
                        "attachmentDownloadPolicyRevision",
                        "draftHydrationRevision",
                        "notificationDrainSequence",
                    ),
                "Controllers.kt" to
                    listOf(
                        "retryGeneration",
                        "materializedGroupsRevision",
                        "forwardTargetsRevision",
                        "memberSnapshotsRevision",
                        "nextActivitySequence",
                        "lastStartedGeneration",
                        "streamDebugEventSequence",
                    ),
                "TtsPlaybackQueue.kt" to
                    listOf("messageProgressGeneration", "edgeRequestGeneration", "parkedTerminalGeneration"),
                "NotificationStreamForegroundService.kt" to
                    listOf("pendingPushWakeGeneration", "completedPushWakeGeneration"),
                "AvatarImageLoader.kt" to listOf("preWarmQueuedGeneration"),
                "AccountUnreadStore.kt" to listOf("revision"),
            )

        requiredExemptions.forEach { (fileName, fields) ->
            val source = productionSource(fileName)
            val lines = source.lineSequence().toList()
            fields.forEach { field ->
                val privateDeclaration = Regex("""\bprivate\s+(?:var|val)\s+${Regex.escape(field)}\b""")
                val anyDeclaration = Regex("""\b(?:var|val)\s+${Regex.escape(field)}\b""")
                val declaration =
                    lines
                        .indexOfFirst(privateDeclaration::containsMatchIn)
                        .takeIf { it >= 0 }
                        ?: lines.indexOfFirst(anyDeclaration::containsMatchIn)
                assertTrue("missing retained field $fileName:$field", declaration >= 0)
                val context = lines.subList((declaration - 3).coerceAtLeast(0), declaration + 1)
                assertTrue(
                    "$fileName:$field needs a nearby staleness-exempt reason",
                    context.any { "staleness-exempt:" in it },
                )
            }
        }
    }

    /** Finds suspended functions that later assign mutable state owned by their class. */
    private fun asyncPublicationCandidates(
        fileName: String,
        source: String,
    ): Set<String> {
        val mutableFields =
            Regex("""(?m)^ {4}(?:(?:private|internal|public|protected)\s+)?(?:lateinit\s+)?var\s+(\w+)""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toSet()
        return source.classFunctionSections().mapNotNullTo(mutableSetOf()) { (name, body) ->
            if ("suspend" !in body.substringBefore('(')) {
                return@mapNotNullTo null
            }
            val suspensionIndex =
                listOf("withContext(", ".await()", "awaitAll()", "delay(", "marmotIo {")
                    .map(body::indexOf)
                    .filter { it >= 0 }
                    .minOrNull()
                    ?: return@mapNotNullTo null
            val afterSuspension = body.substring(suspensionIndex)
            val writesSharedField =
                mutableFields.any { field ->
                    Regex("""\b${Regex.escape(field)}\s*(?:=|\+=|-=|\+\+|--)""")
                        .containsMatchIn(afterSuspension)
                }
            if (writesSharedField) "$fileName:$name" else null
        }
    }

    /** Splits class-level functions into source slices suitable for lightweight policy checks. */
    private fun String.classFunctionSections(): List<Pair<String, String>> {
        val declarations =
            Regex(
                """(?m)^ {4}(?:(?:private|internal|public|protected)\s+)?""" +
                    """(?:(?:suspend|inline|operator|override|open)\s+)*fun\s+(?:\w+\.)?(\w+)\s*\(""",
            ).findAll(this).toList()
        return declarations.mapIndexed { index, match ->
            val end = declarations.getOrNull(index + 1)?.range?.first ?: length
            match.groupValues[1] to substring(match.range.first, end)
        }
    }

    /** Returns the source slice for the named class-level function. */
    private fun String.functionSection(functionName: String): String =
        classFunctionSections().firstOrNull { it.first == functionName }?.second
            ?: error("Missing function $functionName")

    /** Loads the production files that own audited latest-wins paths. */
    private fun productionSources(): Map<String, String> =
        listOf(
            "StalenessGuard.kt",
            "AppState.kt",
            "Controllers.kt",
            "AccountUnreadStore.kt",
            "AttachmentOpenCoordinator.kt",
            "AttachmentTransferCoordinator.kt",
            "ConversationInitialPresentationWarm.kt",
            "ConversationCardPostSynchronizer.kt",
            "TtsController.kt",
            "MessageDraftRepository.kt",
            "MessageForwarding.kt",
        ).associateWith(::productionSource)

    /** Locates one production source from either the module or repository working directory. */
    private fun productionSource(fileName: String): String {
        val packages =
            listOf(
                "state",
                "audio/tts",
                "core",
                "media/editor",
                "notifications",
            )
        return packages
            .asSequence()
            .flatMap { packageName ->
                sequenceOf(
                    File("src/main/java/dev/ipf/whitenoise/android/$packageName/$fileName"),
                    File("app/src/main/java/dev/ipf/whitenoise/android/$packageName/$fileName"),
                )
            }.firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing production source $fileName")
    }
}
