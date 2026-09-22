package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one-shot conversion of an untouched pre-#2699 matrix to the metered-safe default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class MediaAutoDownloadMigrationTest {
    private lateinit var preferences: SharedPreferences

    /** Each case starts from an empty preference store. */
    @Before
    fun setUp() {
        preferences =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences("media-auto-download-migration-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    /** An untouched old default becomes the metered-safe default and is rewritten to disk. */
    @Test
    fun untouchedOldDefaultBecomesTheSafeDefault() {
        val migrated = migrate(MediaAutoDownloadMatrix.LEGACY_METERED_IMAGE_DEFAULT)

        assertEquals(MediaAutoDownloadMatrix.DEFAULT, migrated)
        assertEquals(MediaAutoDownloadMatrix.DEFAULT, stored())
    }

    /** A per-account configuration that is not the old default is preserved exactly. */
    @Test
    fun customMatrixIsPreserved() {
        val custom =
            MediaAutoDownloadMatrix(emptySet())
                .withToggle(MediaAutoDownloadType.Video, MediaAutoDownloadNetwork.Roaming, on = true)

        assertEquals(custom, migrate(custom))
        assertEquals(custom, stored())
    }

    /** The legacy `always` selection enables every cell and stays a deliberate opt-in. */
    @Test
    fun legacyAlwaysSelectionIsPreserved() {
        var always = MediaAutoDownloadMatrix(emptySet())
        MediaAutoDownloadType.entries.forEach { type ->
            MediaAutoDownloadNetwork.entries.forEach { network ->
                always = always.withToggle(type, network, on = true)
            }
        }

        assertEquals(always, migrate(always))
    }

    /** Once stamped, the migration never runs again, so a later metered opt-in survives a reload. */
    @Test
    fun explicitMeteredOptInSurvivesAReload() {
        migrate(MediaAutoDownloadMatrix.LEGACY_METERED_IMAGE_DEFAULT)
        val optIn =
            MediaAutoDownloadMatrix.DEFAULT
                .withToggle(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.Metered, on = true)
        persistMediaAutoDownloadMatrix(preferences, KEY, optIn)

        assertEquals(optIn, migrate(optIn))
        assertEquals(optIn, stored())
    }

    /** An account seeded after the change is already current and is left alone. */
    @Test
    fun seededAccountIsNotMigratedAgain() {
        persistMediaAutoDownloadMatrix(preferences, KEY, MediaAutoDownloadMatrix.DEFAULT)
        val optIn =
            MediaAutoDownloadMatrix.DEFAULT
                .withToggle(MediaAutoDownloadType.Audio, MediaAutoDownloadNetwork.Metered, on = true)
        persistMediaAutoDownloadMatrix(preferences, KEY, optIn)

        assertEquals(optIn, migrate(optIn))
    }

    /** Each account carries its own version stamp, so one migration cannot speak for another. */
    @Test
    fun migrationIsScopedToOneAccountKey() {
        migrate(MediaAutoDownloadMatrix.LEGACY_METERED_IMAGE_DEFAULT)

        val otherAccount =
            migratedMediaAutoDownloadMatrix(
                preferences,
                OTHER_KEY,
                MediaAutoDownloadMatrix.LEGACY_METERED_IMAGE_DEFAULT,
            )
        assertEquals(MediaAutoDownloadMatrix.DEFAULT, otherAccount)
    }

    /** Runs the migration for this account's key. */
    private fun migrate(stored: MediaAutoDownloadMatrix) = migratedMediaAutoDownloadMatrix(preferences, KEY, stored)

    /** The matrix currently on disk for this account. */
    private fun stored() = MediaAutoDownloadMatrix.fromPreference(preferences.getString(KEY, null))

    private companion object {
        const val KEY = "media_auto_download_matrix:account-a"
        const val OTHER_KEY = "media_auto_download_matrix:account-b"
    }
}
