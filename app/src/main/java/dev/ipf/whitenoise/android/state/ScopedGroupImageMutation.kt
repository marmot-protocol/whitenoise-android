package dev.ipf.whitenoise.android.state

/** An image mutation whose editor may stop owning its failure while native work is suspended. */
internal class ScopedGroupImageMutation<out T>(
    val value: T,
    val isActive: () -> Boolean,
)
