package dev.ipf.whitenoise.android.state

import org.json.JSONArray
import org.json.JSONObject

/** Device-local UI prototype. MDK must eventually own full-account evaluation before paging. */
sealed interface SmartFolderFilter {
    data class Group(
        val all: Boolean = true,
        val children: List<SmartFolderFilter> = emptyList(),
        val not: Boolean = false,
    ) : SmartFolderFilter

    data class Condition(
        val field: FolderField,
        val mode: FolderMode = FolderMode.PRESENT,
        val values: Set<String> = emptySet(),
        val not: Boolean = false,
    ) : SmartFolderFilter
}

enum class FolderField {
    UNREAD,
    MENTIONS,
    PARTICIPANTS,
    DRAFT,
    PENDING_SEND,
    MUTED,
    ARCHIVED,
    ACCEPTED,
    TYPE,
    TITLE,
    PINNED,
}

enum class FolderMode { PRESENT, NONE, ANY_OF, ALL_OF, EXCLUDES, DIRECT, GROUP, CONTAINS }

/** Strict, bounded envelope; a bad subtree invalidates the whole automatic rule, never just that branch. */
object SmartFolderCodec {
    const val PUBKEY_HEX_LENGTH = 64
    const val MAX_DEPTH = 4
    const val MAX_NODES = 64
    const val MAX_VALUES = 64
    const val MAX_TEXT = 256
    const val MAX_BYTES = 65536

    fun encode(filter: SmartFolderFilter.Group): String =
        JSONObject()
            .put(
                "version",
                1,
            ).put(
                "root",
                nodeJson(filter),
            ).toString()

    fun decode(raw: String): SmartFolderFilter.Group? =
        runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val json = JSONObject(raw)
            require(json.get("version") == 1)
            val root = parseNode(json.getJSONObject("root"), 0, intArrayOf(0)) as SmartFolderFilter.Group
            root
        }.getOrNull()

    fun valid(filter: SmartFolderFilter.Group): Boolean =
        decode(encode(filter)) == filter &&
            filter.children.all(::completeNode)

    private fun completeNode(node: SmartFolderFilter): Boolean =
        when (node) {
            is SmartFolderFilter.Condition -> true
            is SmartFolderFilter.Group -> node.children.isNotEmpty() && node.children.all(::completeNode)
        }

    private fun nodeJson(node: SmartFolderFilter): JSONObject =
        when (node) {
            is SmartFolderFilter.Group ->
                JSONObject()
                    .put("kind", "group")
                    .put("all", node.all)
                    .put("not", node.not)
                    .put("children", JSONArray(node.children.map(::nodeJson)))
            is SmartFolderFilter.Condition ->
                JSONObject()
                    .put("kind", "condition")
                    .put("field", node.field.name)
                    .put("mode", node.mode.name)
                    .put("values", JSONArray(node.values.toList()))
                    .put("not", node.not)
        }

    private fun parseNode(
        json: JSONObject,
        depth: Int,
        count: IntArray,
    ): SmartFolderFilter {
        require(depth <= MAX_DEPTH && ++count[0] <= MAX_NODES)
        val not = json.get("not") as Boolean
        return when (json.getString("kind")) {
            "group" -> {
                val children = json.getJSONArray("children")
                require(children.length() <= MAX_NODES)
                SmartFolderFilter.Group(
                    json.get("all") as Boolean,
                    (0 until children.length()).map {
                        parseNode(
                            children.getJSONObject(it),
                            depth + 1,
                            count,
                        )
                    },
                    not,
                )
            }
            "condition" -> {
                val field = FolderField.valueOf(json.getString("field"))
                val mode = FolderMode.valueOf(json.getString("mode"))
                val array = json.getJSONArray("values")
                require(array.length() <= MAX_VALUES)
                val values = (0 until array.length()).map { array.getString(it) }.toSet()
                require(values.size == array.length())
                validateCondition(field, mode, values)
                SmartFolderFilter.Condition(field, mode, values, not)
            }
            else -> error("Unknown node")
        }
    }

    private fun validateCondition(
        field: FolderField,
        mode: FolderMode,
        values: Set<String>,
    ) {
        when (field) {
            FolderField.PARTICIPANTS -> {
                require(mode in setOf(FolderMode.ANY_OF, FolderMode.ALL_OF, FolderMode.EXCLUDES))
                require(
                    values.isNotEmpty() &&
                        values.all {
                            it.length == PUBKEY_HEX_LENGTH &&
                                it.all { c ->
                                    c in '0'..'9' ||
                                        c in 'a'..'f'
                                }
                        },
                )
            }
            FolderField.TITLE ->
                require(
                    mode == FolderMode.CONTAINS &&
                        values.size == 1 &&
                        values.single().isNotBlank() &&
                        values.single().length <= MAX_TEXT,
                )
            FolderField.TYPE ->
                require(
                    mode in
                        setOf(
                            FolderMode.DIRECT,
                            FolderMode.GROUP,
                        ) &&
                        values.isEmpty(),
                )
            else -> require(mode in setOf(FolderMode.PRESENT, FolderMode.NONE) && values.isEmpty())
        }
    }
}

/** Single source selection for every folder consumer. An advanced view uses only already-loaded rows. */
internal fun chatFolderSource(
    rule: ChatFolderRule?,
    active: List<ChatListItem>,
    archived: List<ChatListItem>,
): List<ChatListItem> =
    when {
        rule?.smartFilter != null -> sortChatListItems((active + archived).distinctBy { it.foldedId })
        rule?.archivedOnly == true -> archived
        else -> active
    }

/** Used only for unresolved-roster presentation; matching remains unknown until authoritative data arrives. */
internal fun SmartFolderFilter.references(field: FolderField): Boolean =
    when (this) {
        is SmartFolderFilter.Group -> children.any { it.references(field) }
        is SmartFolderFilter.Condition -> this.field == field
    }
