package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.navigation.WarmResumeLifecycleClass

/**
 * The lifecycle a notification tap arrived in, which decides which latency budget applies. Labels are
 * fixed words and never carry an account, group, message or any text of a conversation.
 */
internal enum class NotificationRouteLaunchClass(
    val traceLabel: String,
) {
    /** An existing task and runtime: the budget is the tightest. */
    WARM_TASK("warmTask"),

    /** A new process or a restored one, where startup is part of the route. */
    COLD_PROCESS("coldProcess"),

    /** A live process whose runtime or target account runtime has not finished starting. */
    RUNTIME_NOT_READY("runtimeNotReady"),

    /** App lock is evaluating or showing, so the target stays staged until a person unlocks. */
    APP_LOCK_DEFERRED("appLockDeferred"),
}

/** Buckets one tap by what it found: a lock first, then a new process, then an unready runtime, else warm. */
internal fun classifyNotificationRouteLaunch(
    lifecycle: WarmResumeLifecycleClass,
    runtimeReady: Boolean,
    appLockPending: Boolean,
): NotificationRouteLaunchClass =
    when {
        appLockPending -> NotificationRouteLaunchClass.APP_LOCK_DEFERRED
        lifecycle == WarmResumeLifecycleClass.ColdProcessStart ||
            lifecycle == WarmResumeLifecycleClass.ProcessRestoration -> NotificationRouteLaunchClass.COLD_PROCESS
        !runtimeReady -> NotificationRouteLaunchClass.RUNTIME_NOT_READY
        else -> NotificationRouteLaunchClass.WARM_TASK
    }

/**
 * The lifecycle a tap arrived in. The Activity's own class is fixed when it is created, so a tap that a running
 * Activity receives through `onNewIntent` is a same-Activity resume whatever launch created that Activity.
 */
internal fun notificationTapLifecycle(
    activityClass: WarmResumeLifecycleClass,
    deliveredToRunningActivity: Boolean,
): WarmResumeLifecycleClass = if (deliveredToRunningActivity) WarmResumeLifecycleClass.SameActivity else activityClass

/** Starts the route's trace for one tap, labelled with the lifecycle it arrived in. */
internal fun WhiteNoiseAppState.startNotificationRouteTrace(
    requestId: Long,
    lifecycle: WarmResumeLifecycleClass,
    target: NotificationTarget,
) = NotificationRouteTrace.startRequest(requestId, notificationRouteLaunchClass(lifecycle, target))

/**
 * Classifies a tap from the app state it found. The target account is ready only if its own runtime is
 * running, so a suspended secondary account lands in its own bucket rather than hiding in the warm one.
 */
internal fun WhiteNoiseAppState.notificationRouteLaunchClass(
    lifecycle: WarmResumeLifecycleClass,
    target: NotificationTarget,
): NotificationRouteLaunchClass {
    val targetRunning = accounts.firstOrNull { it.label == target.accountRef }?.running != false
    return classifyNotificationRouteLaunch(
        lifecycle = lifecycle,
        runtimeReady = phase == AppPhase.Ready && targetRunning,
        appLockPending = appUnlockEvaluationPending || appLockScreenVisible,
    )
}
