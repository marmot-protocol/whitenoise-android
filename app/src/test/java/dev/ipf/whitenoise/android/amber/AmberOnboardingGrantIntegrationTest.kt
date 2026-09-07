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
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** End-to-end Android-boundary coverage for the consolidated Amber login grant session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmberOnboardingGrantIntegrationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private lateinit var launcher: CountingLauncher
    private lateinit var rememberedProvider: RememberedValueProvider

    @Before
    fun setUp() {
        launcher = CountingLauncher()
        AmberActivityCoordinator.attach(launcher)
        installAmber64()
        registerAmberHandler()
        rememberedProvider = RememberedValueProvider("ciphertext")
        ShadowContentResolver.registerProviderInternal(
            "${Nip55.AMBER_PACKAGE}.${SignerOp.Nip44Encrypt.contentAuthoritySuffix}",
            rememberedProvider,
        )
    }

    @After
    fun tearDown() {
        AmberActivityCoordinator.detach(launcher)
        Nip55.clearSignerPackage(context)
        ShadowContentResolver.reset()
    }

    @Test
    fun groupedLoginAndRememberedFirstOperationUseOneForegroundSession() {
        val pubkey = "ab".repeat(32)
        val controller = AmberSignerController(context, approvalTimeoutMs = 5_000)
        val loginResult = AtomicReference<String>()
        val loginFailure = AtomicReference<Throwable>()
        val loginDone = CountDownLatch(1)
        Thread {
            try {
                loginResult.set(controller.requestPublicKey())
            } catch (throwable: Throwable) {
                loginFailure.set(throwable)
            } finally {
                loginDone.countDown()
            }
        }.start()

        val loginIntent = awaitLoginIntent()
        val requestId = checkNotNull(loginIntent.getStringExtra(Nip55.EXTRA_ID))
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                Intent()
                    .putExtra(Nip55.EXTRA_ID, requestId)
                    .putExtra(Nip55.EXTRA_RESULT, pubkey)
                    .putExtra(Nip55.EXTRA_PACKAGE, Nip55.AMBER_PACKAGE),
        )

        assertTrue(loginDone.await(2, TimeUnit.SECONDS))
        assertNull(loginFailure.get())
        assertEquals(pubkey, loginResult.get())

        val ciphertext = controller.buildSigner(pubkey).nip44Encrypt("counterparty", "plaintext")

        assertEquals("ciphertext", ciphertext)
        assertEquals(listOf("plaintext", "counterparty", pubkey), rememberedProvider.lastQueryArgs.get())
        assertEquals("remembered work must not open a second Amber session", 1, launcher.launchCount.get())
    }

    /** Amber 6.6.0 omits the rejection ID; kind-0 denial must finish promptly and require explicit retry. */
    @Test
    fun optionalProfileDenialReturnsWithoutAPromptLoopAndExplicitRetryCanApprove() {
        val pubkey = "ab".repeat(32)
        Nip55.saveSignerPackage(context, Nip55.AMBER_PACKAGE)
        val signer = AmberSignerController(context, approvalTimeoutMs = 5_000).buildSigner(pubkey)
        val profile = profileEvent(pubkey)
        val failure = AtomicReference<Throwable>()
        val denied = CountDownLatch(1)
        Thread {
            try {
                signer.signEvent(profile)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                denied.countDown()
            }
        }.start()
        awaitLoginIntent()
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data = Intent().putExtra(Nip55.EXTRA_REJECTED, true),
        )
        assertTrue(denied.await(2, TimeUnit.SECONDS))
        assertTrue(failure.get() is MarmotKitException.ExternalSignerRejected)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, launcher.launchCount.get())

        launcher.launched.set(null)
        val result = AtomicReference<String>()
        val retried = CountDownLatch(1)
        Thread {
            try {
                result.set(signer.signEvent(profile))
            } finally {
                retried.countDown()
            }
        }.start()
        val retry = awaitLoginIntent()
        val signature = "cd".repeat(64)
        AmberActivityCoordinator.deliverResult(
            resultOk = true,
            data =
                Intent().putExtra(
                    Nip55.EXTRA_RESULTS,
                    JSONArray()
                        .put(
                            JSONObject().put("id", retry.getStringExtra(Nip55.EXTRA_ID)).put("signature", signature),
                        ).toString(),
                ),
        )
        assertTrue(retried.await(2, TimeUnit.SECONDS))
        assertEquals(signature, JSONObject(checkNotNull(result.get())).getString("sig"))
        assertEquals(2, launcher.launchCount.get())
    }

    /** Builds a kind-0 request that is deliberately absent from the grouped login grants. */
    private fun profileEvent(pubkey: String): String =
        JSONObject()
            .put("id", "profile-event")
            .put("pubkey", pubkey)
            .put("created_at", 1_700_000_000)
            .put("kind", 0)
            .put("tags", JSONArray())
            .put("content", "{\"display_name\":\"Disposable setup test\"}")
            .put("sig", "")
            .toString()

    private fun awaitLoginIntent(): Intent {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            launcher.launched.get()?.let { return it }
            Thread.sleep(5)
        }
        return checkNotNull(launcher.launched.get())
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
    }

    private fun registerAmberHandler() {
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = Nip55.AMBER_PACKAGE
                        name = "${Nip55.AMBER_PACKAGE}.SignerActivity"
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:")),
            resolveInfo,
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("${Nip55.SCHEME}:")).setPackage(Nip55.AMBER_PACKAGE),
            resolveInfo,
        )
    }

    private class CountingLauncher : ActivityResultLauncher<Intent>() {
        val launched = AtomicReference<Intent>()
        val launchCount = AtomicInteger()

        override fun launch(
            input: Intent,
            options: ActivityOptionsCompat?,
        ) {
            launchCount.incrementAndGet()
            launched.set(input)
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<Intent, *> =
            ActivityResultContracts.StartActivityForResult()
    }

    private class RememberedValueProvider(
        private val value: String,
    ) : ContentProvider() {
        val lastQueryArgs = AtomicReference<List<String>>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            lastQueryArgs.set(projection.orEmpty().toList())
            return MatrixCursor(arrayOf(Nip55.COLUMN_RESULT)).apply {
                addRow(arrayOf(value))
            }
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
}
