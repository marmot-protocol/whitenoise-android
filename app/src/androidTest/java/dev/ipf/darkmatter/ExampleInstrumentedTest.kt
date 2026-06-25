package dev.ipf.darkmatter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // The build under test is one of the three White Noise application IDs.
        assertTrue(
            appContext.packageName in
                setOf(
                    "dev.ipf.whitenoise.android",
                    "dev.ipf.whitenoise.android.dev",
                    "dev.ipf.whitenoise.android.staging",
                ),
        )
    }
}
