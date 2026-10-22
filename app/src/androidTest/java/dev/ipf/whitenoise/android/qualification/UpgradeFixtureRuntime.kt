package dev.ipf.whitenoise.android.qualification

import android.content.Context
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.marmotDeploymentWhiteNoise
import java.io.File

/** Only the fixture permits loopback; the baseline adapter omits the not-yet-existing deployment argument. */
internal fun upgradeFixtureRuntime(
    context: Context,
    root: File,
    relay: String,
): Marmot {
    MarmotAndroid.initialize(context)
    return Marmot.newWithConfiguration(
        root.absolutePath,
        listOf(relay),
        MarmotOptions(
            deployment = marmotDeploymentWhiteNoise(),
            relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
            attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
        ),
    )
}
