package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for the local-ready account-switch boundary. */
class AccountSwitchLocalSnapshotOrderingTest {
    @Test
    fun profileSeedSetIncludesEveryVisibleOtherAccountAndExcludesOverflow() {
        val accounts =
            (1..6).map { index ->
                AccountSummaryFfi(
                    label = "account-$index",
                    accountIdHex = "id-$index",
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                )
            }

        val seeds =
            accountSwitchProfileSeedIds(
                directPeerIds = listOf("peer", "ID-1"),
                accounts = accounts,
                targetAccountRef = "account-2",
            )

        assertEquals(
            listOf("peer", "ID-1", "id-3", "id-4"),
            seeds,
        )
        assertEquals(MAX_TOP_BAR_OTHER_ACCOUNTS + 1, seeds.size)
    }

    @Test
    fun bindPublishesLocalSnapshotsAndAFrameBeforeCatchUp() {
        val body = controllersSource().readText().kotlinFunctionBody("bind")
        val retryLoop = body.indexOf("while (coroutineContext.isActive")
        val catchUpGate = body.indexOf("val catchUpGate = ChatListCatchUpGate()")
        val seededSnapshot = body.indexOf("val seededLocalSnapshot")
        val seededRenderFrame = body.indexOf("awaitRenderedChatListFrame()", startIndex = seededSnapshot)
        val seededCatchUp = body.indexOf("appState.launchCatchUpAccounts()", startIndex = seededRenderFrame)
        val seededRecompute =
            body.indexOf(
                "recompute(scheduleBackgroundEnrichment = false)",
                startIndex = seededCatchUp,
            )
        val firstSnapshot = body.indexOf("chatListStream.snapshot()")
        val localRowsReady = body.indexOf("recordAccountSwitchLocalRowsReady", startIndex = firstSnapshot)
        val secondSnapshot = body.indexOf("chatStream.snapshot()")
        val memberProjection = body.indexOf("seedInitialMemberIdProjection(accountRef, bindEpoch)")
        val publishReady = body.indexOf("isLoading = false", startIndex = secondSnapshot)
        val renderFrame = body.indexOf("awaitRenderedChatListFrame()", startIndex = publishReady)
        val catchUp = body.indexOf("connectionOwner.launchCatchUp()", startIndex = renderFrame)
        val initialValidation = body.indexOf("connectionOwner.beginSubscriptionValidation")
        val retryAttempt = body.indexOf("connectionOwner.beginSessionAttempt", startIndex = initialValidation)

        assertTrue("a constructor seed must be detected before bind clears state", seededSnapshot >= 0)
        assertTrue(
            "the catch-up gate must be scoped to the bind rather than a retry iteration",
            catchUpGate in 0..<retryLoop,
        )
        assertTrue("the seeded target list must draw before its catch-up starts", seededCatchUp > seededRenderFrame)
        assertTrue(
            "deferred rosters must wait for the batched live hydration instead of starting an N-call fan-out",
            seededRecompute > seededCatchUp,
        )
        assertTrue("chat-list snapshot must be read", firstSnapshot >= 0)
        assertTrue("cached-row timing must follow its local snapshot", localRowsReady > firstSnapshot)
        assertTrue("chats snapshot must follow the chat-list snapshot", secondSnapshot > firstSnapshot)
        assertTrue("the local member projection must follow both row snapshots", memberProjection > secondSnapshot)
        assertTrue("member-derived UI must be ready before the first visible frame", publishReady > memberProjection)
        assertTrue("the local snapshot must get a rendered frame before catch-up", renderFrame > publishReady)
        assertTrue("relay catch-up must start only after the rendered local frame", catchUp > renderFrame)
        assertTrue(
            "a retry must be able to re-prove application readiness",
            body.indexOf("pendingReadinessCatchUp", startIndex = catchUp) > catchUp,
        )
        assertEquals(
            "local subscription reopening must not launch another full catch-up",
            1,
            Regex("connectionOwner\\.launchCatchUp\\(\\)").findAll(body).count(),
        )
        assertTrue(
            "live consumers must start after catch-up is launched",
            body.indexOf("runUntilFirstLiveSubscriptionEnds(") > catchUp,
        )
        assertTrue("the first UI projection bind must validate without presenting a retry", initialValidation >= 0)
        assertTrue("only a later subscription iteration may present Connecting", retryAttempt > initialValidation)
    }

