package dev.ipf.whitenoise.android.amber

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import dev.ipf.marmotkit.MarmotKitException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:")).setPackage(SIGNER_PACKAGE),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = SIGNER_PACKAGE
                        name = "$SIGNER_PACKAGE.SignerActivity"
                    }
            },
        )
        ShadowContentResolver.registerProviderInternal(AUTHORITY, RememberedRejectionProvider())
    }

    @After
    fun tearDown() {
        AmberActivityCoordinator.detach(launcher)
        Nip55.clearSignerPackage(context)
        ShadowContentResolver.reset()
    }

    @Test
    fun rememberedContentResolverRejectionWithNullValueDoesNotLaunchForegroundSigner() {
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

    @Test
    fun rememberedContentResolverApprovalDoesNotLaunchForegroundSigner() {
        ShadowContentResolver.registerProviderInternal(AUTHORITY, RememberedValueProvider("ciphertext"))
        val signer =
            AmberExternalSigner(
                appContext = context,
                accountPubkey = "account-pubkey",
                approvalTimeoutMs = 200,
            )

        val result = signer.nip44Encrypt("counterparty-pubkey", "plaintext")

        assertEquals("ciphertext", result)
        assertNull("remembered approval must not launch a signer prompt", launcher.launched.get())
    }

    @Test
    fun missingStoredSignerPackageIsClearedBeforeAnyOperation() {
        Nip55.saveSignerPackage(context, "com.missing.signer")
        val signer = AmberExternalSigner(context, accountPubkey = "account-pubkey", approvalTimeoutMs = 200)

        assertThrows(MarmotKitException.ExternalSignerUnavailable::class.java) {
            signer.nip44Encrypt("counterparty-pubkey", "plaintext")
        }
        assertNull(Nip55.savedSignerPackage(context))
        assertNull(launcher.launched.get())
    }

    @Test
    fun amber64AggregateSignatureCompletesTheMatchingSignEvent() {
        val accountPubkey = "ab".repeat(32)
        val unsignedEvent =
            JSONObject()
                .put("id", "event-id")
                .put("pubkey", accountPubkey)
                .put("created_at", 1_700_000_000)
                .put("kind", 1)
                .put("tags", JSONArray())
                .put("content", "hello")
                .put("sig", "")
                .toString()
        val signature = "cd".repeat(64)
        installAmber64()
        Nip55.saveSignerPackage(context, Nip55.AMBER_PACKAGE)
        val result = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)

        Thread {
            try {
                result.set(
                    AmberExternalSigner(context, accountPubkey, approvalTimeoutMs = 5_000)
                        .signEvent(unsignedEvent),
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }.start()

        val deadline = System.currentTimeMillis() + 2_000
        while (launcher.launched.get() == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val signerIntent = checkNotNull(launcher.launched.get())
        val requestId = checkNotNull(signerIntent.getStringExtra(Nip55.EXTRA_ID))
        assertEquals(Nip55.AMBER_PACKAGE, signerIntent.`package`)
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                Intent().putExtra(
                    Nip55.EXTRA_RESULTS,
                    JSONArray()
                        .put(JSONObject().put("id", requestId).put("signature", signature).put("result", signature))
                        .toString(),
                ),
        )

        assertTrue(done.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertEquals(signature, JSONObject(checkNotNull(result.get())).getString("sig"))
        assertEquals(accountPubkey, JSONObject(checkNotNull(result.get())).getString("pubkey"))
    }

    private fun installAmber64() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = Nip55.AMBER_PACKAGE
                versionName = "6.4.0"
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = Nip55.AMBER_PACKAGE
                    }
            },
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:")).setPackage(Nip55.AMBER_PACKAGE),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = Nip55.AMBER_PACKAGE
                        name = "${Nip55.AMBER_PACKAGE}.SignerActivity"
                    }
            },
        )
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
                addRow(arrayOf<Any?>(null, "ignored-because-rejected"))
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

    private class RememberedValueProvider(
        private val value: String,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(Nip55.COLUMN_RESULT)).apply {
                addRow(arrayOf(value))
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
