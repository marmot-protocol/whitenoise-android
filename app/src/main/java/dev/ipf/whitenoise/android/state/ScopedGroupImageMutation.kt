package dev.ipf.whitenoise.android.state

/** An image mutation whose editor may stop owning its failure while native work is suspended. */
internal class ScopedGroupImageMutation<out T>(
    val value: T,
    val isActive: () -> Boolean,
) {
    var viewerPermissionCheck: Boolean = false
        private set
    var reconcilePrimary: Boolean = false
        private set

    /** Opts this single viewer attempt into authoritative admission and committed-image reconciliation. */
    fun forViewer(reconcile: Boolean): ScopedGroupImageMutation<T> {
        viewerPermissionCheck = true
        reconcilePrimary = reconcile
        return this
    }
}
