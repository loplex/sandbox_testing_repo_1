package cz.loplex.inspectionfilter

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ProblemFilterTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            forgetStoredFilter(project)
        }
        finally {
            super.tearDown()
        }
    }

    fun testWithNoConditionNothingIsLeftOut() {
        assertFalse(ProblemFilter().isActive)
        assertFalse(ProblemFilter(messageContains = "  ").isActive)
        assertTrue(ProblemFilter().acceptsWithNoChangedLine(problem("Anything")))
    }

    fun testOnlyAProblemWithAQuickFixIsKeptWhenOneIsRequired() {
        val filter = ProblemFilter(withQuickFix = true)

        assertTrue(filter.acceptsWithNoChangedLine(problem("Fixable", NoFix())))
        assertFalse(filter.acceptsWithNoChangedLine(problem("Not fixable")))
    }

    fun testTheMessageIsSearchedIgnoringCase() {
        val filter = ProblemFilter(messageContains = "APPLE")

        assertTrue(filter.acceptsWithNoChangedLine(problem("An apple a day")))
        assertFalse(filter.acceptsWithNoChangedLine(problem("A pear a day")))
    }

    fun testTheMessageIsSearchedAsTheResultsShowIt() {
        // The code the problem is about in place of #ref, and nothing after #treeend, which only the tooltip shows.
        val problem = problem("Found #ref here#treeend, and more")

        assertEquals("Found Apple pie here", messageOf(problem))
        assertTrue(ProblemFilter(messageContains = "found apple pie").acceptsWithNoChangedLine(problem))
        assertFalse(ProblemFilter(messageContains = "more").acceptsWithNoChangedLine(problem))
    }

    fun testOnlyAProblemOnAChangedLineIsKeptWhenOneIsRequired() {
        val file = myFixture.configureByText("a.txt", "Apple pie\nBanana split\nCherry tart\n")
        val secondLine = TextRange(10, 22)
        val changedLines = ChangedLines(mapOf(file.virtualFile to listOf(secondLine)))
        val filter = ProblemFilter(onChangedLines = true)

        assertTrue(filter.isActive)
        assertTrue(filter.accepts(problemAt(file, "Banana"), changedLines))
        assertTrue(filter.accepts(problemAt(file, "split"), changedLines))
        // The words just before the line break that starts it, and just after the one that ends it.
        assertFalse(filter.accepts(problemAt(file, "pie"), changedLines))
        assertFalse(filter.accepts(problemAt(file, "Cherry"), changedLines))
        assertFalse(filter.accepts(problemAt(file, "Banana"), ChangedLines.NONE))
        assertTrue(changedLines.hasAnyIn(file))
        assertFalse(ChangedLines.NONE.hasAnyIn(file))
    }

    fun testTheFilterStoredIsTheOneLoaded() {
        val filter = ProblemFilter(withQuickFix = true, messageContains = "apple", onChangedLines = true)

        storeProblemFilter(project, filter)

        assertEquals(filter, loadProblemFilter(project))
    }

    fun testWithNothingStoredNothingIsLeftOut() {
        assertEquals(ProblemFilter(), loadProblemFilter(project))
    }

    /** Whether the filter accepts [problem] when no line has changed. */
    private fun ProblemFilter.acceptsWithNoChangedLine(problem: ProblemDescriptor): Boolean = accepts(problem, ChangedLines.NONE)

    private fun problemAt(file: PsiFile, word: String): ProblemDescriptor =
        InspectionManager.getInstance(project).createProblemDescriptor(file, TextRange.from(file.text.indexOf(word), word.length), word,
                                                                       ProblemHighlightType.GENERIC_ERROR_OR_WARNING, false)

    private fun problem(message: String, vararg fixes: LocalQuickFix): ProblemDescriptor {
        val file = myFixture.configureByText("a.txt", "Apple pie")
        return InspectionManager.getInstance(project).createProblemDescriptor(file, message, false, fixes, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
    }

    private class NoFix : LocalQuickFix {
        override fun getFamilyName(): String = "Do nothing"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {}
    }
}
