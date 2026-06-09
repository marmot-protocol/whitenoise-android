package dev.ipf.darkmatter.notifications

object BackgroundConnectionPolicy {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    private val systemWakeActions = setOf(ACTION_BOOT_COMPLETED, ACTION_MY_PACKAGE_REPLACED)

    fun shouldStartFromSystemWake(
        action: String?,
        backgroundConnectionEnabled: Boolean,
    ): Boolean = backgroundConnectionEnabled && action in systemWakeActions
}
