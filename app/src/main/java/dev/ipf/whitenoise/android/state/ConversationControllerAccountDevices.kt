package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountDevicesActivationFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R

/**
 * MDK's advisory answer to whether this group can let members' linked devices join now, or null when it could not
 * be read. It is about the whole group and names no member or device. Android never works this out itself.
 */
internal suspend fun ConversationController.readAccountDevicesActivation(): AccountDevicesActivationFfi? {
    val account = conversationAccountRef ?: return null
    val groupId = group.groupIdHex
    return runCatchingCancellable {
        appState.marmotIo { accountDevicesActivation(account, groupId) }
    }.getOrNull()
}

/** A remote MLS commit can change eligibility while the display group record stays equal. */
internal fun MarmotEventFfi.isAccountDevicesEpochChange(
    account: String,
    groupId: String,
): Boolean =
    this is MarmotEventFfi.GroupEvent &&
        accountLabel == account &&
        groupIdHex.equals(groupId, ignoreCase = true) &&
        event is GroupEventKindFfi.EpochChanged

/** What MDK's advisory answer depends on here: it is read again whenever any part of this changes. */
internal fun ConversationController.accountDevicesRevision(): Any {
    val mutation = mutationInFlight to lastMutationError
    return Triple(accountDevicesGroupRevision, managementState, mutation)
}

/**
 * Turns on linked-device membership for this group in one admin commit that no member can undo. MDK checks
 * eligibility again when it commits, so a group that stopped being eligible is refused with MDK's reason and
 * nothing is sent. The change goes through the shared group-change admission, so a group still catching up asks the
 * user rather than failing.
 */
internal suspend fun ConversationController.enableAccountDevices(): Boolean =
    withMutationLockResult(false) {
        lastMutationError = null
        val account = conversationAccountRef ?: return@withMutationLockResult false
        runCatchingCancellable {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                appState.runGroupChange(
                    account,
                    group.groupIdHex,
                    actionLabel = R.string.group_account_devices_enable,
                ) {
                    appState.marmotIo { enableAccountDevices(account, group.groupIdHex) }
                }
            }
            refreshManagementState()
            true
        }.onFailure {
            recordMutationFailure(
                title = R.string.toast_couldnt_enable_account_devices,
                operationCode = "GROUP_ACCOUNT_DEVICES_ENABLE",
                throwable = it,
                detail = accountDevicesRefusalDetail(it),
            )
        }.getOrDefault(false)
    }

/**
 * The detail for a failed enable: MDK's own reason when it refused the activation, otherwise the generic retry
 * copy. The reason is the same aggregate answer the advisory read gives.
 */
internal fun accountDevicesRefusalDetail(error: Throwable): AppText {
    val reason = (error as? MarmotKitException.AccountDevicesActivationRefused)?.reason
    return AppText.Resource(reason?.let(::accountDevicesActivationReason) ?: R.string.error_try_again)
}

/**
 * The sentence for why a group cannot take linked devices yet, or null where no sentence applies: an eligible group
 * is offered the action, an enabled one says so on its own row, and a member who is not an admin is told nothing,
 * because MDK tells a non-admin nothing more.
 */
internal fun accountDevicesActivationReason(activation: AccountDevicesActivationFfi): Int? =
    when (activation) {
        AccountDevicesActivationFfi.ELIGIBLE,
        AccountDevicesActivationFfi.NOT_ADMIN,
        AccountDevicesActivationFfi.ALREADY_ENABLED,
        -> null
        AccountDevicesActivationFfi.LEGACY_GROUP -> R.string.group_account_devices_legacy
        AccountDevicesActivationFfi.MEMBERS_NEED_UPDATE -> R.string.group_account_devices_members_need_update
        AccountDevicesActivationFfi.DEVICES_NEED_REPLACEMENT -> R.string.group_account_devices_need_replacement
        AccountDevicesActivationFfi.DEVICE_LIMIT_EXCEEDED -> R.string.group_account_devices_limit
    }
