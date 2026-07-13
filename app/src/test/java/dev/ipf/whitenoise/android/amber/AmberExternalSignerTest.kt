package dev.ipf.whitenoise.android.amber

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import dev.ipf.marmotkit.MarmotKitException
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmberExternalSignerTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private lateinit var launcher: CapturingLauncher

    @Before
    fun setUp() {
        launcher = CapturingLauncher()
        AmberActivityCoordinator.attach(launcher)
        Nip55.saveSignerPackage(context, SIGNER_PACKAGE)
        ShadowContentResolver.registerProviderInternal(AUTHORITY, RememberedRejectionProvider())
    }

    @After
    fun tearDown() {
        AmberActivityCoordinator.detach(launcher)
        Nip55.clearSignerPackage(context)
        ShadowContentResolver.reset()
    }

    @Test
    fun rememberedContentResolverRejectionDoesNotLaunchForegroundSigner() {
        val thrown = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val signer =
            AmberExternalSigner(
                appContext = context,
                accountPubkey = "account-pubkey",
                approvalTimeoutMs = 200,
            )

        Thread {
            try {
                signer.nip44Encrypt("counterparty-pubkey", "plaintext")
            } catch (error: Throwable) {
                thrown.set(error)
            } finally {
                done.countDown()
            }
        }.start()

        val deadline = System.currentTimeMillis() + 2_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertTrue("signer request did not finish", done.await(100, TimeUnit.MILLISECONDS))
        assertTrue(thrown.get() is MarmotKitException.ExternalSignerRejected)
        assertNull("remembered rejection must not launch a signer prompt", launcher.launched.get())
    }

    private class CapturingLauncher : ActivityResultLauncher<Intent>() {
        val launched = AtomicReference<Intent>()

        override fun launch(
            input: Intent,
            options: ActivityOptionsCompat?,
        ) {
            launched.set(input)
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<Intent, *> =
            ActivityResultContracts.StartActivityForResult()
    }

    private class RememberedRejectionProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(Nip55.COLUMN_REJECTED, Nip55.COLUMN_RESULT)).apply {
                addRow(arrayOf<Any>(1, "ignored-because-rejected"))
            }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val SIGNER_PACKAGE = "com.example.remembered-rejection"
        const val AUTHORITY = "$SIGNER_PACKAGE.NIP44_ENCRYPT"
    }
}
