package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Runs the shared regressions on both distribution classpaths against the official Compose runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeRectListRegressionTest : ComposeRectListReuseFixture()
