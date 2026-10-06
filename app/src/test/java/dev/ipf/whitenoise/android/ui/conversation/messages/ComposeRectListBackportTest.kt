package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Properties

/** Runs the shared regressions on both distribution classpaths and verifies backport identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeRectListBackportTest : ComposeRectListReuseFixture() {
    /** Guards against tests silently resolving an unpatched UI jar on either distribution. */
    @Test
    fun runtimeContainsTheReviewedSourceBackport() {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("META-INF/whitenoise-compose-rectlist-backport.properties")
        assertNotNull("the Compose source backport must be on the test runtime classpath", stream)
        val properties = Properties()
        requireNotNull(stream).use(properties::load)
        assertEquals("1.12.1", properties.getProperty("base"))
        assertEquals("fd550bed793b66378c83091532e29c18fdef44cc", properties.getProperty("upstream"))
    }
}
