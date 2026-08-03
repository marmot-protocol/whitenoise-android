package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppTextTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun plainResolvesToItsLiteralValue() {
        assertEquals("already localized", AppText.Plain("already localized").resolve(context))
    }

    @Test
    fun plainPreservesAnEmptyValue() {
        // Callers pass engine-supplied detail through Plain, which can be empty.
        assertEquals("", AppText.Plain("").resolve(context))
    }

    @Test
    fun resourceWithoutArgumentsResolvesTheString() {
        assertEquals(context.getString(R.string.retry), AppText.Resource(R.string.retry).resolve(context))
    }

    @Test
    fun resourceWithArgumentsFormatsThem() {
        val expected = context.getString(R.string.group_title_invite_from, "alice")

        assertEquals(expected, AppText.Resource(R.string.group_title_invite_from, listOf("alice")).resolve(context))
    }

    @Test
    fun resourceWithoutArgumentsSkipsTheFormattingOverload() {
        // A bare string containing a literal % would throw if it were formatted.
        assertEquals(emptyList<Any>(), AppText.Resource(R.string.retry).args)
        assertEquals(context.getString(R.string.retry), AppText.Resource(R.string.retry).resolve(context))
    }

    @Test
    fun equalityFollowsTheDataContract() {
        // AppText rides in state objects compared for change detection.
        assertEquals(AppText.Plain("x"), AppText.Plain("x"))
        assertEquals(
            AppText.Resource(R.string.retry, listOf("a")),
            AppText.Resource(R.string.retry, listOf("a")),
        )
    }
}
