package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.sync.Mutex
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Transient native-call coordination only; holds no group or recovery results. */
internal object QuarantinedGroupsOperationLeases {
    private val nodes = mutableListOf<Node>()

    internal class Node(
        val runtime: Any,
        val account: String,
        var references: Int = 0,
    ) {
        val mutex = Mutex()
    }

    fun acquire(
        runtime: Any,
        account: String,
    ): Lease =
        synchronized(nodes) {
            val node =
                nodes.firstOrNull { it.runtime === runtime && it.account == account }
                    ?: Node(runtime, account).also(nodes::add)
            node.references++
            Lease(node)
        }

    internal class Lease(
        private val node: Node,
    ) : Closeable {
        private val closed = AtomicBoolean()
        val mutex get() = node.mutex

        /** An admitted call retains its coordination through screen disposal. */
        fun retain(): Lease =
            synchronized(nodes) {
                check(!closed.get())
                node.references++
                Lease(node)
            }

        override fun close() =
            synchronized(nodes) {
                if (closed.compareAndSet(false, true)) {
                    node.references--
                    if (node.references == 0) nodes.remove(node)
                }
                Unit
            }
    }
}
