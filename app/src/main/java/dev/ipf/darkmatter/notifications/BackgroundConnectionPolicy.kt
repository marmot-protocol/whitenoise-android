package dev.ipf.darkmatter.notifications

object BackgroundConnectionPolicy {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"

    fun shouldStartFromSystemWake(
        action: String?,
        backgroundConnectionEnabled: Boolean,
    ): Boolean {
        return backgroundConnectionEnabled && action == ACTION_BOOT_COMPLETED
    }
}
