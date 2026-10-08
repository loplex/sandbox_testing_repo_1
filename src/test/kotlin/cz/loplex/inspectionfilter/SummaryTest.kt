package cz.loplex.inspectionfilter

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SummaryTest : BasePlatformTestCase() {

    fun testAFewNamesAreAllGivenWithNoTooltip() {
        assertEquals(Summary("A, B, C"), summaryOf(listOf("A", "B", "C")))
    }

    fun testManyNamesAreCountedAndAllGivenInTheTooltip() {
        val summary = summaryOf(listOf("A", "B", "C", "D", "E"))

        assertEquals("A, B, C and 2 more", summary.text)
        assertEquals(linesOf(listOf("A", "B", "C", "D", "E")), summary.tooltip)
    }

    fun testNoPropertyRequiredMeansAllInspections() {
        assertEquals(Summary("All inspections"), propertySummary(emptySet()))
    }

    fun testThePropertiesRequiredAreNamedInTheirOwnOrder() {
        val summary = propertySummary(setOf(InspectionProperty.BATCH_ONLY, InspectionProperty.CLEANUP))

        assertEquals("Cleanup, batch-mode only", summary.text)
        assertNotNull(summary.tooltip)
    }

    fun testNoFilterOfProblemsMeansAllProblems() {
        assertEquals("All problems", problemSummary(ProblemFilter(messageContains = " ")))
    }

    fun testTheConditionsOnAProblemAreNamedTogether() {
        assertEquals("With a quick fix", problemSummary(ProblemFilter(withQuickFix = true)))
        assertEquals("Message contains \u201Capple\u201D", problemSummary(ProblemFilter(messageContains = "apple")))
        assertEquals("With a quick fix, message contains \u201Capple\u201D", problemSummary(ProblemFilter(true, "apple")))
        assertEquals("On lines changed since the last commit", problemSummary(ProblemFilter(onChangedLines = true)))
        assertEquals("On lines changed since the last commit, with a quick fix, message contains \u201Capple\u201D",
                     problemSummary(ProblemFilter(true, "apple", true)))
    }
}
