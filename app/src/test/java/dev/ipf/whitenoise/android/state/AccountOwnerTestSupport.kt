package dev.ipf.whitenoise.android.state

/** Injects a live owner change for same-frame UI fences; native switching is exercised by separate tests. */
internal fun WhiteNoiseAppState.replaceActiveAccountForTest(value: String) {
    WhiteNoiseAppState::class.java
        .getDeclaredMethod("setActiveAccountRef", String::class.java)
        .apply { isAccessible = true }
        .invoke(this, value)
}
