package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.state.BoundedNpubCache
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Existing synthetic public key used by the group header fixtures; no native formatter is needed. */
internal fun WhiteNoiseAppState.withGroupReviewActorNpub(): WhiteNoiseAppState {
    val field = WhiteNoiseAppState::class.java.getDeclaredField("npubs")
    field.isAccessible = true
    val cache = field.get(this) as BoundedNpubCache
    cache.put(GROUP_REVIEW_ACTOR_HEX, GROUP_REVIEW_ACTOR_NPUB)
    // Match the shared picker fixture's a0 public key as well.
    cache.put("a0".repeat(32), "npub15zs2pg9q5zs2pg9q5zs2pg9q5zs2pg9q5zs2pg9q5zs2pg9q5zsqun85q9")
    return this
}

internal const val GROUP_REVIEW_ACTOR_HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val GROUP_REVIEW_ACTOR_NPUB = "npub1424242424242424242424242424242424242424242424242424qamrcaj"

internal const val REVIEW_FIRST_MEMBER_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
internal const val REVIEW_FIRST_MEMBER_NPUB = "npub1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0st5hsmq"
internal const val REVIEW_SECOND_MEMBER_HEX = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
internal const val REVIEW_SECOND_MEMBER_NPUB = "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg"
