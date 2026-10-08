package cz.loplex.inspectionfilter

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInsight.actions.VcsFacade
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.HTMLComposer
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptorBase
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.Tools
import com.intellij.codeInspection.lang.GlobalInspectionContextExtension
import com.intellij.codeInspection.lang.HTMLComposerExtension
import com.intellij.codeInspection.lang.InspectionExtensionsFactory
import com.intellij.codeInspection.lang.RefManagerExtension
import com.intellij.codeInspection.reference.RefElement
import com.intellij.codeInspection.reference.RefManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where the context of a run keeps the filter of problems it applies, for [ChangedLinesFinder] to find the lines of. */
internal val RUN_PROBLEM_FILTER: Key<RunProblemFilter> = Key.create("cz.loplex.inspectionfilter.runProblemFilter")

/**
 * In a unit test, the files a project takes as changed since the last commit, in place of those its VCS knows of.
 * What each of them was at the last commit is `VcsFacade.TEST_REVISION_CONTENT` of its PSI file.
 */
internal val TEST_CHANGED_FILES: Key<Collection<VirtualFile>> = Key.create("cz.loplex.inspectionfilter.testChangedFiles")

/**
 * The lines changed since the last commit, as ranges of the text of each file they are in; a file with no changed
 * line has none. Lines only deleted are not among them: they are no longer there for a problem to be on.
 */
internal class ChangedLines(private val ranges: Map<VirtualFile, List<TextRange>>) {

    /** Whether [file] has any changed line - the file it is injected in, if it is. */
    fun hasAnyIn(file: PsiFile): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        val topLevelFile = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file)?.virtualFile
        topLevelFile != null && topLevelFile in ranges
    }

    /**
     * Whether [problem] is on a changed line: whether the code it marks meets one. A problem with no code to mark,
     * which only some global inspections report, is on none.
     */
    fun contain(problem: CommonProblemDescriptor): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        val (file, range) = locate(problem) ?: return@compute false
        ranges[file].orEmpty().any { it.intersects(range) }
    }

    /**
     * Whether the name of [element] is on a changed line: the code the editor marks for an element shown with no
     * problem in it. An element with no name of its own is taken as a whole.
     */
    fun containNameOf(element: RefElement): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        val psiElement = element.psiElement ?: return@compute false
        val name = (psiElement as? PsiNameIdentifierOwner)?.nameIdentifier ?: psiElement
        val nameRange = name.textRange ?: return@compute false
        val injectedLanguageManager = InjectedLanguageManager.getInstance(name.project)
        val file = injectedLanguageManager.getTopLevelFile(name)?.virtualFile ?: return@compute false
        val range = injectedLanguageManager.injectedToHost(name, nameRange)
        ranges[file].orEmpty().any { it.intersects(range) }
    }

    companion object {
        val NONE = ChangedLines(emptyMap())
    }
}

/** The file the code [problem] marks is in, and the range of its text it marks - in the file it is injected in, if it is. */
private fun locate(problem: CommonProblemDescriptor): Pair<VirtualFile, TextRange>? {
    val element = (problem as? ProblemDescriptor)?.psiElement ?: return null
    val injectedLanguageManager = InjectedLanguageManager.getInstance(element.project)
    val range = if (problem is ProblemDescriptorBase) {
        // Also covers a problem that spans from one element to another.
        problem.textRangeForNavigation
    }
    else {
        val elementRange = element.textRange ?: return null
        injectedLanguageManager.injectedToHost(element, problem.textRangeInElement?.shiftRight(elementRange.startOffset) ?: elementRange)
    }
    val file = injectedLanguageManager.getTopLevelFile(element)?.virtualFile
    return if (range != null && file != null) file to range else null
}

/**
 * Finds the lines changed since the last commit for a run that reports only the problems on them, as the run starts
 * and before it initializes its tools: before each run, and so before every rerun from the results too. The lines are
 * then those of the code the run inspects, whatever has been changed or committed since the run before.
 *
 * Every inspection context gets one, and it does nothing in those whose filter of problems - which the context has as
 * [RUN_PROBLEM_FILTER] - is not on changed lines. It is the one way to be told of the start of every run: a rerun from
 * the results starts the run of the same context again, and nothing else.
 */
