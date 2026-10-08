package cz.loplex.inspectionfilter

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.Tools
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

private const val GROUP_OVERRIDES_KEY = "cz.loplex.inspectionfilter.groupOverrides"

/** Every key the choice of groups is stored under in a project's [PropertiesComponent]. */
internal val GROUP_SELECTION_KEYS = listOf(GROUP_OVERRIDES_KEY)

/**
 * A group of inspections as the inspection settings show it, from the top down: `["Java", "Probable bugs"]`.
 *
 * Made of display names, as there is nothing else to name a group by: a group declared only by its path in a plugin's
 * descriptor has no key. A choice stored in one language of the IDE is therefore lost in another.
 */
internal typealias GroupPath = List<String>

/** The group these inspections are listed under in the inspection settings. */
internal val Tools.groupPath: GroupPath
    // From the plugin's descriptor where it says, so without creating the inspection.
    get() = tool.groupPath.asList()

/**
 * Which groups of inspections a run reports.
 *
 * Only where a group differs from the group it belongs to is stored, in [overrides]; any other group is as its nearest
 * stored parent, and a top-level group with none is included. A group that appears later, with a plugin say, therefore
 * follows the choice made for its parent, and a group that the profile at hand does not have keeps its choice for a
 * profile that has it.
 */
internal class GroupSelection(val overrides: Map<GroupPath, Boolean> = emptyMap()) {

    /** Whether inspections listed directly under [path] are reported. */
    fun includes(path: GroupPath): Boolean {
        for (length in path.size downTo 1) {
            overrides[path.subList(0, length)]?.let { return it }
        }
        return true
    }

    /**
     * This selection with [node] and every group under it, [included] or not.
     *
     * A group with no inspections of its own is just the sum of the groups under it, so wherever these now all agree,
     * it takes their state too; a group added under it later then follows them.
     */
    fun with(node: GroupNode, included: Boolean): GroupSelection {
        val changed = overrides.filterKeys { !it.startsWith(node.path) }.toMutableMap()
        changed[node.path] = included
        var parent = node.parent
        while (parent != null && !parent.hasOwnInspections) {
            val group = parent
            val states = group.children.mapTo(HashSet()) { GroupSelection(changed).includes(it.path) }
            val state = states.singleOrNull() ?: break
            changed.keys.removeIf { it.startsWith(group.path) }
            changed[group.path] = state
            parent = group.parent
        }
        return GroupSelection(changed).minimal()
    }

    /** This selection without the overrides that say what their group would be anyway. */
    private fun minimal(): GroupSelection {
        val kept = HashMap<GroupPath, Boolean>()
        // Parents first: whether a group's override is needed depends only on those above it.
        for ((path, included) in overrides.entries.sortedBy { it.key.size }) {
            if (GroupSelection(kept).includes(path.dropLast(1)) != included) {
                kept[path] = included
            }
        }
        return GroupSelection(kept)
    }

    /** Whether any inspection of the groups in [roots] is reported. */
    fun includesAny(roots: List<GroupNode>): Boolean =
        roots.any { root -> root.selfAndDescendants().any { it.hasOwnInspections && includes(it.path) } }

    /**
     * What is reported of the groups in [roots], in as few groups as it can be said in: those wholly reported, and
     * those wholly left out. A group whose own inspections differ from the groups under it is listed as [Part.OWN].
     */
    fun summary(roots: List<GroupNode>): Summary {
        val included = ArrayList<Part>()
        val excluded = ArrayList<Part>()

        fun visit(node: GroupNode) {
            when (coverage(node)) {
                true -> included += Part(node.path, whole = true)
                false -> excluded += Part(node.path, whole = true)
                null -> {
                    if (node.hasOwnInspections) {
                        (if (includes(node.path)) included else excluded) += Part(node.path, whole = false)
                    }
                    node.children.forEach(::visit)
                }
            }
        }
        roots.forEach(::visit)
        return Summary(included, excluded)
    }

    /** True when every inspection under [node] is reported, false when none is, null for a mix. */
    private fun coverage(node: GroupNode): Boolean? =
        node.selfAndDescendants().filter { it.hasOwnInspections }.map { includes(it.path) }.distinct().singleOrNull()

