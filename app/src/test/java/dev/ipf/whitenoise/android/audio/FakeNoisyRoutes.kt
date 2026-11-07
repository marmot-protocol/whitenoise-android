package dev.ipf.whitenoise.android.audio

/** Headphone-disconnect source whose open registrations a test can count and fire. */
internal class FakeNoisyRoutes : NoisyAudioRouteRegistrar {
    private val active = LinkedHashMap<Any, () -> Unit>()

    val listeners: Int
        @Synchronized get() = active.size

    @Synchronized
    override fun register(onNoisy: () -> Unit): AutoCloseable {
        val token = Any()
        active[token] = onNoisy
        return AutoCloseable { synchronized(this) { active.remove(token) } }
    }

    /** Delivers the platform signal to every registration open at the moment of the disconnect. */
    fun disconnect() = synchronized(this) { active.values.toList() }.forEach { it() }
}
