package dev.ipf.whitenoise.android.state

internal fun profilePresentationIdsNeedingWarm(
    accountIdHexes: Iterable<String>,
    hasCachedPresentation: (String) -> Boolean,
    hasMaterialization: (String) -> Boolean,
): List<String> =
    accountIdHexes
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .filter { id -> !hasCachedPresentation(id) || hasMaterialization(id) }
        .toList()