    override fun equals(other: Any?): Boolean = other is GroupSelection && other.overrides == overrides

    override fun hashCode(): Int = overrides.hashCode()

    override fun toString(): String = "GroupSelection($overrides)"

    /** A group, or only its own inspections when [whole] is false. */
    data class Part(val path: GroupPath, val whole: Boolean)

    data class Summary(val included: List<Part>, val excluded: List<Part>)
}

private fun GroupPath.startsWith(prefix: GroupPath): Boolean = size >= prefix.size && subList(0, prefix.size) == prefix

/**
 * A group of inspections in a profile. [hasOwnInspections] says whether any are listed directly under it, rather than
 * only under the groups in [children].
 */
internal class GroupNode(val path: GroupPath, val parent: GroupNode?) {
    var hasOwnInspections: Boolean = false
        private set
    val children: List<GroupNode> get() = childrenByName.values.toList()
    private val childrenByName = sortedMapOf<String, GroupNode>(BY_NAME)

    val name: String get() = path.last()

    fun selfAndDescendants(): Sequence<GroupNode> = sequenceOf(this) + children.asSequence().flatMap { it.selfAndDescendants() }

    companion object {
        private val BY_NAME = String.CASE_INSENSITIVE_ORDER.thenComparing(naturalOrder())

        /** The groups of [paths] and every group above them, as trees sorted by name like the inspection settings. */
        fun treesOf(paths: Iterable<GroupPath>): List<GroupNode> {
            val roots = sortedMapOf<String, GroupNode>(BY_NAME)
            for (path in paths) {
                if (path.isEmpty()) continue
                var node = roots.getOrPut(path[0]) { GroupNode(path.take(1), null) }
                for (length in 2..path.size) {
                    val parent = node
                    node = parent.childrenByName.getOrPut(path[length - 1]) { GroupNode(path.take(length), parent) }
                }
                node.hasOwnInspections = true
            }
            return roots.values.toList()
        }
    }
}

/** The groups whose name matches the search [text], without those under a group that matches too; [roots] for none. */
internal fun foundGroups(roots: List<GroupNode>, text: String): List<GroupNode> =
    roots.flatMap { root ->
        if (root.name.contains(text, ignoreCase = true)) listOf(root) else foundGroups(root.children, text)
    }

/** The groups of the inspections switched on in [profile]. */
internal fun groupTreesOf(profile: InspectionProfileImpl, project: Project): List<GroupNode> =
    GroupNode.treesOf(profile.getAllEnabledInspectionTools(project).map { it.groupPath })

// Kept per project, like the severities.

/** The choice of groups last made in [project]. */
internal fun loadGroupSelection(project: Project): GroupSelection {
    val stored = PropertiesComponent.getInstance(project).getList(GROUP_OVERRIDES_KEY).orEmpty()
    return GroupSelection(stored.mapNotNull(::decodeOverride).toMap())
}

internal fun storeGroupSelection(project: Project, selection: GroupSelection) {
    PropertiesComponent.getInstance(project).setList(GROUP_OVERRIDES_KEY, selection.overrides.map { (path, included) ->
        encodeOverride(path, included)
    })
}

/**
 * `+` or `-` for included or not, then the path, its names joined by `/`. A backslash in a name escapes the next
 * character, so that a name may hold `/` itself.
 */
internal fun encodeOverride(path: GroupPath, included: Boolean): String =
    (if (included) "+" else "-") + path.joinToString("/") { it.replace("\\", "\\\\").replace("/", "\\/") }

/** The path and state [encodeOverride] made [encoded] of, or null if it did not make it. */
internal fun decodeOverride(encoded: String): Pair<GroupPath, Boolean>? {
    val included = when (encoded.firstOrNull()) {
        '+' -> true
        '-' -> false
        else -> return null
    }
    val names = ArrayList<String>()
    val name = StringBuilder()
    var escaped = false
    for (char in encoded.substring(1)) {
        when {
            escaped -> {
                name.append(char)
                escaped = false
            }
            char == '\\' -> escaped = true
            char == '/' -> {
                names += name.toString()
                name.clear()
            }
            else -> name.append(char)
        }
    }
    names += name.toString()
    return if (escaped) null else names to included
}
