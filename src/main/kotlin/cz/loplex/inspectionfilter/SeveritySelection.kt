package cz.loplex.inspectionfilter

import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.ui.LevelChooserAction

private const val SELECTED_SEVERITIES_KEY = "cz.loplex.inspectionfilter.selectedSeverities"
private const val SEVERITY_MODE_KEY = "cz.loplex.inspectionfilter.severityMode"
private const val LOWEST_SEVERITY_KEY = "cz.loplex.inspectionfilter.lowestSeverity"

/** Every key the choice is stored under in a project's [PropertiesComponent]. */
internal val SEVERITY_SELECTION_KEYS = listOf(SELECTED_SEVERITIES_KEY, SEVERITY_MODE_KEY, LOWEST_SEVERITY_KEY)

/** Chosen when nothing has been chosen yet, or when none of what was chosen exists any more. */
private val DEFAULT_SEVERITY_NAMES = setOf(HighlightSeverity.ERROR.name, HighlightSeverity.WARNING.name)

/** How the severities to report are chosen. */
internal enum class SeverityMode {
    /** Those ticked one by one. */
    SELECTED,

    /** One, and every severity above it. */
    THIS_AND_HIGHER,
}

/**
 * The severities an inspection can be given, from the highest down, custom ones included: the order the inspection
 * settings list them in, and the one "Edit Severities" lets the user change.
 *
 * "No highlighting" is left out: a batch run skips inspections at that level whatever the profile says, so offering
 * it would offer nothing.
 */
internal fun availableSeverities(project: Project): List<HighlightSeverity> =
    LevelChooserAction.getSeverities(SeverityRegistrar.getSeverityRegistrar(project), false)

/** The names of the severities a run reports, by the mode last chosen in [project]. */
internal fun loadReportedSeverityNames(project: Project): Set<String> =
    when (loadSeverityMode(project)) {
        SeverityMode.SELECTED -> loadSelectedSeverityNames(project)
        SeverityMode.THIS_AND_HIGHER -> severityNamesFrom(project, loadLowestSeverityName(project))
    }

/** The names of [lowestName] and of every severity above it, from the highest down. */
internal fun severityNamesFrom(project: Project, lowestName: String): Set<String> {
    val registrar = SeverityRegistrar.getSeverityRegistrar(project)
    val severities = availableSeverities(project)
    val lowest = severities.first { it.name == lowestName }
    return severities.filter { registrar.compare(it, lowest) >= 0 }.mapTo(LinkedHashSet()) { it.name }
}

// All of the following is kept per project, because custom severities can be defined per project too.

internal fun loadSeverityMode(project: Project): SeverityMode {
    val stored = PropertiesComponent.getInstance(project).getValue(SEVERITY_MODE_KEY)
    return SeverityMode.entries.firstOrNull { it.name == stored } ?: SeverityMode.SELECTED
}

internal fun storeSeverityMode(project: Project, mode: SeverityMode) {
    PropertiesComponent.getInstance(project).setValue(SEVERITY_MODE_KEY, mode.name)
}

/** The names of the severities last ticked in [project], among those that still exist. */
internal fun loadSelectedSeverityNames(project: Project): Set<String> {
    val available = availableSeverities(project).mapTo(HashSet()) { it.name }
    val stored = PropertiesComponent.getInstance(project).getList(SELECTED_SEVERITIES_KEY).orEmpty()
    return stored.filterTo(LinkedHashSet()) { it in available }
        .ifEmpty { DEFAULT_SEVERITY_NAMES.filterTo(LinkedHashSet()) { it in available } }
}

internal fun storeSelectedSeverityNames(project: Project, names: Collection<String>) {
    PropertiesComponent.getInstance(project).setList(SELECTED_SEVERITIES_KEY, names)
}

/** The name of the severity last chosen in [project] as the lowest to report, or of Warning if it no longer exists. */
internal fun loadLowestSeverityName(project: Project): String {
    val stored = PropertiesComponent.getInstance(project).getValue(LOWEST_SEVERITY_KEY)
    return stored?.takeIf { name -> availableSeverities(project).any { it.name == name } } ?: HighlightSeverity.WARNING.name
}

internal fun storeLowestSeverityName(project: Project, name: String) {
    PropertiesComponent.getInstance(project).setValue(LOWEST_SEVERITY_KEY, name)
}
