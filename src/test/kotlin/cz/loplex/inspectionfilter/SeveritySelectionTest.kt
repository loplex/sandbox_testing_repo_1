package cz.loplex.inspectionfilter

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

class SeveritySelectionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            forgetStoredFilter(project)
        }
        finally {
            super.tearDown()
        }
    }

    fun testErrorsAndWarningsAreChosenUntilSomethingElseIs() {
        assertEquals(setOf(HighlightSeverity.ERROR.name, HighlightSeverity.WARNING.name),
                     loadSelectedSeverityNames(project))

        storeSelectedSeverityNames(project, listOf(HighlightSeverity.WEAK_WARNING.name))

        assertEquals(setOf(HighlightSeverity.WEAK_WARNING.name), loadSelectedSeverityNames(project))
    }

    fun testAChoiceOfSeveritiesThatNoLongerExistFallsBackToTheDefault() {
        storeSelectedSeverityNames(project, listOf("A severity since removed"))

        assertEquals(setOf(HighlightSeverity.ERROR.name, HighlightSeverity.WARNING.name),
                     loadSelectedSeverityNames(project))
    }

    fun testTheSelectedSeveritiesAreReportedUntilAnotherModeIsChosen() {
        storeSelectedSeverityNames(project, listOf(HighlightSeverity.WEAK_WARNING.name))

        assertEquals(SeverityMode.SELECTED, loadSeverityMode(project))
        assertEquals(setOf(HighlightSeverity.WEAK_WARNING.name), loadReportedSeverityNames(project))
    }

    fun testThisAndHigherReportsTheChosenSeverityAndThoseAboveIt() {
        storeSeverityMode(project, SeverityMode.THIS_AND_HIGHER)
        storeLowestSeverityName(project, HighlightSeverity.WARNING.name)

        val reported = loadReportedSeverityNames(project)

        assertTrue(reported.containsAll(setOf(HighlightSeverity.ERROR.name, HighlightSeverity.WARNING.name)))
        assertFalse(HighlightSeverity.WEAK_WARNING.name in reported)
    }

    fun testSwitchingBetweenModesKeepsWhatWasSelected() {
        storeSelectedSeverityNames(project, listOf(HighlightSeverity.WEAK_WARNING.name))
        storeSeverityMode(project, SeverityMode.THIS_AND_HIGHER)
        storeLowestSeverityName(project, HighlightSeverity.ERROR.name)
        assertFalse(HighlightSeverity.WEAK_WARNING.name in loadReportedSeverityNames(project))

        storeSeverityMode(project, SeverityMode.SELECTED)

        assertEquals(setOf(HighlightSeverity.WEAK_WARNING.name), loadReportedSeverityNames(project))
    }

    fun testTheChooserIsBuiltWithWhatIsStored() {
        storeSeverityMode(project, SeverityMode.THIS_AND_HIGHER)
        storeLowestSeverityName(project, HighlightSeverity.WARNING.name)

        val chooser = SeverityChooser(project)

        assertEquals(loadReportedSeverityNames(project), chooser.reportedSeverityNames)
    }

    fun testTheSeveritiesOfTheChooserAreAsWideWhicheverTheMode() {
        storeSeverityMode(project, SeverityMode.THIS_AND_HIGHER)
        // Built as the popup shows it, with its check boxes disabled.
        val content = SeverityChooser::class.java.getDeclaredMethod("buildContent").apply { isAccessible = true }
            .invoke(SeverityChooser(project)) as JComponent
        val labels = UIUtil.findComponentsOfType(content, JBLabel::class.java)
        val disabledWidths = labels.map { it.preferredSize.width }

        UIUtil.findComponentsOfType(content, JBRadioButton::class.java).single { it.text == "Selected" }.doClick()

        // The popup stays as wide as it opened.
        assertTrue(labels.all { it.isEnabled })
        assertEquals(disabledWidths, labels.map { it.preferredSize.width })
    }

    fun testTheSummaryNamesTheSeveritiesSelected() {
        storeSelectedSeverityNames(project, listOf(HighlightSeverity.WARNING.name, HighlightSeverity.ERROR.name))

        // In the order of the severities, whatever the order they were chosen in; no icon for more than one.
        assertEquals(Summary("Error, Warning") to null, severitySummary(project))

        storeSelectedSeverityNames(project, listOf(HighlightSeverity.WARNING.name))

        assertEquals(Summary("Warning") to HighlightSeverity.WARNING, severitySummary(project))
    }

    fun testTheSummaryNamesOnlyTheFirstFewOfManySeverities() {
        val severities = availableSeverities(project)
        // All but the lowest, as all of them are summed up otherwise.
        val chosen = severities.dropLast(1)
        assertTrue(chosen.size > 3)
        storeSelectedSeverityNames(project, chosen.map { it.name })

        val (summary, _) = severitySummary(project)

        assertEquals("${chosen.take(3).joinToString(", ") { it.displayCapitalizedName }} and ${chosen.size - 3} more", summary.text)
        assertNotNull(summary.tooltip)
    }

    fun testTheSummaryOfThisAndHigherNamesTheLowest() {
        storeSeverityMode(project, SeverityMode.THIS_AND_HIGHER)
        storeLowestSeverityName(project, HighlightSeverity.WARNING.name)

        assertEquals(Summary("Warning and higher") to HighlightSeverity.WARNING, severitySummary(project))
    }

    fun testTheSummarySaysAllWhenEverySeverityIsReported() {
        val severities = availableSeverities(project)
        storeSelectedSeverityNames(project, severities.map { it.name })

        assertEquals(Summary("All severities") to null, severitySummary(project))

        storeSeverityMode(project, SeverityMode.THIS_AND_HIGHER)
        storeLowestSeverityName(project, severities.last().name)

        assertEquals(Summary("All severities") to null, severitySummary(project))
    }

    fun testALowestSeverityThatNoLongerExistsFallsBackToWarning() {
        storeLowestSeverityName(project, "A severity since removed")

        assertEquals(HighlightSeverity.WARNING.name, loadLowestSeverityName(project))
    }
}
