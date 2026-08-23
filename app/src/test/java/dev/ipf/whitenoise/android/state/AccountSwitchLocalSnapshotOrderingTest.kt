package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for issue #1698's local-ready account-switch boundary. */
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
        val seededSnapshot = body.indexOf("val seededLocalSnapshot")
        val seededRenderFrame = body.indexOf("awaitRenderedChatListFrame()", startIndex = seededSnapshot)
        val seededCatchUp = body.indexOf("appState.launchCatchUpAccounts()", startIndex = seededRenderFrame)
        val firstSnapshot = body.indexOf("chatListStream.snapshot()")
        val localRowsReady = body.indexOf("recordAccountSwitchLocalRowsReady", startIndex = firstSnapshot)
        val secondSnapshot = body.indexOf("chatStream.snapshot()")
        val memberProjection = body.indexOf("seedInitialMemberIdProjection(accountRef, bindEpoch)")
        val publishReady = body.indexOf("isLoading = false", startIndex = secondSnapshot)
        val renderFrame = body.indexOf("awaitRenderedChatListFrame()", startIndex = publishReady)
        val catchUp = body.indexOf("appState.launchCatchUpAccounts()", startIndex = renderFrame)

        assertTrue("a constructor seed must be detected before bind clears state", seededSnapshot >= 0)
        assertTrue("the seeded target list must draw before its catch-up starts", seededCatchUp > seededRenderFrame)
        assertTrue("chat-list snapshot must be read", firstSnapshot >= 0)
        assertTrue("cached-row timing must follow its local snapshot", localRowsReady > firstSnapshot)
        assertTrue("chats snapshot must follow the chat-list snapshot", secondSnapshot > firstSnapshot)
        assertTrue("the local member projection must follow both row snapshots", memberProjection > secondSnapshot)
        assertTrue("member-derived UI must be ready before the first visible frame", publishReady > memberProjection)
        assertTrue("the local snapshot must get a rendered frame before catch-up", renderFrame > publishReady)
        assertTrue("relay catch-up must start only after the rendered local frame", catchUp > renderFrame)
        assertTrue("one bind must start catch-up only once", "if (!catchUpStarted)" in body)
        assertTrue(
            "live consumers must start after catch-up is launched",
            body.indexOf("runUntilFirstLiveSubscriptionEnds(") > catchUp,
        )
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
        val handoff =
            body.indexOf(
                "accountSwitchHandoff.publish(requestGeneration, localSnapshot)",
                startIndex = cacheClear,
            )
        val publishAccount = body.indexOf("activeAccountRef = label", startIndex = handoff)

        assertTrue("each switch intent must capture a monotonic generation", generation >= 0)
        assertTrue("the target MDK snapshot must load before target publication", preload > generation)
        assertTrue("a late A→B result must be rejected after its final suspension", finalGenerationGuard > preload)
        assertTrue(
            "cross-account caches must clear only after the final generation guard",
            cacheClear > finalGenerationGuard,
        )
        assertTrue("the one-shot local handoff must be ready before active account changes", handoff > cacheClear)
        assertTrue("the active account must publish only after the handoff is installed", publishAccount > handoff)
    }

    @Test
    fun localPreloadFencesEverySuspendingStageAndAlwaysClosesTemporarySubscriptions() {
        val body = appStateSource().readText().kotlinFunctionBody("loadAccountSwitchLocalSnapshot")
        val firstSubscription = body.indexOf("subscribeChatList")
        val firstGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = firstSubscription)
        val secondSubscription = body.indexOf("subscribeChats", startIndex = firstGuard)
        val secondGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = secondSubscription)
        val rows = body.indexOf("chatListSubscription.snapshot()", startIndex = secondGuard)
        val rowsGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = rows)
        val groups = body.indexOf("chatsSubscription.snapshot()", startIndex = rowsGuard)
        val groupsGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = groups)
        val members = body.indexOf("loadAccountSwitchMemberIds", startIndex = groupsGuard)
        val membersGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = members)
        val profiles = body.indexOf("loadAccountSwitchProfileSeeds", startIndex = membersGuard)
        val profilesGuard = body.indexOf("ensureAccountSwitchRequestIsCurrent", startIndex = profiles)
        val cleanup = body.indexOf("withContext(NonCancellable + Dispatchers.IO)", startIndex = profilesGuard)

        assertTrue(firstGuard > firstSubscription)
        assertTrue(secondGuard > secondSubscription)
        assertTrue(rowsGuard > rows)
        assertTrue(groupsGuard > groups)
        assertTrue(membersGuard > members)
        assertTrue(profilesGuard > profiles)
        assertTrue("temporary subscriptions must close even for stale/cancelled loads", cleanup > profilesGuard)
        assertTrue("chat-list subscription must close", "chatListSubscription?.close()" in body)
        assertTrue("group subscription must close", "chatsSubscription?.close()" in body)
    }

    @Test
    fun localProfileWarmUsesTheTargetAccountAndSharedTopBarLimit() {
        val source = appStateSource().readText()
        val snapshot = source.kotlinFunctionBody("loadAccountSwitchLocalSnapshot")
        val loader = source.kotlinFunctionBody("loadAccountSwitchProfileSeeds")

        assertTrue("the target ref must own the profile seed projection", "targetAccountRef = accountRef" in snapshot)
        assertTrue("top-bar accounts must join direct-peer profile seeds", "accountSwitchProfileSeedIds" in loader)
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

    @Test
    fun catchUpLaunchIsProcessScopedAndDeduplicated() {
        val body = appStateSource().readText().kotlinFunctionBody("launchCatchUpAccounts")

        assertTrue("a blocked catch-up must run outside the controller bind job", "notificationScope.launch" in body)
        assertTrue("rapid account rebinds must share an active catch-up", "accountCatchUpJob?.isActive == true" in body)
        assertTrue(
            "the background job must run the result-bearing best-effort catch-up",
            "catchUpAccountsBestEffort()" in body,
        )
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
        val policy = appStateSource().readText()
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
            assertTrue(
                "stage records must use the monotonic startup clock",
                "SystemClock.elapsedRealtime()" in recorder,
            )
        }
        assertTrue(
            "switch stages must reject a superseded trace",
            "trace.accountRef != accountRef" in stageRecorder,
        )
        assertTrue(
            "stage output may contain only stage, elapsed time, and row count",
            "rows=\$rowCount" in stageRecorder,
        )
        assertFalse("stage output must not interpolate an account identifier", "accountRef}" in stageRecorder)
    }

    private fun controllersSource(): File = source("state/Controllers.kt")

    private fun appStateSource(): File = source("state/AppState.kt")

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