    @Test
    fun targetLocalSnapshotIsLoadedAndGenerationFencedBeforeAccountPublication() {
        val body = setActiveAccountSection()
        val generation = body.indexOf("val requestGeneration = accountSwitchHandoff.beginRequest()")
        val preload = body.indexOf("loadAccountSwitchLocalSnapshot(label, requestGeneration)")
        val finalGenerationGuard =
            body.indexOf(
                "isAccountSwitchCurrent(requestGeneration)",
                startIndex = preload,
            )
        val cacheClear = body.indexOf("clearCrossAccountCaches()", startIndex = finalGenerationGuard)
        val stageSnapshot = body.indexOf("stageAccountSwitchLocalSnapshot", startIndex = cacheClear)
        val publishAccount = body.indexOf("activeAccountRef = label", startIndex = stageSnapshot)
        val stageBody = appStateSource().readText().kotlinFunctionBody("stageAccountSwitchLocalSnapshot")
        val unreadProjection = stageBody.indexOf("accountUnreadValueFromRows")
        val profileSeeds =
            stageBody.indexOf(
                "localSnapshot.profiles.forEach(::applyAccountSwitchProfileSeed)",
                startIndex = unreadProjection,
            )
        val handoff =
            stageBody.indexOf(
                "accountSwitchHandoff.publish(requestGeneration, localSnapshot)",
                startIndex = profileSeeds,
            )

        assertTrue("each switch intent must capture a monotonic generation", generation >= 0)
        assertTrue("the target MDK snapshot must load before target publication", preload > generation)
        assertTrue("a late A→B result must be rejected after its final suspension", finalGenerationGuard > preload)
        assertTrue(
            "cross-account caches must clear only after the final generation guard",
            cacheClear > finalGenerationGuard,
        )
        assertTrue("target snapshot staging must follow cache clearing", stageSnapshot > cacheClear)
        assertTrue(
            "target unread rows must replace stale state before handoff publication",
            unreadProjection >= 0,
        )
        assertTrue("target profile seeds must apply after unread replacement", profileSeeds > unreadProjection)
        assertTrue(
            "the one-shot local handoff must publish after target profile seeds",
            handoff > profileSeeds,
        )
        assertTrue(
            "the active account must publish only after snapshot staging",
            publishAccount > stageSnapshot,
        )
        assertTrue(
            "a missing target snapshot must become unknown before account publication",
            stageBody.indexOf("accountUnreadStore.markUnknown(label)", startIndex = handoff) > handoff,
        )
    }

