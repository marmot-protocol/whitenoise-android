package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import org.json.JSONArray
import org.json.JSONObject

/** Persists only this generated native result privately; no imeta parsing or Android protocol rules are involved. */
internal fun MediaAttachmentReferenceFfi.upgradeReceipt(): JSONObject =
    JSONObject()
        .put("locators", JSONArray(locators.map { JSONObject().put("kind", it.kind).put("value", it.value) }))
        .put("ciphertextSha256", ciphertextSha256)
        .put("plaintextSha256", plaintextSha256)
        .put("nonceHex", nonceHex)
        .put("fileName", fileName)
        .put("mediaType", mediaType)
        .put("version", version.name)
        .put("sourceEpoch", sourceEpoch.toString())
        .put("dim", dim ?: JSONObject.NULL)
        .put("thumbhash", thumbhash ?: JSONObject.NULL)

/** Reconstructs exactly the baseline's native result so MDK can promote its own pending upload after upgrade. */
internal fun JSONObject.upgradeMediaReference(): MediaAttachmentReferenceFfi {
    val values = getJSONArray("locators")
    return MediaAttachmentReferenceFfi(
        List(values.length()) { index ->
            values.getJSONObject(index).let { MediaLocatorFfi(it.getString("kind"), it.getString("value")) }
        },
        getString("ciphertextSha256"),
        getString("plaintextSha256"),
        getString("nonceHex"),
        getString("fileName"),
        getString("mediaType"),
        EncryptedMediaVersionFfi.valueOf(getString("version")),
        getString("sourceEpoch").toULong(),
        if (isNull("dim")) null else getString("dim"),
        if (isNull("thumbhash")) null else getString("thumbhash"),
    )
}
