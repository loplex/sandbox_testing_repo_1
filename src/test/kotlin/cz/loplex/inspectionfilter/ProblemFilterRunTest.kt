package cz.loplex.inspectionfilter

import com.intellij.analysis.AnalysisScope
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInsight.actions.VcsFacade
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the action for real over two files, with a filter of problems, and compares what it reports with what the
 * filter keeps - for each of the three ways an inspection can report: a local one through a visitor, an external one
 * such as ShellCheck with `checkFile`, and a global one to the run.
 *
 * Both files are the same three lines, "apple", "banana" and "cherry", and each problem is on the line of the word its
 * message is. The local and the external inspection report all three problems in each file: "apple", "banana" - the
 * one with a quick fix - and "cherry". The global one reports "apple" and "cherry" in a.txt, and "banana", with a
 * quick fix, in b.txt.
 */
class ProblemFilterRunTest : BasePlatformTestCase() {

    private lateinit var a: PsiFile
    private lateinit var b: PsiFile

    override fun setUp() {
        super.setUp()
        // Without it a unit-test run leaves a profile's tools uninitialized, and the profile empty.
        InspectionProfileImpl.INIT_INSPECTIONS = true
        inspectedFiles.clear()
        a = myFixture.addFileToProject("a.txt", TEXT)
        b = myFixture.addFileToProject("b.txt", TEXT)
        useAsProjectProfile(project, newProfile("Source", supplierOf(Local(), External(), Global()), InspectionProfileImpl.BASE_PROFILE.get(),
                                                project), testRootDisposable)
        storeSelectedSeverityNames(project, setOf(HighlightSeverity.ERROR.name))
    }

    override fun tearDown() {
        try {
            InspectionProfileImpl.INIT_INSPECTIONS = false
            project.putUserData(TEST_CHANGED_FILES, null)
            forgetStoredFilter(project)
        }
        finally {
            super.tearDown()
        }
    }

    fun testARunWithNoFilterOfProblemsReportsEveryProblem() {
        assertEquals((LOCAL + EXTERNAL + GLOBAL).sorted(), findingsOf(runAction()))
    }

    fun testARunReportsOnlyTheProblemsWithAQuickFix() {
        storeProblemFilter(project, ProblemFilter(withQuickFix = true))

        assertEquals(listOf("Local: banana in a.txt", "Local: banana in b.txt", "External: banana in a.txt", "External: banana in b.txt",
                            "Global: banana in b.txt").sorted(), findingsOf(runAction()))
    }

    fun testARunReportsOnlyTheProblemsWhoseMessageContainsTheTextInAnyCase() {
        storeProblemFilter(project, ProblemFilter(messageContains = "ANA"))

        assertEquals(listOf("Local: banana in a.txt", "Local: banana in b.txt", "External: banana in a.txt", "External: banana in b.txt",
                            "Global: banana in b.txt").sorted(), findingsOf(runAction()))
    }

    fun testARunReportsOnlyTheProblemsBothFiltersKeep() {
        storeProblemFilter(project, ProblemFilter(withQuickFix = true, messageContains = "apple"))

        assertEquals(emptyList<String>(), findingsOf(runAction()))
    }

    fun testTheProblemsOfAGlobalInspectionInOneElementAreFilteredOneByOne() {
        // The run keeps all the problems a global inspection reports in a.txt, "cherry" with "apple"; the results do not.
        storeProblemFilter(project, ProblemFilter(messageContains = "apple"))

        assertEquals(listOf("Local: apple in a.txt", "Local: apple in b.txt", "External: apple in a.txt", "External: apple in b.txt",
                            "Global: apple in a.txt").sorted(), findingsOf(runAction()))
    }

    fun testARerunKeepsTheFilterOfProblems() {
        storeProblemFilter(project, ProblemFilter(withQuickFix = true))
        val context = runAction()
        val findings = findingsOf(context)

        // A filter chosen since does not change what a rerun reports.
        storeProblemFilter(project, ProblemFilter())
        runAndWait(context, AnalysisScope(project))

        assertEquals(findings, findingsOf(context))
    }

    fun testARunReportsOnlyTheProblemsOnLinesChangedSinceTheLastCommit() {
        // In a.txt only the line of "banana" has changed; b.txt is new, and so changed as a whole.
        changeSinceTheLastCommit(a to "apple\nbanana split\ncherry\n", b to "")
        storeProblemFilter(project, ProblemFilter(onChangedLines = true))

        assertEquals((listOf("Local: banana in a.txt", "External: banana in a.txt", "Global: banana in b.txt") +
                      (LOCAL + EXTERNAL).filter { it.endsWith("in b.txt") }).sorted(),
                     findingsOf(runAction()))
    }

    fun testALocalInspectionIsNotRunInAFileWithNoChangedLine() {
        changeSinceTheLastCommit(a to "apple\nbanana split\ncherry\n")
        storeProblemFilter(project, ProblemFilter(onChangedLines = true))

        runAction()

        assertEquals(setOf("Local: a.txt", "External: a.txt"), inspectedFiles)
    }

