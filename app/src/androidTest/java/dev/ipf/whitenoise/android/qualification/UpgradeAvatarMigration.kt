package dev.ipf.whitenoise.android.qualification

import dev.ipf.marmotkit.AvatarAvailabilityFfi
import dev.ipf.marmotkit.Marmot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val FIXTURE_AVATAR_URL =
    "https://raw.githubusercontent.com/marmot-protocol/whitenoise-android/" +
        "512dcf241e155ff71d6751b0d06d9cb8b9830cd4/app/src/main/res/mipmap-mdpi/ic_launcher.png"
private const val FIXTURE_AVATAR_SHA256 = "a447905dfc5868e09924d9539d05832bdf058863a144a73476f67741b315d6a6"

/** Seeds a pinned public HTTPS image through MDK; the runner blocks public egress before any upgrade reads. */
internal suspend fun seedUpgradeAvatar(
    runtime: Marmot,
    receipt: File,
) {
    val value = JSONObject(receipt.readText())
    val account = value.getString("sender")
    val group = value.getString("group")
    runtime.updateGroupAvatarUrl(account, group, FIXTURE_AVATAR_URL, null, null)
    val avatars = JSONArray()
    for (owner in listOf(account, value.getString("receiver"))) {
        avatars.put(awaitUpgradeAvatar(runtime, owner, group))
    }
    value.put("avatars", avatars)
    receipt.writeText(value.toString())
    verifyUpgradeAvatar(runtime, value)
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
                check(avatarDigest(payload.bytes) == FIXTURE_AVATAR_SHA256) { "Unexpected pinned avatar content" }
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
            runtime
                .readAvatarAssets(
                    avatar.getString("account"),
                    listOf(avatar.getString("reference")),
                    1_048_576uL,
                ).single()
        check(!payload.deferred && payload.availability == AvatarAvailabilityFfi.READY)
        check(avatarDigest(payload.bytes) == avatar.getString("sha256")) { "Migrated avatar bytes changed or vanished" }
    }
}

/** Compares only image content; account identifiers and native descriptors stay in the private receipt. */
private fun avatarDigest(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