    @Test
    fun localPreloadFencesEverySuspendingStageAndDefersLiveSubscriptions() {
        val source = appStateSource().readText()
        val body = source.kotlinFunctionBody("loadAccountSwitchLocalSnapshot")
        val presentation = source.kotlinFunctionBody("loadAccountSwitchPresentationSeeds")
        val rows = body.indexOf("chatList(accountRef, includeArchived = true)")
        val rowsGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = rows)
        val rowsReady = body.indexOf("\"cached-chat-rows-ready\"", startIndex = rowsGuard)
        val presentationStart = body.indexOf("loadAccountSwitchPresentationSeeds", startIndex = rowsReady)
        val presentationGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = presentationStart)
        val snapshot = body.indexOf("AccountSwitchLocalSnapshot(", startIndex = presentationGuard)
        val topBarProfiles = presentation.indexOf("topBarProfilesDeferred = async")
        val members = presentation.indexOf("loadAccountSwitchMemberIds")
        val membersGuard = presentation.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = members)
        val directProfiles = presentation.indexOf("val directProfiles", startIndex = membersGuard)
        val directProfilesGuard =
            presentation.indexOf(
                "ensureAccountSwitchRequestIsCurrent",
                startIndex = directProfiles,
            )
        val topBarAwait = presentation.indexOf("topBarProfilesDeferred.await()", startIndex = directProfilesGuard)
        val topBarGuard = presentation.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = topBarAwait)

        assertTrue("one authoritative row read must replace temporary subscription admission", rows >= 0)
        assertTrue(rowsGuard > rows)
        assertTrue("row readiness must be recorded after the generation guard", rowsReady > rowsGuard)
        assertTrue("identity presentation must follow the authoritative rows", presentationStart > rowsReady)
        assertTrue(presentationGuard > presentationStart)
        assertTrue(snapshot > presentationGuard)
        assertTrue("the pre-activation handoff must defer full groups", "groups = emptyList()" in body)
        assertFalse("chat-list live admission belongs to the target controller", "subscribeChatList" in body)
        assertFalse("full group projection belongs to the target controller", "subscribeChats" in body)
        assertTrue("bounded top-bar profiles must overlap member projection", topBarProfiles in 0..<members)
        assertTrue(membersGuard > members)
        assertTrue(directProfilesGuard > directProfiles)
        assertTrue(topBarGuard > topBarAwait)
    }

    @Test
    fun localProfileWarmUsesTheTargetAccountAndSharedTopBarLimit() {
        val source = appStateSource().readText()
        val snapshot = source.kotlinFunctionBody("loadAccountSwitchPresentationSeeds")
        val directPeers =
            accountSwitchSnapshotSource()
                .readText()
                .kotlinFunctionBody("accountSwitchDirectPeerProfileIds")

        assertTrue(
            "the target ref must own the bounded top-bar profile seed projection",
            "accountSwitchProfileSeedIds(emptyList(), accounts, accountRef)" in snapshot,
        )
        assertTrue(
            "direct peers must still derive from the identity-critical member projection",
            "initialDirectPeerProfileIds" in directPeers,
        )
        assertTrue(
            "top-bar and direct-peer seeds must be merged before publication",
            "directProfiles + topBarProfiles" in snapshot,
        )
        val seedProjection =
            source
                .substringAfter("internal fun accountSwitchProfileSeedIds(")
                .substringBefore("/**")
        assertTrue(
            "the shared top-bar limit must bound profile warming",
            "take(MAX_TOP_BAR_OTHER_ACCOUNTS)" in seedProjection,
        )
    }

    @Test
    fun blockingProfileWarmSelectsWorkAtomicallyBeforeLaunchingReads() {
        val body = appStateSource().readText().kotlinFunctionBody("warmProfilePresentationsBlocking")
        val lock = body.indexOf("synchronized(profilePresentationLock)")
        val selection = body.indexOf("profilePresentationIdsNeedingWarm", startIndex = lock)
        val reads = body.indexOf("coroutineScope", startIndex = selection)

        assertTrue("warm selection must observe both profile caches under their shared lock", lock >= 0)
        assertTrue("profile selection must happen while the shared lock is held", selection > lock)
        assertTrue("profile I/O must start only after the atomic selection", reads > selection)
    }

    @Test
    fun initialMemberProjectionDropsResultsInvalidatedWhileTheBatchReadWasInFlight() {
        val source = controllersSource().readText()
        val body = source.kotlinFunctionBody("seedInitialMemberIdProjection")
        val currentCheck =
            source
                .substringAfter("private fun initialMemberProjectionIsCurrent(")
                .substringBefore("private fun initialDirectPeerProfileIds(")
        val loader = source.kotlinFunctionBody("loadInitialMemberIdProjections")
        val publisher = source.kotlinFunctionBody("applyInitialMemberIdProjections")
        val capturedEpoch = body.indexOf("expectedCacheEpoch = memberCacheEpoch")
        val ffiRead = body.indexOf("loadInitialMemberIdProjections", startIndex = capturedEpoch)
        val staleCheck = body.indexOf("initialMemberProjectionIsCurrent", startIndex = ffiRead)
        val profileWarm = body.indexOf("warmProfilePresentationsBlocking", startIndex = staleCheck)
        val secondStaleCheck = body.indexOf("initialMemberProjectionIsCurrent", startIndex = profileWarm)
        val publish = body.indexOf("applyInitialMemberIdProjections", startIndex = secondStaleCheck)
        val guardCapturesGeneration =
            "initialMemberFallbackGenerationIsCurrent" in currentCheck &&
                "currentCacheEpoch = memberCacheEpoch" in currentCheck
        val publisherReplacesCache = "memberCacheByGroup = updatedCache" in publisher

        assertTrue("the current cache generation must be captured before suspension", capturedEpoch >= 0)
        assertTrue("the batch FFI read must follow the generation snapshot", ffiRead > capturedEpoch)
        assertTrue("live invalidation must be checked after the FFI read", staleCheck > ffiRead)
        assertTrue("DM local profiles must be awaited before cache publication", profileWarm > staleCheck)
        assertTrue("profile warming must be followed by another generation check", secondStaleCheck > profileWarm)
        assertTrue("stale batch results must be rejected before cache publication", publish > secondStaleCheck)
        assertTrue("the loader must use the batched local projection", "loadGroupMemberIdsPages" in loader)
        assertTrue("the guard must compare the captured cache generation", guardCapturesGeneration)
        assertTrue("the publisher must replace the cache only after validation", publisherReplacesCache)
    }

    @Test
    fun memberDerivedReadinessIsRecordedBeforeTheFirstVisibleFrame() {
        val body = controllersSource().readText().kotlinFunctionBody("bind")
        val seed = body.indexOf("seedInitialMemberIdProjection(accountRef, bindEpoch)")
        val readiness = body.indexOf("recordMemberDerivedLocalReadyIfComplete()", startIndex = seed)
        val publishReady = body.indexOf("isLoading = false", startIndex = readiness)

        assertTrue("local membership must be seeded", seed >= 0)
        assertTrue("member-derived readiness must be checked after seeding", readiness > seed)
        assertTrue("member-derived readiness must precede visible publication", publishReady > readiness)
    }

    /** Keeps process-scoped catch-up fenced by account, runtime, and shared network lifetime. */
    @Test
    fun catchUpLaunchIsProcessScopedAndDeduplicated() {
        val body = appStateSource().readText().kotlinFunctionBody("launchAccountCatchUp")
        val coordinator = connectivityRuntimeSource().readText()

        assertTrue(
            "a blocked catch-up must run outside the controller bind job",
            "accountCatchUpCoordinator.launch" in body,
        )
        assertTrue(
            "readiness callers must receive a result without owning the native job",
            "val result: CompletableDeferred<AccountCatchUpResult>" in coordinator,
        )
        assertTrue(
            "only identity-matched callers may share an active catch-up",
            "request.key == key" in coordinator,
        )
        assertTrue(
            "the background job must run the result-bearing best-effort catch-up",
            "catchUpAccountsBestEffort()" in body,
        )
        assertTrue(
            "completion must be fenced by account, runtime, and network generation",
            "activeAccountRef == key.accountRef" in body &&
                "runtimeGeneration == key.runtimeGeneration" in body &&
                "connectivitySignalOwner.isNetworkGenerationCurrent(key.networkGeneration)" in body,
        )
    }

    /** Foreground recovery shares catch-up without issuing a second transport wake. */
    @Test
    fun foregroundRecoveryUsesTheSharedCatchUpOnly() {
        val body = appStateSource().readText().kotlinFunctionBody("catchUpAfterForegroundActivation")

        assertFalse("foreground recovery must not duplicate the transport wake", "notifyConnectivityRestored()" in body)
        assertTrue(
            "foreground recovery must use the post-trigger catch-up boundary",
            "catchUpAfterObservedPushWake(pendingGeneration)" in body,
        )
    }

    /** Durable push recovery joins the same process-owned native call. */
    @Test
    fun pushWakeRecoveryUsesTheSharedCatchUp() {
        val body = appStateSource().readText().kotlinFunctionBody("drainPendingPushWakeCatchUpIfNeeded")

        assertTrue(
            "push-wake recovery must use the post-trigger catch-up boundary",
            "catchUpAfterObservedPushWake(pendingGeneration)" in body,
        )
        assertFalse("push-wake recovery must not bypass coordination", "catchUpAccountsBestEffort()" in body)
    }

    @Test
    fun activationCallbackPrecedesBestEffortPostSwitchWork() {
        val source = appStateSource().readText()
        val start = source.indexOf("suspend fun setActiveAccount(")
        val end = source.indexOf("internal fun recordAccountSwitchLocalSnapshotRendered", startIndex = start)
        check(start >= 0 && end > start) { "Missing setActiveAccount section" }
        val body = source.substring(start, end)
        val activationGuard =
            body.indexOf(
                "if (!shouldActivate() || !isAccountSwitchCurrent(requestGeneration)) return",
            )
        val activeRef = body.indexOf("activeAccountRef = label")
        val localUiState = body.indexOf("reloadMediaAutoDownloadMatrix()", startIndex = activeRef)
        val activated = body.indexOf("onActivated()", startIndex = localUiState)
        val profile = body.indexOf("warmProfile(it)", startIndex = activated)
        val privacy = body.indexOf("configurePrivacyRuntime()", startIndex = activated)
        val notifications = body.indexOf("refreshLocalNotificationSettings()", startIndex = activated)
        val push = body.indexOf("syncNativePushRegistrationIfEnabled()", startIndex = activated)

        assertTrue(
            "a stale route must be rejected before the active account is published",
            activationGuard >= 0 && activeRef > activationGuard,
        )
        assertTrue(
            "target account context must flip before the activation callback",
            activeRef >= 0 && localUiState > activeRef,
        )
        assertTrue("the activation callback must follow local account UI state", activated > localUiState)
        listOf(profile, privacy, notifications, push).forEach { postSwitchIndex ->
            assertTrue(
                "network/best-effort switch work must follow the activation callback",
                postSwitchIndex > activated,
            )
        }
    }

    @Test
    fun notificationPriorityPolicySkipsBroadPreloadAndDefersBestEffortWork() {
        val policy = accountSwitchSnapshotSource().readText()
        val body = setActiveAccountSection()
        val policyGate = body.indexOf("shouldLoadAccountSwitchLocalSnapshot(")
        val broadSnapshot = body.indexOf("loadAccountSwitchLocalSnapshot(label, requestGeneration)")
        val policyElse = body.indexOf("} else {", startIndex = policyGate)
        val activated = body.indexOf("onActivated()")
        val firstFrameGate = body.indexOf("awaitPostActivationWork()", startIndex = activated)
        val staleGuard =
            body.indexOf(
                "isCurrentPostActivationAccountSwitch(label, requestGeneration)",
                startIndex = firstFrameGate,
            )
        val profile = body.indexOf("warmProfile(it)", startIndex = staleGuard)

        assertTrue(
            "only ordinary switches may load the broad local snapshot",
            "preloadPolicy == AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT" in policy,
        )
        assertTrue(
            "broad account preload must be inside the policy-controlled branch",
            policyGate >= 0 && broadSnapshot > policyGate && policyElse > broadSnapshot,
        )
        assertTrue("the target account must activate before waiting for its readable frame", firstFrameGate > activated)
        assertTrue("superseded deferred work must be rejected after the wait", staleGuard > firstFrameGate)
        assertTrue("profile warming must stay outside the target first-frame path", profile > staleGuard)
    }

    @Test
    fun selectorDismissesAtActivationBoundaryInsteadOfAwaitingPostSwitchWork() {
        val body = accountSelectorSource().readText().kotlinFunctionBody("AccountSelectorSheet")

        val switchCall =
            body
                .substringAfter("appState.setActiveAccount(accountLabel) {")
                .substringBefore("\n                    }")
        val dismiss = switchCall.indexOf("onDismiss()")
        val reset = switchCall.indexOf("onAccountSwitched()")

        assertTrue(
            "dismiss/reset must be passed into setActiveAccount's activation boundary",
            dismiss >= 0 && reset > dismiss,
        )
        assertFalse(
            "dismiss must not wait until the entire setActiveAccount call returns",
            Regex("""setActiveAccount\(accountLabel\)\s*onDismiss\(\)""").containsMatchIn(body),
        )
    }

    @Test
    fun firstLocalFrameTimingTraceIsTargetScopedAndPrivacySafe() {
        val appState = appStateSource().readText()
        val recorder = appState.kotlinFunctionBody("recordAccountSwitchLocalSnapshotRendered")
        val bind = controllersSource().readText().kotlinFunctionBody("bind")

        assertTrue(
            "trace must reject a stale controller from another account",
            "trace.accountRef != accountRef" in recorder,
        )
        assertTrue(
            "trace must reject a controller that is no longer active",
            "activeAccountRef != accountRef" in recorder,
        )
        assertTrue(
            "a stale controller must be rejected before publishing startup readiness",
            recorder.indexOf("activeAccountRef != accountRef") <
                recorder.indexOf("recordStartupLocalSnapshotRendered()"),
        )
        assertTrue(
            "trace must use a monotonic elapsed clock",
            "SystemClock.elapsedRealtime() - trace.startedAtMs" in recorder,
        )
        assertTrue("trace must report only timing and a small row count", "rows=\$rowCount" in recorder)
        assertFalse(
            "trace output must not include an account identifier",
            "trace.accountRef" in recorder.substringAfter("appStateDebug"),
        )
        assertTrue(
            "the trace must be recorded only after the local frame boundary",
            bind.indexOf("recordAccountSwitchLocalSnapshotRendered") >
                bind.indexOf("awaitRenderedChatListFrame()"),
        )
    }

    @Test
    fun readinessStageTracesAreTargetScopedAndPrivacySafe() {
        val source = appStateSource().readText()
        val localRows = source.kotlinFunctionBody("recordAccountSwitchLocalRowsReady")
        val memberDerived = source.kotlinFunctionBody("recordAccountSwitchMemberDerivedLocalReady")
        val stageRecorder = source.kotlinFunctionBody("recordPendingAccountSwitchStage")

        listOf(localRows, memberDerived).forEach { recorder ->
            assertTrue("stale account stages must be rejected", "activeAccountRef != accountRef" in recorder)
        }
        assertTrue(
            "switch stages must reject a superseded trace",
            "trace.accountRef != accountRef" in stageRecorder,
        )
        assertTrue(
            "stage records must use the monotonic startup clock",
            "SystemClock.elapsedRealtime()" in stageRecorder,
        )
        assertTrue(
            "stage output may contain only stage, elapsed time, and row count",
            "rows=\$rowCount" in stageRecorder,
        )
        assertFalse("stage output must not interpolate an account identifier", "accountRef}" in stageRecorder)
    }

    private fun controllersSource(): File = source("state/Controllers.kt")

    private fun appStateSource(): File = source("state/AppState.kt")

    private fun accountSwitchSnapshotSource(): File = source("state/AccountSwitchLocalSnapshot.kt")

    private fun connectivityRuntimeSource(): File = source("state/ChatListConnectivityRuntime.kt")

    private fun setActiveAccountSection(): String {
        val source = appStateSource().readText()
        val start = source.indexOf("suspend fun setActiveAccount(")
        val end = source.indexOf("internal fun recordAccountSwitchLocalRowsReady", startIndex = start)
        check(start >= 0 && end > start) { "Missing setActiveAccount section" }
        return source.substring(start, end)
    }

    private fun accountSelectorSource(): File = source("ui/account/AccountSelectorSheet.kt")

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists) ?: error("Missing source file: $relativePath")
}
