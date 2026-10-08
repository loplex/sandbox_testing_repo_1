package cz.loplex.inspectionfilter

import com.intellij.analysis.AnalysisScope
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.find.FindModel
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.lang.regexp.inspection.custom.CustomRegExpInspection
import org.intellij.lang.regexp.inspection.custom.RegExpInspectionConfiguration

/**
 * Runs the action for real, with every inspection the IDE the tests run in registers, over a text with two grammar
 * errors and a word a custom inspection looks for - for the inspections that report under the names of others:
 * - Grazie's runner, which reports what it finds as "Grammar error" and "Style suggestion";
 * - "Custom RegExp", which reports for each custom inspection made with a regular expression.
 *
 * Each of them has a severity of its own, and so may be left out of a run that keeps an inspection it reports for.
 */
class ReportingForOthersRunTest : BasePlatformTestCase() {

    private lateinit var custom: RegExpInspectionConfiguration

    override fun setUp() {
        super.setUp()
        // Without it a unit-test run leaves a profile's tools uninitialized, and the profile empty.
        InspectionProfileImpl.INIT_INSPECTIONS = true
        myFixture.addFileToProject("a.txt", "This is a error in the text. It are bad. An apple a day.\n")
        val profile = InspectionProfileImpl("Source").apply { initInspectionTools(project) }
        custom = RegExpInspectionConfiguration("Apple").apply {
            problemDescriptor = "An apple"
            addPattern(RegExpInspectionConfiguration.InspectionPattern("apple", PlainTextFileType.INSTANCE, 0, FindModel.SearchContext.ANY, null))
        }
        CustomRegExpInspection.getCustomRegExpInspection(profile).addConfiguration(custom)
        CustomRegExpInspection.addInspectionToProfile(project, profile, custom)
        // An error, while "Custom RegExp" itself is a warning.
        profile.setErrorLevel(HighlightDisplayKey.find(custom.uuid)!!, HighlightDisplayLevel.ERROR, project)
        useAsProjectProfile(project, profile, testRootDisposable)
    }

    override fun tearDown() {
        try {
            InspectionProfileImpl.INIT_INSPECTIONS = false
            forgetStoredFilter(project)
        }
        finally {
            super.tearDown()
        }
    }

    fun testGrammarErrorsAreReportedWhenChosen() {
        storeSelectedSeverityNames(project, setOf(GRAMMAR_ERROR))

        assertEquals(2, findingsOf(runAction(), "GrazieInspection").size)
    }

    fun testARunOfWarningsLeavesGrammarErrorsOutWithoutAnError() {
        // Grazie's runner is a warning. Were it run, what it reports as a grammar error would fail the run in 2025.3.
        storeSelectedSeverityNames(project, setOf(HighlightSeverity.WARNING.name))

        assertEquals(emptyList<String>(), findingsOf(runAction(), "GrazieInspection"))
    }

    fun testACustomInspectionReportsWhenTheOneReportingForItIsLeftOut() {
        storeSelectedSeverityNames(project, setOf(HighlightSeverity.ERROR.name))

        assertEquals(listOf("An apple"), findingsOf(runAction(), custom.uuid))
    }

    fun testTheOneReportingForACustomInspectionIsFilteredWhenItIsLeftOut() {
        storeSelectedSeverityNames(project, setOf(HighlightSeverity.ERROR.name))
        storeProblemFilter(project, ProblemFilter(messageContains = "banana"))

        // The run adds it, as the custom inspection's main tool. What it reports is stored filtered either way, but the
        // results show what a local inspection reports as it reports it: it has to be the filtering tool that runs.
        val reporting = runAction().tools.getValue(CustomRegExpInspection.SHORT_NAME).tools.map { it.tool.tool }
        assertTrue(reporting.toString(), reporting.none { it is CustomRegExpInspection })
    }

    fun testTheProblemsOfACustomInspectionAreKeptWhenTheFilterAcceptsThem() {
        storeSelectedSeverityNames(project, setOf(HighlightSeverity.ERROR.name))
        storeProblemFilter(project, ProblemFilter(messageContains = "apple"))

        assertEquals(listOf("An apple"), findingsOf(runAction(), custom.uuid))
    }

    /** Runs the action over the whole project, and returns the context it ran in. */
    private fun runAction(): GlobalInspectionContextImpl {
        val contexts = (InspectionManager.getInstance(project) as InspectionManagerEx).runningContexts
        val contextsBefore = contexts.toSet()
        runAction(InspectCodeWithFiltersAction(), project, AnalysisScope(project))
        return (contexts - contextsBefore).single()
    }

    /** The messages of what [context]'s last run reported under [shortName]. */
    private fun findingsOf(context: GlobalInspectionContextImpl, shortName: String): List<String> =
        problemsOf(context).filter { (wrapper, _) -> wrapper.shortName == shortName }.map { (_, problem) -> problem.descriptionTemplate }

    private companion object {
        /** The severity of Grazie's "Grammar error". */
        const val GRAMMAR_ERROR = "GRAMMAR_ERROR"
    }
}