    fun testARerunFindsTheChangedLinesAnew() {
        changeSinceTheLastCommit(a to "apple\nbanana split\ncherry\n")
        storeProblemFilter(project, ProblemFilter(onChangedLines = true))
        val context = runAction()

        // As if "banana" had been committed since, and "cherry" changed.
        a.putUserData(VcsFacade.TEST_REVISION_CONTENT, "apple\nbanana\ncherry pie\n")
        runAndWait(context, AnalysisScope(project))

        assertEquals(listOf("Local: cherry in a.txt", "External: cherry in a.txt", "Global: cherry in a.txt").sorted(), findingsOf(context))
    }

    /** Makes [changes] the files changed since the last commit, each with what it was then. */
    private fun changeSinceTheLastCommit(vararg changes: Pair<PsiFile, String>) {
        for ((file, lastCommitted) in changes) {
            file.putUserData(VcsFacade.TEST_REVISION_CONTENT, lastCommitted)
        }
        project.putUserData(TEST_CHANGED_FILES, changes.map { it.first.virtualFile })
    }

    /** Runs the action over the whole project, and returns the context it ran in. */
    private fun runAction(): GlobalInspectionContextImpl {
        val contexts = (InspectionManager.getInstance(project) as InspectionManagerEx).runningContexts
        val contextsBefore = contexts.toSet()
        runAction(InspectCodeWithFiltersAction(), project, AnalysisScope(project))
        return (contexts - contextsBefore).single()
    }

    /**
     * The findings the results of [context]'s last run show, as "inspection: message in file", sorted. The results are
     * built first, as the run's own are: what each inspection has kept is shown for each element they show.
     */
    private fun findingsOf(context: GlobalInspectionContextImpl): List<String> =
        context.tools.values.flatMap { tools -> tools.tools.map { it.tool } }.distinct().flatMap { wrapper ->
            val presentation = context.getPresentation(wrapper).apply { updateContent() }
            val shown = presentation.content.values.flatten().flatMap { presentation.problemElements.get(it).orEmpty().asList() }
            shown.map { problem ->
                "${wrapper.shortName}: ${problem.descriptionTemplate} in ${(problem as ProblemDescriptor).psiElement.containingFile.name}"
            }
        }.sorted()

    private abstract class ErrorInspection : LocalInspectionTool() {
        override fun getShortName(): String = javaClass.simpleName
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = "Test"
        override fun getDefaultLevel(): HighlightDisplayLevel = HighlightDisplayLevel.ERROR
        override fun isEnabledByDefault(): Boolean = true
    }

    private class Local : ErrorInspection() {
        override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                inspectedFiles += "Local: ${file.name}"
                holder.registerProblem(file, rangeOf(file, "apple"), "apple")
                holder.registerProblem(file, rangeOf(file, "banana"), "banana", NoFix())
                // Not every inspection registers a problem the same way.
                holder.problem(file, "cherry").range(rangeOf(file, "cherry")).register()
            }
        }
    }

    /** An external tool, such as ShellCheck, which the run finds by its type, and runs with [checkFile] alone. */
    private class External : ErrorInspection(), ExternalAnnotatorBatchInspection {
        override fun checkFile(file: PsiFile, context: GlobalInspectionContext, manager: InspectionManager): Array<ProblemDescriptor> {
            inspectedFiles += "External: ${file.name}"
            return arrayOf(problem(file, manager, "apple"), problem(file, manager, "banana", NoFix()), problem(file, manager, "cherry"))
        }
    }

    private class Global : GlobalSimpleInspectionTool() {
        override fun getShortName(): String = javaClass.simpleName
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = "Test"
        override fun getDefaultLevel(): HighlightDisplayLevel = HighlightDisplayLevel.ERROR
        override fun isEnabledByDefault(): Boolean = true

        override fun checkFile(
            psiFile: PsiFile,
            manager: InspectionManager,
            problemsHolder: ProblemsHolder,
            globalContext: GlobalInspectionContext,
            problemDescriptionsProcessor: ProblemDescriptionsProcessor,
        ) {
            if (psiFile.name == "a.txt") {
                problemsHolder.registerProblem(psiFile, rangeOf(psiFile, "apple"), "apple")
                problemsHolder.registerProblem(psiFile, rangeOf(psiFile, "cherry"), "cherry")
            }
            else {
                problemsHolder.registerProblem(psiFile, rangeOf(psiFile, "banana"), "banana", NoFix())
            }
        }
    }

    private class NoFix : LocalQuickFix {
        override fun getFamilyName(): String = "Do nothing"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {}
    }

    private companion object {
        const val TEXT = "apple\nbanana\ncherry\n"

        /** The files the local and the external inspection have been run in, as "inspection: file". */
        val inspectedFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

        val LOCAL = findings("Local")
        val EXTERNAL = findings("External")
        val GLOBAL = listOf("Global: apple in a.txt", "Global: cherry in a.txt", "Global: banana in b.txt")

        /** What [inspection] reports, when it reports all three problems in both files. */
        fun findings(inspection: String): List<String> =
            listOf("a.txt", "b.txt").flatMap { file -> listOf("apple", "banana", "cherry").map { "$inspection: $it in $file" } }

        fun problem(file: PsiFile, manager: InspectionManager, message: String, vararg fixes: LocalQuickFix): ProblemDescriptor =
            manager.createProblemDescriptor(file, rangeOf(file, message), message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false, *fixes)

        /** Where [word] is in [file]. */
        fun rangeOf(file: PsiFile, word: String): TextRange = TextRange.from(file.text.indexOf(word), word.length)
    }
}
