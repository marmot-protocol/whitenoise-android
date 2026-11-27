package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.pm.PackageManager
import dev.ipf.whitenoise.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceivedApkFlavorContractTest {
    @Test
    fun mergedManifestPermissionMatchesTheCompiledDistributionPolicy() {
        val context = RuntimeEnvironment.getApplication()
        val requestedPermissions =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()

        assertEquals(
            BuildConfig.SELF_UPDATE_ENABLED,
            requestedPermissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"),
        )
    }

    @Test
    fun distributionBuildFlagsMatchTheManifestPolicy() {
        val buildScript = projectFile("app/build.gradle.kts").readText()
        val distributionBlock =
            buildScript.substringAfter("create(\"zapstore\")").substringBefore("buildTypes {")

        assertTrue(
            distributionBlock.contains("buildConfigField(\"boolean\", \"SELF_UPDATE_ENABLED\", \"true\")"),
        )
        assertTrue(distributionBlock.substringAfter("create(\"play\")").contains("\"SELF_UPDATE_ENABLED\", \"false\""))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .firstOrNull(File::exists)
            ?: File(path)
}
