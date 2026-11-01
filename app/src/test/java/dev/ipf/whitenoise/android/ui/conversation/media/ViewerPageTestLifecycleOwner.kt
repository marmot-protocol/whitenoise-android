package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/** Direct page fixtures explicitly own the same foreground lifecycle as the viewer dialog. */
internal class ViewerPageTestLifecycleOwner(
    initial: Lifecycle.State = Lifecycle.State.RESUMED,
) : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = initial }
}