internal class ChangedLinesFinder : GlobalInspectionContextExtension<ChangedLinesFinder> {

    override fun getID(): Key<ChangedLinesFinder> = ID

    override fun performPreInitToolsActivities(usedTools: List<Tools>, context: GlobalInspectionContext) {
        val filter = context.getUserData(RUN_PROBLEM_FILTER)?.takeIf { it.filter.onChangedLines } ?: return
        // None, rather than those of the run before, if the run is cancelled while they are looked for.
        filter.changedLines = ChangedLines.NONE
        ProgressManager.getInstance().progressIndicator?.text = InspectionFilterBundle.message("progress.changedLines")
        filter.changedLines = findChangedLines(context.project, context.refManager.scope)
    }

    override fun performPreRunActivities(globalTools: List<Tools>, localTools: List<Tools>, context: GlobalInspectionContext) {}

    override fun performPostRunActivities(inspections: List<InspectionToolWrapper<*, *>>, context: GlobalInspectionContext) {}

    override fun cleanup() {}

    private companion object {
        val ID: Key<ChangedLinesFinder> = Key.create("cz.loplex.inspectionfilter.changedLinesFinder")
    }
}

/** Gives every inspection context a [ChangedLinesFinder]; the rest of what it could extend it leaves as it is. */
internal class ChangedLinesFinderFactory : InspectionExtensionsFactory() {
    override fun createGlobalInspectionContextExtension(): GlobalInspectionContextExtension<*> = ChangedLinesFinder()
    override fun createRefManagerExtension(refManager: RefManager): RefManagerExtension<*>? = null
    override fun createHTMLComposerExtension(composer: HTMLComposer): HTMLComposerExtension<*>? = null
    override fun isToCheckMember(element: PsiElement, id: String): Boolean = true
    override fun getSuppressedInspectionIdsIn(element: PsiElement): String? = null
}

/**
 * The lines changed since the last commit in the files of [scope] - or of the whole project, with no scope - found as
 * "Reformat Code" finds them when it is to reformat only those. A file new to the VCS is changed as a whole, while one
 * the VCS does not know of has no changed line, just as one with no change.
 */
private fun findChangedLines(project: Project, scope: AnalysisScope?): ChangedLines {
    val ranges = HashMap<VirtualFile, List<TextRange>>()
    for (file in changedFiles(project)) {
        ProgressManager.checkCanceled()
        // A file at a time, so as not to hold changes up for long: what a file was is read from the VCS.
        val changed = ReadAction.compute<List<TextRange>?, RuntimeException> {
            val psiFile = if (file.isValid && (scope == null || scope.contains(file))) PsiManager.getInstance(project).findFile(file) else null
            psiFile?.let { VcsFacade.getInstance().getChangedRangesInfo(it) }?.allChangedRanges
        }
        if (!changed.isNullOrEmpty()) {
            ranges[file] = changed
        }
    }
    return ChangedLines(ranges)
}

/**
 * The files changed since the last commit, once the VCS has caught up with the changes to them: a file saved just
 * before the run started may not be among them yet.
 */
private fun changedFiles(project: Project): Collection<VirtualFile> {
    if (ApplicationManager.getApplication().isUnitTestMode) {
        project.getUserData(TEST_CHANGED_FILES)?.let { return it }
    }
    if (!ProjectLevelVcsManager.getInstance(project).hasActiveVcss()) {
        return emptyList()
    }
    val changeListManager = ChangeListManager.getInstance(project)
    val updated = CountDownLatch(1)
    changeListManager.invokeAfterUpdate(false, updated::countDown)
    while (!updated.await(50, TimeUnit.MILLISECONDS)) {
        ProgressManager.checkCanceled()
    }
    return changeListManager.affectedFiles
}
