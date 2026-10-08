package cz.loplex.inspectionfilter

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * Forgets the filter stored in [project], for the tests that store one: a light project, and what is stored in it,
 * outlives the test, and is shared with the tests of other classes.
 */
internal fun forgetStoredFilter(project: Project) {
    val properties = PropertiesComponent.getInstance(project)
    for (key in SEVERITY_SELECTION_KEYS + GROUP_SELECTION_KEYS + PROPERTY_SELECTION_KEYS + PROBLEM_FILTER_KEYS) {
        properties.unsetValue(key)
        // Lists are kept apart from values, and unsetValue leaves them be.
        properties.setList(key, null)
    }
}
