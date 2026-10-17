package dev.ipf.whitenoise.android.ui

/** Per-content link geometry. Hosts retain their own lifetime and snapshot policy. */
internal class MarkdownLinkLayoutRegistry(
    private val layouts: MutableMap<Any, MarkdownLinkTextLayout> = linkedMapOf(),
) {
    val values: Collection<MarkdownLinkTextLayout> get() = layouts.values

    val report: MarkdownLinkTextLayoutReporter = { key, text, layoutResult, coordinates ->
        if (layoutResult != null && coordinates != null) {
            layouts[key] = MarkdownLinkTextLayout(text, layoutResult, coordinates)
        } else {
            layouts.remove(key)
        }
    }
}
