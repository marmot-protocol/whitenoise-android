package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

private class AccountSwitchSnapshotSuperseded : RuntimeException()

internal fun WhiteNoiseAppState.isAccountSwitchCurrent(generation: Long) = accountSwitchHandoff.isCurrent(generation)

private fun WhiteNoiseAppState.ensureAccountSwitchRequestIsCurrent(generation: Long) {
    if (!isAccountSwitchCurrent(generation)) throw AccountSwitchSnapshotSuperseded()
}

/** Prepares both independent local reads before the coordinator's fenced publication. */
internal suspend fun WhiteNoiseAppState.projectActivation(
    request: AccountSessionActivationRequest,
    generation: Long,
    switchingAccounts: Boolean,
    restored: PreparedAccountSessionActivation,
): PreparedAccountSessionActivation =
    coroutineScope {
        val target = accounts.firstOrNull { it.label == request.label }
        val wanted = request.shouldActivate() && isAccountSwitchCurrent(generation)
        val plan = accountSwitchPreloadPlan(switchingAccounts, wanted, request.preloadPolicy)
        val snapshot =
            if (plan.loadLocalRows) {
                async { loadAccountSwitchLocalSnapshot(request.label, generation, plan) }
            } else {
                null
            }
        val profile =
            preloadStartupSelfProfile(
                policy = request.preloadPolicy,
                accountIdHex = target?.accountIdHex,
                activationWanted = wanted,
                loadProfile = ::loadAccountSwitchProfileSeed,
            )
        restored.copy(profilePresentation = profile, snapshot = snapshot?.await())
    }

/**
 * Reads the target account's local chat rows, and decodes their stored first-frame
 * avatars, before publishing the new active account. The first target composition gets
 * these rows instead of a loading placeholder; its controller owns full group and live
 * subscription admission after that seeded frame is visible.
 */
