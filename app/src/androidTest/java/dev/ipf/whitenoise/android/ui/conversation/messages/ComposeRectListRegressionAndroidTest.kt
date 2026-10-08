package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.runner.RunWith

/** Executes the same alignment/reuse regressions on a real Android layout pipeline in PR smoke. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ComposeRectListRegressionAndroidTest : ComposeRectListReuseFixture()
