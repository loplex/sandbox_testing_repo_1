package cz.loplex.inspectionfilter

import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.Tools
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

private const val REQUIRED_PROPERTIES_KEY = "cz.loplex.inspectionfilter.requiredProperties"

/** Every key the choice of properties is stored under in a project's [PropertiesComponent]. */
internal val PROPERTY_SELECTION_KEYS = listOf(REQUIRED_PROPERTIES_KEY)

/**
 * A property an inspection may be required to have, as the inspection settings filter by it: their "Show only cleanup
 * inspections", "Show only modified inspections" and "Show only batch-mode inspections".
 */
internal enum class InspectionProperty(val messageKey: String, val descriptionKey: String) {
    /** One whose fixes "Code Cleanup" applies. */
    CLEANUP("dialog.properties.cleanup", "dialog.properties.cleanup.description"),

    /** One whose settings differ from the defaults in any way: switched on or off, severity, scopes, options. */
    MODIFIED("dialog.properties.modified", "dialog.properties.modified.description"),

    /** A global inspection with nothing to highlight in the editor, so that it is seen in "Inspect Code" only. */
    BATCH_ONLY("dialog.properties.batchOnly", "dialog.properties.batchOnly.description"),
    ;

    /** The name the summary gives it. */
    val shortKey: String get() = "$messageKey.short"
}

/**
 * Whether these tools have every property in [required]. [baseProfile] holds the defaults that [InspectionProperty.MODIFIED]
 * compares to.
 *
 * The inspection settings compare to the base of the profile at hand, which for every profile read from disk is
 * [InspectionProfileImpl.BASE_PROFILE]; only a copy made in the settings and not yet reloaded has another.
 */
internal fun Tools.hasProperties(required: Set<InspectionProperty>, baseProfile: InspectionProfileImpl): Boolean =
    // In the order declared rather than that of [required], so that the question that creates the inspection comes last.
    InspectionProperty.entries.all { property ->
        property !in required || when (property) {
            InspectionProperty.CLEANUP -> tool.isCleanupTool
            // As the inspection settings tell a modified inspection (`InspectionProfileModifiableModel.isProperSetting`).
            InspectionProperty.MODIFIED -> baseProfile.getToolsOrNull(shortName, null) != this
            InspectionProperty.BATCH_ONLY -> (tool as? GlobalInspectionToolWrapper)?.worksInBatchModeOnly() == true
        }
    }

// Kept per project, like the severities.

/** The properties last required in [project]. */
internal fun loadRequiredProperties(project: Project): Set<InspectionProperty> {
    val stored = PropertiesComponent.getInstance(project).getList(REQUIRED_PROPERTIES_KEY).orEmpty()
    return InspectionProperty.entries.filterTo(LinkedHashSet()) { it.name in stored }
}

internal fun storeRequiredProperties(project: Project, properties: Set<InspectionProperty>) {
    PropertiesComponent.getInstance(project).setList(REQUIRED_PROPERTIES_KEY, properties.map { it.name })
}
