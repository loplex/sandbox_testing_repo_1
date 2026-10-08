package cz.loplex.inspectionfilter

import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.reference.RefElement
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

private const val WITH_QUICK_FIX_KEY = "cz.loplex.inspectionfilter.problems.withQuickFix"
private const val MESSAGE_CONTAINS_KEY = "cz.loplex.inspectionfilter.problems.messageContains"
private const val ON_CHANGED_LINES_KEY = "cz.loplex.inspectionfilter.problems.onChangedLines"

/** Every key the filter of problems is stored under in a project's [PropertiesComponent]. */
internal val PROBLEM_FILTER_KEYS = listOf(WITH_QUICK_FIX_KEY, MESSAGE_CONTAINS_KEY, ON_CHANGED_LINES_KEY)

/**
 * Which of the problems the inspections report are reported: those with a quick fix, if [withQuickFix]; those whose
 * message contains [messageContains], ignoring case, if it is not blank; and those on a line changed since the last
 * commit, if [onChangedLines].
 *
 * Unlike the rest of an [InspectionFilter], this one decides problem by problem rather than inspection by inspection -
 * see [wrapForProblemFilter].
 */
internal data class ProblemFilter(
    val withQuickFix: Boolean = false,
    val messageContains: String = "",
    val onChangedLines: Boolean = false,
) {

    /** Whether it leaves out any problem at all. */
    val isActive: Boolean get() = withQuickFix || messageContains.isNotBlank() || onChangedLines

    /** Whether [problem] is one to report, with [changedLines] the lines changed since the last commit. */
    fun accepts(problem: CommonProblemDescriptor, changedLines: ChangedLines): Boolean =
        (!withQuickFix || !problem.fixes.isNullOrEmpty()) &&
        (messageContains.isBlank() || messageOf(problem).contains(messageContains, ignoreCase = true)) &&
        (!onChangedLines || changedLines.contain(problem))

    /**
     * Whether an element "Unused declaration" shows with no problem reported in it - see [UNUSED_DECLARATION] - is one
     * to report, with [message] what the results say of it and [nameChanged] whether its name is on a changed line.
     * Every such element comes with a quick fix: the results offer "Safe delete", "Comment out" and the like for each
     * one they show, until one of them is applied.
     */
    fun acceptsUnusedDeclaration(message: () -> String, nameChanged: () -> Boolean): Boolean =
        (messageContains.isBlank() || message().contains(messageContains, ignoreCase = true)) &&
        (!onChangedLines || nameChanged())
}

/**
 * [filter] as the runs with one filtered profile apply it, its reruns included: with the lines changed since the last
 * commit that the run in progress found as it started - see [ChangedLinesFinder].
 */
internal class RunProblemFilter(val filter: ProblemFilter) {

    /** The lines changed, as the run in progress found them; none before a run has looked. */
    @Volatile
    var changedLines: ChangedLines = ChangedLines.NONE

    fun accepts(problem: CommonProblemDescriptor): Boolean = filter.accepts(problem, changedLines)

    /** Whether [element], which "Unused declaration" shows with no problem in it, is one to report. */
    fun acceptsUnusedDeclaration(element: RefElement): Boolean =
        filter.acceptsUnusedDeclaration({ UnusedDeclarationMessages.instance?.of(element).orEmpty() },
                                        { changedLines.containNameOf(element) })

    /** Whether [file] can have any problem this filter accepts, and so whether it is worth inspecting at all. */
    fun mayAcceptIn(file: PsiFile): Boolean = !filter.onChangedLines || changedLines.hasAnyIn(file)
}

/**
 * The message of [problem] as the results of "Inspect Code" show it: with the code it is about in place of `#ref`,
 * and neither markup nor the part meant for the tooltip only.
 */
internal fun messageOf(problem: CommonProblemDescriptor): String = ReadAction.compute<String, RuntimeException> {
    ProblemDescriptorUtil.renderDescriptionMessage(problem, (problem as? ProblemDescriptor)?.psiElement,
                                                   ProblemDescriptorUtil.TRIM_AT_TREE_END)
}

// Kept per project, like the rest of the filter.

/** The filter of problems last chosen in [project]. */
internal fun loadProblemFilter(project: Project): ProblemFilter {
    val properties = PropertiesComponent.getInstance(project)
    return ProblemFilter(properties.getBoolean(WITH_QUICK_FIX_KEY), properties.getValue(MESSAGE_CONTAINS_KEY).orEmpty(),
                         properties.getBoolean(ON_CHANGED_LINES_KEY))
}

internal fun storeProblemFilter(project: Project, filter: ProblemFilter) {
    val properties = PropertiesComponent.getInstance(project)
    properties.setValue(WITH_QUICK_FIX_KEY, filter.withQuickFix)
    properties.setValue(MESSAGE_CONTAINS_KEY, filter.messageContains, "")
    properties.setValue(ON_CHANGED_LINES_KEY, filter.onChangedLines)
}
