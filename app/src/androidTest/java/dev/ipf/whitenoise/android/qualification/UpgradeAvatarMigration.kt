package dev.ipf.whitenoise.android.qualification

import android.graphics.Bitmap
import android.graphics.Color
import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.Marmot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Seeds a native-owned avatar cache on October 21 so migration 0114 must preserve real cached image bytes. */
internal suspend fun seedUpgradeAvatar(
    runtime: Marmot,
    blobServer: String,
    receipt: File,
) {
    val value = JSONObject(receipt.readText())
    val account = value.getString("sender")
    val group = value.getString("group")
    val url = runtime.uploadProfileImage(account, upgradeAvatarPng(), "image/png", blobServer)
    runtime.updateGroupAvatarUrl(account, group, url, null, null)
    val avatars = JSONArray()
    for (owner in listOf(account, value.getString("receiver"))) {
        avatars.put(awaitUpgradeAvatar(runtime, owner, group))
    }
    value.put("avatars", avatars)
    receipt.writeText(value.toString())
    verifyUpgradeAvatar(runtime, value)
}

/** Produces a small valid synthetic image without depending on external assets or image generators. */
private fun upgradeAvatarPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    return try {
        bitmap.eraseColor(Color.MAGENTA)
        ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

/** Uses MDK's projected target and acquisition, retaining only its opaque reference and observed payload digest. */
private suspend fun awaitUpgradeAvatar(
    runtime: Marmot,
    owner: String,
    group: String,
): JSONObject =
    withTimeout(60_000L) {
        var retained: JSONObject? = null
        while (retained == null) {
            val row = runtime.presentedChatListRow(owner, group)
            val asset =
                try {
                    row?.avatarAsset?.copy()
                } finally {
                    row?.destroy()
                }
            if (asset?.availability == AvatarAvailabilityFfi.READY && asset.reference != null) {
                val payload =
                    runtime.readAvatarAssets(owner, listOf(checkNotNull(asset.reference)), 1_048_576uL).single()
                check(!payload.deferred && payload.bytes.isNotEmpty())
                retained =
                    JSONObject()
                        .put("account", owner)
                        .put("reference", asset.reference)
                        .put("sha256", avatarDigest(payload.bytes))
            } else if (asset != null) {
                runtime.requestAvatarAssets(owner, listOf(asset.target))
            }
            if (retained == null) delay(250L)
        }
        retained
    }

/** Reads the original opaque references with fixture acquisition disabled after upgrade and cold restart. */
internal suspend fun verifyUpgradeAvatar(
    runtime: Marmot,
    receipt: JSONObject,
) {
    val avatars = receipt.getJSONArray("avatars")
    check(avatars.length() == 2)
    repeat(avatars.length()) { index ->
        val avatar = avatars.getJSONObject(index)
        val payload =
            runtime.readAvatarAssets(
                avatar.getString("account"),
                listOf(avatar.getString("reference")),
                1_048_576uL,
            ).single()
        check(!payload.deferred && payload.availability == AvatarAvailabilityFfi.READY)
        check(avatarDigest(payload.bytes) == avatar.getString("sha256")) { "Migrated avatar bytes changed or vanished" }
    }
}

/** Compares only generated image content; account identifiers and native descriptors stay in the private receipt. */
private fun avatarDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
