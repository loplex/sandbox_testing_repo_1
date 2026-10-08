package cz.loplex.inspectionfilter

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.radioButton
import com.intellij.driver.sdk.ui.components.elements.tree
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val PLUGIN_ID = "cz.loplex.ij-inspection-filter"

/** The order the rows of the dialog give their "Choose…" in. */
private enum class Row { SEVERITIES, GROUPS, PROPERTIES, PROBLEMS }

/**
 * The popups of the dialog, in one IDE for the whole class, since one takes a minute to start: the filter a test
 * stores is forgotten before the next one, which so starts from nothing chosen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilterPopupUiTest {

    private lateinit var run: BackgroundRun

    @BeforeAll
    fun startIde() {
        run = ideWithPlugin("filterPopups").runIdeWithDriver()
        run.driver.withContext { waitForIndicators(5.minutes) }
    }

    @AfterAll
    fun closeIde() {
        run.closeIdeAndWait()
    }

    @BeforeEach
    fun forgetStoredFilter() {
        run.driver.forgetStoredFilter()
    }

    @Test
    fun escInThePopupOfSeveritiesGoesBackToTheSeveritiesAndTheModeItOpenedWith() = inDialog {
        open(Row.SEVERITIES)
        severityLabel("Weak Warning").click()
        summary("Error, Warning, Weak Warning").shouldBe { present() }
        radioButton { byVisibleText("This and higher:") }.click()
        summary("Warning and higher").shouldBe { present() }
        keyboard { escape() }
        severityLabel("Weak Warning").shouldBe { notPresent() }
        summary("Error, Warning").shouldBe { present() }
    }

    @Test
    fun escInTheOpenListOfTheLowestSeverityClosesTheListOnly() = inDialog {
        open(Row.SEVERITIES)
        radioButton { byVisibleText("This and higher:") }.click()
        summary("Warning and higher").shouldBe { present() }
        popup().comboBox().click()
        val list = x { byType("javax.swing.plaf.basic.BasicComboPopup") }
        list.shouldBe { present() }
        keyboard { escape() }
        list.shouldBe { notPresent() }
        x { byVisibleText("Edit Severities…") }.shouldBe { present() }
        summary("Warning and higher").shouldBe { present() }
        keyboard { escape() }
        x { byVisibleText("Edit Severities…") }.shouldBe { notPresent() }
        summary("Error, Warning").shouldBe { present() }
    }

    @Test
    fun escInThePopupOfGroupsGoesBackToTheGroupsItOpenedWith() = inDialog {
        open(Row.GROUPS)
        x { byVisibleText("Uncheck All") }.click()
        summary("No group: nothing will be reported").shouldBe { present() }
        keyboard { escape() }
        x { byVisibleText("Uncheck All") }.shouldBe { notPresent() }
        summary("All groups").shouldBe { present() }
    }

    @Test
    fun escInThePopupOfPropertiesGoesBackToThePropertiesItOpenedWith() = inDialog {
        open(Row.PROPERTIES)
        checkBox { byVisibleText("Cleanup inspections only") }.click()
        summary("Cleanup").shouldBe { present() }
        keyboard { escape() }
        checkBox { byVisibleText("Cleanup inspections only") }.shouldBe { notPresent() }
        summary("All inspections").shouldBe { present() }
    }

    @Test
    fun escInThePopupOfProblemsGoesBackToTheProblemsItOpenedWith() = inDialog {
        open(Row.PROBLEMS)
        checkBox { byVisibleText("With a quick fix only") }.click()
        summary("With a quick fix").shouldBe { present() }
        keyboard { escape() }
        checkBox { byVisibleText("With a quick fix only") }.shouldBe { notPresent() }
        summary("All problems").shouldBe { present() }
    }

    @Test
    fun revertInThePopupOfGroupsGoesBackToTheGroupsItOpenedWithAndLeavesThePopupOpen() = inDialog {
        open(Row.GROUPS)
        val revert = x { byVisibleText("Revert") }
        // Hidden while there is nothing to go back to.
        revert.shouldBe { notPresent() }
        x { byVisibleText("Uncheck All") }.click()
        summary("No group: nothing will be reported").shouldBe { present() }
        revert.click()
        summary("All groups").shouldBe { present() }
        x { byVisibleText("Uncheck All") }.shouldBe { present() }
        revert.shouldBe { notPresent() }
        keyboard { escape() }
    }

    @Test
    fun aClickOutsideThePopupClosesItAndKeepsTheChange() = inDialog {
        open(Row.PROPERTIES)
        checkBox { byVisibleText("Cleanup inspections only") }.click()
        summary("Cleanup").shouldBe { present() }
        x { byVisibleText("Inspections:") }.click()
        checkBox { byVisibleText("Cleanup inspections only") }.shouldBe { notPresent() }
        summary("Cleanup").shouldBe { present() }
    }

    @Test
    fun enterOnACheckBoxClosesThePopupAndKeepsTheChange() = inDialog {
        open(Row.PROPERTIES)
        checkBox { byVisibleText("Cleanup inspections only") }.click()
        summary("Cleanup").shouldBe { present() }
        keyboard { enter() }
        checkBox { byVisibleText("Cleanup inspections only") }.shouldBe { notPresent() }
        // Not the dialog's Analyze, which closes it.
        summary("Cleanup").shouldBe { present() }
    }

    @Test
    fun enterInTheFieldOfTheMessageClosesThePopupAndKeepsTheText() = inDialog {
        // The popup opens with the focus in the field.
        open(Row.PROBLEMS)
        keyboard { typeText("abc") }
        summary("Message contains “abc”").shouldBe { present() }
        keyboard { enter() }
        checkBox { byVisibleText("With a quick fix only") }.shouldBe { notPresent() }
        summary("Message contains “abc”").shouldBe { present() }
    }

    @Test
    fun enterInTheSearchOfGroupsClosesThePopup() = inDialog {
        // The popup opens with the focus in the search.
        open(Row.GROUPS)
        keyboard { typeText("Java") }
        keyboard { enter() }
        x { byVisibleText("Uncheck All") }.shouldBe { notPresent() }
        summary("All groups").shouldBe { present() }
    }

    @Test
    fun enterOnALinkPressesIt() = inDialog {
        open(Row.GROUPS)
        x { byVisibleText("Uncheck All") }.click()
        summary("No group: nothing will be reported").shouldBe { present() }
        val revert = x { byVisibleText("Revert") }
        revert.setFocus()
        revert.shouldBe { isFocusOwner() }
        keyboard { enter() }
        summary("All groups").shouldBe { present() }
        x { byVisibleText("Uncheck All") }.shouldBe { present() }
        keyboard { escape() }
    }

    @Test
    fun aRunReportsOnlyTheProblemsTheFilterLetsThrough() {
        val (reported, left) = TYPOS
        inDialog {
            radioButton { byVisibleText("Whole project") }.click()
            // Only Error and Warning are reported when nothing has been chosen.
            open(Row.SEVERITIES)
            severityLabel("Typo").click()
            keyboard { enter() }
            summary("Error, Warning, Typo").shouldBe { present() }
            x { byVisibleText("This choice runs into a bug of IntelliJ IDEA 2025.3") }.shouldBe { notPresent() }
            open(Row.PROBLEMS)
            keyboard { typeText(reported) }
            keyboard { enter() }
            summary("Message contains “$reported”").shouldBe { present() }
            pressButton("Analyze")
        }
        run.driver.withContext {
            waitForIndicators(5.minutes)
            val results = ui.tree(xQuery { byType("com.intellij.codeInspection.ui.InspectionTree") })
            waitFor("Results with $reported", timeout = 30.seconds) {
                results.expandAll().collectExpandedPathsAsStrings().any { reported in it }
            }
            val paths = results.collectExpandedPathsAsStrings()
            assertTrue(paths.none { left in it }) { "Results with $left too:\n${paths.joinToString("\n")}" }
        }
    }

    /**
     * Opens the dialog of the action, does [action] in it, and then closes it: with Esc, which closes a popup [action]
     * has left open first, and so does not press its Analyze by mistake.
     */
    private fun inDialog(action: DialogUiComponent.() -> Unit): Unit = run.driver.withContext {
        invokeAction("cz.loplex.inspectionfilter.InspectCodeWithFilters", now = false)
        ui.dialog(title = "Specify Inspection Scope") {
            try {
                shouldBe { present() }
                action()
            }
            finally {
                // A popup, the open list of a combo box in it, and the dialog itself.
                repeat(3) {
                    if (present()) {
                        keyboard { escape() }
                        Thread.sleep(500)
                    }
                }
            }
        }
    }
}

