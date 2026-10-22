package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupMembersReviewLabelTest {
    /** Count-free action text follows the selection size, including locales where 21 has category one. */
    @Test
    fun singularLabelIsReservedForExactlyOneSelectedPerson() {
        listOf(
            Triple(Locale.ENGLISH, "Add member", "Add members"),
            Triple(Locale.FRENCH, "Ajouter un membre", "Ajouter des membres"),
            Triple(Locale.forLanguageTag("ru"), "Добавить участника", "Добавить участников"),
        ).forEach { (locale, singular, multiple) ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val resources = context.createConfigurationContext(configuration).resources
            assertEquals(singular, resources.getString(groupReviewAddMembersLabel(1)))
            listOf(2, 21).forEach { count ->
                assertEquals(multiple, resources.getString(groupReviewAddMembersLabel(count)))
            }
        }
    }
}
