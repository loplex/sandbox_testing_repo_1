package cz.loplex.inspectionfilter

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.Tools
import com.intellij.openapi.project.Project

/*
 * Works around a bug that a filtered run hits in IntelliJ IDEA 2025.3 (build 253) with the bundled Grazie.
 *
 * Grazie checks grammar and style with one inspection, the runner, which reports what it finds under the names of two
 * others, its children: "Grammar error" and "Style suggestion". The children have no visitor of their own. In 2025.3:
 * - the runner decides whether to check by whether its children are on in the project's profile, not in the profile of
 *   the run;
 * - the run looks up the inspection a problem is reported under without checking that it is part of the run
 *   (`GlobalInspectionContextImpl.inspectFile`), so a name it does not know fails with an IDE error, and the rest of
 *   that file's results are lost;
 * - nothing declares the runner the children's main tool (`InspectionProfileEntry.getMainToolId`), so the platform
 *   does not add the runner to a run that has a child in it.
 *
 * The runner is a warning and its children are not, so filtering by severity alone breaks the run both ways: errors and
 * warnings keep the runner and drop the children, and "Grammar error" alone drops the runner, which leaves the child
 * with nothing to report. Here the runner is therefore on in a filtered run exactly when one of its children is.
 * Filtering by group cannot split them: the runner and its children are all in Proofreading.
 *
 * That still leaves a choice of one child without the other, while the other is on in the project's profile: the runner
 * reports under both. The dialog warns about it (see [filterBreaksGrazieRunner]).
 *
 * From 2026.1 (build 261) on, the children name the runner as their main tool, Grazie reads the profile of the run, and
 * the lookup no longer fails, so none of this applies there.
 */

private const val FIRST_FIXED_BASELINE_VERSION = 261

internal const val GRAZIE_RUNNER = "GrazieInspectionRunner"
private val GRAZIE_RUNNER_CHILDREN = listOf("GrazieInspection", "GrazieStyle")

/** Whether there is anything to work around: a build before 261, with Grazie in [profile]. */
internal fun grazieRunnerWorkaroundApplies(profile: InspectionProfileImpl, project: Project, baselineVersion: Int): Boolean =
    baselineVersion < FIRST_FIXED_BASELINE_VERSION && profile.getToolsOrNull(GRAZIE_RUNNER, project) != null

/**
 * Whether the Grazie runner is to be on in a copy of [profile] filtered by [filter]: when any of its children is, and
 * its own group is included. Its own severity and properties are not asked, as it reports nothing of its own. A runner
 * switched off in [profile] stays off.
 *
 * Null where the runner is left to the filter like any other inspection: from build 261 on, or with Grazie not in
 * [profile].
 */
internal fun grazieRunnerEnabled(
    profile: InspectionProfileImpl,
    filter: InspectionFilter,
    project: Project,
    baselineVersion: Int,
    baseProfile: InspectionProfileImpl = InspectionProfileImpl.BASE_PROFILE.get(),
): Boolean? {
    if (!grazieRunnerWorkaroundApplies(profile, project, baselineVersion)) return null
    val runner = profile.getToolsOrNull(GRAZIE_RUNNER, project)!!
    return runner.isEnabled && filter.groups.includes(runner.groupPath) &&
           GRAZIE_RUNNER_CHILDREN.any { profile.getToolsOrNull(it, project)?.isKeptBy(filter, baseProfile) == true }
}

/**
 * Whether a run of [profile] filtered by [filter] hits the bug: the runner runs, and reports under a child that the
 * filter has switched off. Which children it reports under is decided by [projectProfile], whichever profile the run
 * is given.
 */
internal fun filterBreaksGrazieRunner(
    profile: InspectionProfileImpl,
    projectProfile: InspectionProfileImpl,
    filter: InspectionFilter,
    project: Project,
    baselineVersion: Int,
    baseProfile: InspectionProfileImpl = InspectionProfileImpl.BASE_PROFILE.get(),
): Boolean {
    if (grazieRunnerEnabled(profile, filter, project, baselineVersion, baseProfile) != true) return false
    return GRAZIE_RUNNER_CHILDREN.any { child ->
        projectProfile.getToolsOrNull(child, project)?.isEnabled == true &&
        profile.getToolsOrNull(child, project)?.isKeptBy(filter, baseProfile) != true
    }
}

/** The Grazie runner's children as they are in [profile], to name them to the user. */
internal fun grazieRunnerChildren(profile: InspectionProfileImpl, project: Project): List<Tools> =
    GRAZIE_RUNNER_CHILDREN.mapNotNull { profile.getToolsOrNull(it, project) }