private fun DialogUiComponent.open(row: Row) {
    xx { byVisibleText("Choose…") }.list()[row.ordinal].click()
}

/** The summary of a row, or anything else in the dialog with that [text]. */
private fun DialogUiComponent.summary(text: String): UiComponent = x { byClass("JBLabel") and byVisibleText(text) }

/** The label of a severity in its popup, which ticks its check box when clicked, as the check box has no text. */
private fun DialogUiComponent.severityLabel(name: String): UiComponent = summary(name)

/** Forgets the filter stored in the project, as the tests of the plugin itself do. */
private fun Driver.forgetStoredFilter() {
    val properties = service<RemotePropertiesComponent>(singleProject())
    val keys = utility<RemoteSeveritySelectionFile>().getSEVERITY_SELECTION_KEYS() +
        utility<RemoteInspectionGroupsFile>().getGROUP_SELECTION_KEYS() +
        utility<RemoteInspectionPropertiesFile>().getPROPERTY_SELECTION_KEYS() +
        utility<RemoteProblemFilterFile>().getPROBLEM_FILTER_KEYS()
    for (key in keys) {
        properties.unsetValue(key)
        // Lists are kept apart from values, and unsetValue leaves them be.
        properties.setList(key, null)
    }
}

@Remote("com.intellij.ide.util.PropertiesComponent")
private interface RemotePropertiesComponent {
    fun unsetValue(name: String)
    fun setList(name: String, values: Collection<String>?)
}

// Where the plugin keeps the keys of what it stores.

@Remote("cz.loplex.inspectionfilter.SeveritySelectionKt", plugin = PLUGIN_ID)
private interface RemoteSeveritySelectionFile {
    fun getSEVERITY_SELECTION_KEYS(): List<String>
}

@Remote("cz.loplex.inspectionfilter.InspectionGroupsKt", plugin = PLUGIN_ID)
private interface RemoteInspectionGroupsFile {
    fun getGROUP_SELECTION_KEYS(): List<String>
}

@Remote("cz.loplex.inspectionfilter.InspectionPropertiesKt", plugin = PLUGIN_ID)
private interface RemoteInspectionPropertiesFile {
    fun getPROPERTY_SELECTION_KEYS(): List<String>
}

@Remote("cz.loplex.inspectionfilter.ProblemFilterKt", plugin = PLUGIN_ID)
private interface RemoteProblemFilterFile {
    fun getPROBLEM_FILTER_KEYS(): List<String>
}
