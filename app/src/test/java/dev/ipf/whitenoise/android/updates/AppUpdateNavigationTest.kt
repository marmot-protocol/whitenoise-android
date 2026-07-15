package dev.ipf.whitenoise.android.updates

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateNavigationTest {
    @Test
    fun acceptsOnlyUpdateIntentsMintedByAppUpdateNavigation() {
        val minted = Intent()
        AppUpdateNavigation.applyToIntent(minted, "2026.6.20")
        assertTrue(AppUpdateNavigation.isUpdateTap(minted))

        assertFalse(AppUpdateNavigation.isUpdateTap(Intent(AppUpdateNavigation.ACTION_OPEN_UPDATE)))
        assertFalse(
            AppUpdateNavigation.isUpdateTap(
                Intent(AppUpdateNavigation.ACTION_OPEN_UPDATE, Uri.parse("https://zapstore.dev/apps/org.parres.darkmatter")),
            ),
        )
        assertFalse(
            AppUpdateNavigation.isUpdateTap(
                Intent(AppUpdateNavigation.ACTION_OPEN_UPDATE, Uri.parse("darkmatter-update://other/2026.6.20")),
            ),
        )
    }
}
