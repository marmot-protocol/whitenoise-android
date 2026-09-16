package dev.ipf.whitenoise.android.state

/** How many fresh pseudonyms are drawn before accepting a repeat of the excluded name. */
private const val PSEUDONYM_DRAWS = 8

/** Draws a pseudonym from [generate], retrying a few times while it repeats [excluding]. */
internal fun pickPseudonym(
    excluding: String?,
    generate: () -> String,
): String {
    var candidate = generate()
    var draws = 1
    while (candidate == excluding && draws < PSEUDONYM_DRAWS) {
        candidate = generate()
        draws += 1
    }
    return candidate
}

/** A fresh two-word display name from MDK's shared pseudonym generator that avoids the current name (#1584). */
internal fun WhiteNoiseAppState.randomProfilePseudonym(excluding: String?): String {
    val engine = marmot()
    return pickPseudonym(excluding) { engine.randomProfilePseudonym() }
}