internal suspend fun WhiteNoiseAppState.loadAccountSwitchLocalSnapshot(
    accountRef: String,
    generation: Long,
    plan: AccountSwitchPreloadPlan,
): AccountSwitchLocalSnapshot? =
    try {
        val prefetched =
            if (plan.startupRestoration) awaitStartupChatListPrefetch(accountRef) else null
        val presentedRows =
            prefetched ?: marmotIo(MarmotTraceSection.CHAT_LIST_READ) {
                presentedChatList(accountRef, includeArchived = true)
            }.rows
        ensureAccountSwitchRequestIsCurrent(generation)
        val rows = presentedRows.map { it.row }
        val firstFrameAvatars = prewarmSwitchAvatars(accountRef, presentedRows, generation, plan, prefetched == null)
        ensureAccountSwitchRequestIsCurrent(generation)
        recordAccountSwitchPreloadStage(accountRef, "cached-chat-rows-ready", rows.size)
        val presentation =
            loadAccountSwitchPresentationSeeds(
                accountRef = accountRef,
                generation = generation,
                rows = rows,
                plan = plan,
            )
        ensureAccountSwitchRequestIsCurrent(generation)

        AccountSwitchLocalSnapshot(
            accountRef = accountRef,
            activeAccountIdHex = presentation.activeAccountIdHex,
            rows = rows,
            groups = emptyList(),
            memberIds = presentation.memberIds,
            profiles = presentation.profiles,
            presentedRows = presentedRows,
            firstFrameAvatars = firstFrameAvatars,
        ).also { snapshot ->
            if (plan.includePresentationSeeds) recordAccountSwitchIdentityState(accountRef, snapshot)
        }
    } catch (_: AccountSwitchSnapshotSuperseded) {
        null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (throwable: Throwable) {
        appStateDebug(throwable) {
            "account-switch local snapshot failed: ${throwable.readableMessage()}"
        }
        null
    }

private suspend fun WhiteNoiseAppState.prewarmSwitchAvatars(
    accountRef: String,
    rows: List<PresentedChatRowFfi>,
    generation: Long,
    plan: AccountSwitchPreloadPlan,
    decode: Boolean,
): List<FirstFrameDurableAvatar> =
    prewarmFirstFrameDurableAvatars(
        accountRef,
        rows,
        preparation =
            FirstFrameAvatarPreparation(
                awaitColdWorkers = plan.awaitColdAvatarWorkers,
                decode = decode,
                stillCurrent = { accountSwitchHandoff.isCurrent(generation) },
            ),
        onPrepared =
            if (plan.startupRestoration) {
                { renderable, decoded ->
                    ensureAccountSwitchRequestIsCurrent(generation)
                    recordStartupAvatarPreparation(renderable, decoded)
                }
            } else {
                null
            },
    )

private suspend fun WhiteNoiseAppState.loadAccountSwitchPresentationSeeds(
    accountRef: String,
    generation: Long,
    rows: List<ChatListRowFfi>,
    plan: AccountSwitchPreloadPlan,
): AccountSwitchPresentationSeeds =
    coroutineScope {
        val activeAccountIdHex = accounts.firstOrNull { it.label == accountRef }?.accountIdHex
        // The bounded set the selector/overflow policy will actually render. Both
        // switch paths read it here, so an interactive switch owes its first frame
        // exactly the seeds the full-snapshot path already loads (#2155).
        val topBarProfileIds =
            if (plan.includeTopBarProfileSeeds) {
                accountSwitchProfileSeedIds(emptyList(), accounts, accountRef)
            } else {
                emptyList()
            }
        if (!plan.includePresentationSeeds) {
            recordAccountSwitchPreloadStage(accountRef, "member-derived-local-deferred", rows.size)
            // Off the main thread, and dropped wholesale if a later switch wins.
            val topBarProfiles = loadSwitchProfileSeeds(topBarProfileIds)
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(accountRef, "top-bar-profiles-ready", rows.size)
            return@coroutineScope AccountSwitchPresentationSeeds(activeAccountIdHex, emptyList(), topBarProfiles)
        }
        // Overlap bounded top-bar reads with the identity-critical member page.
        val topBarProfilesDeferred = async { loadSwitchProfileSeeds(topBarProfileIds) }
        val memberIds = loadAccountSwitchMemberIds(accountRef, rows)
        ensureAccountSwitchRequestIsCurrent(generation)
        recordAccountSwitchPreloadStage(
            accountRef,
            accountSwitchMemberStage(rows, memberIds),
            rows.size,
        )
        val directPeerProfileIds = accountSwitchDirectPeerProfileIds(rows, memberIds, activeAccountIdHex)
        val topBarKeys = topBarProfileIds.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val directProfiles =
            loadSwitchProfileSeeds(
                directPeerProfileIds.filterNot { it.lowercase(Locale.ROOT) in topBarKeys },
            )
        ensureAccountSwitchRequestIsCurrent(generation)
        val topBarProfiles = topBarProfilesDeferred.await()
        ensureAccountSwitchRequestIsCurrent(generation)
        recordAccountSwitchPreloadStage(accountRef, "persisted-profiles-ready", rows.size)
        AccountSwitchPresentationSeeds(
            activeAccountIdHex = activeAccountIdHex,
            memberIds = memberIds,
            profiles = (directProfiles + topBarProfiles).distinctBy { it.accountIdHex.lowercase(Locale.ROOT) },
        )
    }

private suspend fun WhiteNoiseAppState.loadAccountSwitchMemberIds(
    accountRef: String,
    rows: List<ChatListRowFfi>,
): List<AppGroupMemberIdsFfi> {
    val identityGroupIds = accountSwitchFirstFrameMemberGroupIds(rows)
    if (identityGroupIds.isEmpty()) return emptyList()
    return runCatchingCancellable {
        loadGroupMemberIdsPages(identityGroupIds) { page ->
            marmotIo(MarmotTraceSection.MEMBER_IDS_READ) { groupMemberIdsPage(accountRef, page) }
        }
    }.onFailure { error ->
        appStateDebug(error) {
            "account-switch local member projection failed: ${error.readableMessage()}"
        }
    }.getOrDefault(emptyList())
}

private suspend fun WhiteNoiseAppState.loadSwitchProfileSeeds(ids: List<String>): List<AccountSwitchProfileSeed> =
    if (profileReader != null || profileDisplayNameReader != null) {
        // Existing injected single-identity readers retain their fixture contract.
        coroutineScope {
            val gate = Semaphore(WhiteNoiseAppState.PROFILE_PRESENTATION_WARM_FANOUT)
            ids
                .map { id -> async { gate.withPermit { loadAccountSwitchProfileSeed(id) } } }
                .awaitAll()
        }
    } else {
        loadAccountSwitchIdentityPages(ids) { page ->
            marmotIo(MarmotTraceSection.PROFILE_READ) { cachedIdentityProjections(page) }
        }
    }

internal fun WhiteNoiseAppState.stageAccountSwitchLocalSnapshot(
    label: String,
    switchingAccounts: Boolean,
    requestGeneration: Long,
    localSnapshot: AccountSwitchLocalSnapshot?,
) {
    if (localSnapshot != null) {
        // The target account's local SQLite rows are authoritative at the
        // same generation boundary as the first target composition. Replace
        // a stale/unknown picker value before activeAccountRef changes.
        accountUnreadStore.updateValue(label) {
            accountUnreadValueFromRows(
                rows = localSnapshot.rows,
                activeAccountIdHex = localSnapshot.activeAccountIdHex,
            )
        }
        localSnapshot.profiles.forEach(::applyAccountSwitchProfileSeed)
        accountSwitchHandoff.publish(requestGeneration, localSnapshot)
    } else if (switchingAccounts) {
        // A failed or intentionally deferred local snapshot cannot certify
        // the previous aggregate. Retain it for reconciliation, but stop
        // presenting it as current on the target account's first frame.
        accountUnreadStore.markUnknown(label)
    }
}
