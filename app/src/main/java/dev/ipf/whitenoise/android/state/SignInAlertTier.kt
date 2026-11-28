package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi

/**
 * How loudly an account's sign-ins are announced outside the Devices screen. Only MDK's evidence raises
 * [Compromise]; an unknown sign-in alone is [Review]; everything else stays on the Devices screen. Time never moves
 * an account from one tier to another.
 */
internal enum class SignInAlertTier {
    /** Nothing to announce: sign-ins from before this device, ones marked as the user's, or ones that went away. */
    Passive,

    /** At least one new sign-in waits for the user's answer. */
    Review,

    /** MDK has an open compromise warning for the account on this device. */
    Compromise,
}

/** Sign-in alerts apply to local-key and external-signer accounts that are signed in here. */
internal fun signInAlertsApply(account: AccountSummaryFfi): Boolean {
    if (account.unlinked) return false
    return account.isSignedInSigningAccount()
}

/**
 * The sign-ins that still ask for an answer: new ones (not from before this device), still signed in, still waiting
 * in MDK's prompt state, and not marked "Mine, don't link" on this device. Set aside (Not now) and answered ones
 * stay in the Devices list only.
 */
internal fun signInsToReview(
    signIns: List<UnrecognizedSignInFfi>,
    mine: Set<String>,
): List<UnrecognizedSignInFfi> =
    signIns.filter {
        !it.beforeThisDevice &&
            it.signedIn &&
            it.state == UnrecognizedSignInStateFfi.PROMPT &&
            it.slotIdHex !in mine
    }

/** The tier for an account's [toReview] sign-ins and open [warning]. */
internal fun signInAlertTier(
    toReview: List<UnrecognizedSignInFfi>,
    warning: List<CompromiseWarningItemFfi>,
): SignInAlertTier =
    when {
        warning.isNotEmpty() -> SignInAlertTier.Compromise
        toReview.isNotEmpty() -> SignInAlertTier.Review
        else -> SignInAlertTier.Passive
    }

/** One warning item's identity, so an item is announced once however often MDK reports it. */
internal fun compromiseWarningKey(item: CompromiseWarningItemFfi): String =
    listOf(item.reason.name, item.slotIdHex, item.occurredAt, item.keyPackageRefHex).joinToString(":")

/** What one account's sign-in notifications should do after a read. */
internal data class SignInNotificationPlan(
    /** The number of sign-ins to show in the review notification, or null to leave it as it is. */
    val reviewCount: Int? = null,
    /** Whether posting [reviewCount] should alert, which only a sign-in not announced before does. */
    val reviewAlerts: Boolean = false,
    /** Whether the review notification should be withdrawn because nothing waits for an answer. */
    val withdrawReview: Boolean = false,
    /** Whether the compromise notification should be posted because the warning gathered something new. */
    val postCompromise: Boolean = false,
    /** Whether the compromise notification should be withdrawn because no warning is open. */
    val withdrawCompromise: Boolean = false,
    /** The sign-ins announced so far, to keep. */
    val announcedSignIns: Set<String> = emptySet(),
    /** The warning items announced so far, to keep. */
    val announcedWarning: Set<String> = emptySet(),
)

/**
 * Decides the notifications for one account. Every sign-in to review is announced once, and several new at once share
 * one alert; a smaller count than the one shown is updated quietly. A warning item is announced once. Nothing waits:
 * the matching notification is withdrawn. [shownReviewCount] is the count on the active notification, 0 when none.
 */
internal fun planSignInNotifications(
    toReview: List<UnrecognizedSignInFfi>,
    warning: List<CompromiseWarningItemFfi>,
    announcedSignIns: Set<String>,
    announcedWarning: Set<String>,
    shownReviewCount: Int,
): SignInNotificationPlan {
    val reviewSlots = toReview.map { it.slotIdHex }.toSet()
    val newSignIns = reviewSlots - announcedSignIns
    val warningKeys = warning.map(::compromiseWarningKey).toSet()
    val newWarning = warningKeys - announcedWarning
    val reviewCount =
        when {
            newSignIns.isNotEmpty() -> reviewSlots.size
            reviewSlots.isNotEmpty() && shownReviewCount > reviewSlots.size -> reviewSlots.size
            else -> null
        }
    return SignInNotificationPlan(
        reviewCount = reviewCount,
        reviewAlerts = newSignIns.isNotEmpty(),
        withdrawReview = reviewSlots.isEmpty(),
        postCompromise = newWarning.isNotEmpty(),
        withdrawCompromise = warningKeys.isEmpty(),
        announcedSignIns = announcedSignIns + reviewSlots,
        announcedWarning = announcedWarning + warningKeys,
    )
}
