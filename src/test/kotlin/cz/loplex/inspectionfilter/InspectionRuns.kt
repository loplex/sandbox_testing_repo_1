package cz.loplex.inspectionfilter

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.actions.CodeInspectionAction
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.util.ui.UIUtil
import java.lang.reflect.InvocationTargetException

/**
 * Runs the inspections of [profile] over the whole of [project] the way "Inspect Code" runs them, and passes what
 * they reported to [read] before the context lets go of it. [setUp] may set the context up before the run.
 */
internal fun <T> runInspections(
    project: Project,
    profile: InspectionProfileImpl,
    read: (GlobalInspectionContextImpl) -> T,
    setUp: (GlobalInspectionContextImpl) -> Unit = {},
): T {
    val context = (InspectionManager.getInstance(project) as InspectionManagerEx).createNewGlobalContext()
    try {
        context.setExternalProfile(profile)
        setUp(context)
        runAndWait(context, AnalysisScope(project))
        return read(context)
    }
    finally {
        context.cleanup()
    }
}

/** Runs [context] over [scope], as "Rerun" in the results runs it again, and waits for the run to end. */
internal fun runAndWait(context: GlobalInspectionContextImpl, scope: AnalysisScope) {
    IndexingTestUtil.waitUntilIndexesAreReady(context.project)
    context.doInspections(scope)
    // In a unit test the run is launched on the event queue, and then runs to its end without leaving it.
    UIUtil.dispatchAllInvocationEvents()
}

/** Every problem [context]'s last run reported, with the inspection that reported it. */
internal fun problemsOf(context: GlobalInspectionContextImpl): List<Pair<InspectionToolWrapper<*, *>, CommonProblemDescriptor>> =
    // An inspection with another severity in a scope has a tool of its own there, which reports on its own.
    context.tools.values.flatMap { tools -> tools.tools.map { it.tool } }.distinct().flatMap { wrapper ->
        context.getPresentation(wrapper).problemDescriptors.map { wrapper to it }
    }

/**
 * Runs [action] over [scope] from its own entry point, which it reaches from its dialog, and waits for the run to end.
 * The context it ran in is the one [InspectionManagerEx.getRunningContexts] has gained.
 */
internal fun runAction(action: CodeInspectionAction, project: Project, scope: AnalysisScope) {
    val method = CodeInspectionAction::class.java.getDeclaredMethod("runInspections", Project::class.java, AnalysisScope::class.java)
    method.isAccessible = true
    try {
        method.invoke(action, project, scope)
    }
    catch (e: InvocationTargetException) {
        throw e.targetException
    }
    UIUtil.dispatchAllInvocationEvents()
}

/**
 * Makes [profile] the one of [project] until [disposable] is disposed, as the action runs with it when no other is
 * chosen.
 */
internal fun useAsProjectProfile(project: Project, profile: InspectionProfileImpl, disposable: Disposable) {
    val manager = ProjectInspectionProfileManager.getInstance(project)
    val previous = manager.currentProfile
    manager.addProfile(profile)
    manager.setCurrentProfile(profile)
    Disposer.register(disposable) {
        manager.setCurrentProfile(previous)
        manager.deleteProfile(profile)
    }
}
