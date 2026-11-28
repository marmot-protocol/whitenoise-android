package dev.ipf.whitenoise.android.ui.navigation

import androidx.lifecycle.SavedStateHandle
import java.util.UUID

private const val SECTION_ACCOUNT_KEY = "main_shell_section_account"
private const val SECTION_RUNTIME_KEY = "main_shell_section_runtime"
private const val SECTION_PROCESS_KEY = "main_shell_section_process"
private const val SECTION_NAME_KEY = "main_shell_section_name"
private const val SECTION_DETAIL_KEY = "main_shell_section_detail"
private const val SECTION_CHAIN_KEY = "main_shell_section_settings_chain"
private val PROCESS_EPOCH = UUID.randomUUID().toString()

/**
 * The Activity's saved copy of the route's section, written from [navigator] and read back into it once.
 *
 * Destination enum names reach [SavedStateHandle], under the same keys and with the same account, runtime-generation
 * and process-epoch rules as before the navigator owned the route, so the section is retained while the protected
 * shell is absent behind app lock and restored after process death for the same account and runtime. The Settings
 * pages the person came through, each with its item argument, are saved beside them, so Back after a restore still
 * returns to the page that opened the open one (#4095). The section itself is read from [navigator] and changed only
 * by its events.
 */
internal class MainShellSectionState(
    private val savedState: SavedStateHandle,
    internal val navigator: ShellNavigator = ShellNavigator(),
) {
    private var accountRef = savedState.get<Any>(SECTION_ACCOUNT_KEY) as? String
    private var runtimeGeneration = savedState.get<Any>(SECTION_RUNTIME_KEY) as? Int
    private var runtimeProcess = savedState.get<Any>(SECTION_PROCESS_KEY) as? String
    private var savedSection = validSection(savedState.get<Any>(SECTION_NAME_KEY) as? String)
    private var savedDetail = validDetail(savedState.get<Any>(SECTION_DETAIL_KEY) as? String)
    private var savedChain = (savedState.get<Any>(SECTION_CHAIN_KEY) as? List<*>)?.filterIsInstance<String>()

    // This holder's saved section reaches the process-retained navigator once, at its first bind.
    private var restored = false

    // The one listener this holder attaches, kept so it can detach exactly that one.
    private val persistence: (List<ShellRoute>) -> Unit = { persist(it) }

    init {
        navigator.attachPersistence(persistence)
    }

    /** The open section's name, read from the route stack. */
    val sectionName: String
        get() = sectionProjection(navigator.stack).first.name

    /** The open Settings screen's name while Settings or Diagnostics is open, read from the route stack. */
    val settingsDetailName: String?
        get() = sectionProjection(navigator.stack).second?.name

    /** The open Settings pages, home first, read from the route stack. Empty while Chats is the section. */
    val settingsStack: List<ShellRoute.Settings>
        get() = settingsEntries(navigator.stack)

    /** Whether an account owns the section, without which master ignores every section write. */
    val hasOwner: Boolean
        get() = accountRef != null

    /**
     * Bind before rendering, after the route owner has been rebound: account changes and same-process runtime
     * replacement clear the section, and this holder's saved section is handed to [navigator] the first time.
     */
    fun bind(
        ownerAccountRef: String?,
        ownerRuntimeGeneration: Int,
    ) {
        if (ownerAccountRef == null) {
            clear()
        } else if (
            accountRef != ownerAccountRef ||
            (runtimeProcess == PROCESS_EPOCH && runtimeGeneration != ownerRuntimeGeneration)
        ) {
            clear()
            bindOwner(ownerAccountRef, ownerRuntimeGeneration)
        } else if (runtimeProcess != PROCESS_EPOCH) {
            // A counter restarting after process death must not pop a valid same-account destination.
            bindOwner(ownerAccountRef, ownerRuntimeGeneration)
        }
        if (!restored) {
            restored = true
            val section = mainSectionOf(savedSection)
            navigator.dispatch(
                ShellNavEvent.SectionRestored(section, settingsDetailOf(savedDetail), decodeSettingsChain(savedChain)),
            )
        }
    }

    /** Sends a Settings-only [event] to the navigator, dropped while no account owns the section, as on master. */
    fun dispatchSection(event: ShellNavEvent.SectionOnly) {
        if (hasOwner) navigator.dispatch(event)
    }

    /** What differs between the saved section and the stack's, or null when the Activity would save the stack's. */
    fun persistenceProblem(): String? {
        if (!restored || accountRef == null) return null
        val (section, detail) = sectionProjection(navigator.stack)
        return when {
            section.name != savedSection -> "saved section $savedSection, stack section ${section.name}"
            detail?.name != savedDetail -> "saved detail $savedDetail, stack detail ${detail?.name}"
            else -> null
        }
    }

    private fun bindOwner(
        ownerAccountRef: String,
        ownerRuntimeGeneration: Int,
    ) {
        accountRef = ownerAccountRef
        runtimeGeneration = ownerRuntimeGeneration
        runtimeProcess = PROCESS_EPOCH
        savedState[SECTION_ACCOUNT_KEY] = ownerAccountRef
        savedState[SECTION_RUNTIME_KEY] = ownerRuntimeGeneration
        savedState[SECTION_PROCESS_KEY] = PROCESS_EPOCH
    }

    /**
     * Writes the section of [stack] under the saved keys, only once this holder's own saved section has been handed
     * to the navigator and only while an account owns it, and only the names that changed.
     */
    private fun persist(stack: List<ShellRoute>) {
        if (!restored || accountRef == null) return
        val (section, detail) = sectionProjection(stack)
        if (section.name != savedSection) {
            savedSection = section.name
            savedState[SECTION_NAME_KEY] = savedSection
        }
        if (detail?.name != savedDetail) {
            savedDetail = detail?.name
            savedState[SECTION_DETAIL_KEY] = savedDetail
        }
        val chain = encodeSettingsChain(settingsEntries(stack))
        if (chain != savedChain) {
            savedChain = chain
            savedState[SECTION_CHAIN_KEY] = chain?.let(::ArrayList)
        }
    }

    /**
     * Stops saving the stack into this holder's saved state, once its Activity is gone for good. The stack and the
     * saved keys stay as they are, so a newer holder that already took the persistence over keeps it.
     */
    fun detach() {
        navigator.detachPersistence(persistence)
    }

    /** Terminal navigation and explicitly released holders discard the lightweight saved route. */
    fun clear() {
        accountRef = null
        runtimeGeneration = null
        runtimeProcess = null
        savedSection = MainSection.Chats.name
        savedDetail = null
        savedChain = null
        savedState.remove<String>(SECTION_ACCOUNT_KEY)
        savedState.remove<Int>(SECTION_RUNTIME_KEY)
        savedState.remove<String>(SECTION_PROCESS_KEY)
        savedState.remove<String>(SECTION_NAME_KEY)
        savedState.remove<String>(SECTION_DETAIL_KEY)
        savedState.remove<ArrayList<String>>(SECTION_CHAIN_KEY)
        if (sectionProjection(navigator.stack).first != MainSection.Chats) {
            navigator.dispatch(ShellNavEvent.ExitSettings)
        }
    }

    private fun validSection(value: String?): String {
        val entry = MainSection.entries.firstOrNull { it.name == value }
        return entry?.name ?: MainSection.Chats.name
    }

    private fun validDetail(value: String?): String? = SettingsDetail.entries.firstOrNull { it.name == value }?.name
}

/** Separates a saved page's enum name from its argument; no [SettingsDetail] name contains it. */
private const val SETTINGS_CHAIN_ARG_SEPARATOR = '='

/** The pages of [entries] below home as `Name` or `Name=arg`, or null when only home is open. */
internal fun encodeSettingsChain(entries: List<ShellRoute.Settings>): List<String>? =
    entries
        .mapNotNull { entry ->
            entry.detail?.let { detail ->
                entry.arg?.let { "${detail.name}$SETTINGS_CHAIN_ARG_SEPARATOR$it" } ?: detail.name
            }
        }.ifEmpty { null }

/** Reads back [encodeSettingsChain]'s form, or null when any page is no longer a known destination. */
internal fun decodeSettingsChain(saved: List<String>?): List<ShellRoute.Settings>? =
    saved?.map { entry ->
        val name = entry.substringBefore(SETTINGS_CHAIN_ARG_SEPARATOR)
        val detail = settingsDetailOf(name) ?: return null
        ShellRoute.Settings(detail, entry.substringAfter(SETTINGS_CHAIN_ARG_SEPARATOR, "").ifEmpty { null })
    }
