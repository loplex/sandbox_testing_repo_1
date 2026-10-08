package cz.loplex.inspectionfilter

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent

/** Registered by the platform; its classes are internal, its ID is not. */
private const val EDIT_SEVERITIES_ACTION_ID = "SeverityEditorDialog"

/**
 * The dialog's choice of the severities to report: a [summary] of the choice, and a [button] that opens it - the
 * severities ticked one by one, or one and every severity above it, in which case the check boxes are disabled and
 * show which that is.
 *
 * Every change is stored at once (see SeveritySelection.kt), so that the choice survives the dialog, and so that the
 * whole of it can be built anew from what is stored after "Edit Severities…" has added, removed or reordered severities.
 */
internal class SeverityChooser(private val project: Project) {

    val summary = JBLabel()
    val button = JButton(InspectionFilterBundle.message("dialog.choose")).apply { addActionListener { showPopup() } }

    /** The names of the severities the choice currently reports. */
    var reportedSeverityNames: Set<String> = emptySet()
        private set

    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        changed()
    }

    /** Calls [listener] whenever [reportedSeverityNames] may have changed. */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    private fun changed() {
        reportedSeverityNames = loadReportedSeverityNames(project)
        val (text, iconSeverity) = severitySummary(project)
        summary.text = text.text
        summary.toolTipText = text.tooltip
        summary.icon = iconSeverity?.let(::severityIcon)
        changeListeners.forEach { it() }
    }

    /** Opens the popup; Esc goes back to [opened], which reopening it after "Edit Severities…" keeps. */
    private fun showPopup(opened: StoredSeverities = StoredSeverities.load(project)) {
        lateinit var popup: JBPopup
        val edit = ActionLink(InspectionFilterBundle.message("dialog.severities.edit")) {
            // A modal dialog would take the focus from the popup, and so close it anyway; opened again when done.
            popup.cancel()
            editSeverities()
            showPopup(opened)
        }
        popup = showChooserPopup(button, buildContent(), links = listOf(edit)) {
            opened.store(project)
            changed()
        }
    }

    private fun buildContent(): JComponent {
        val severities = availableSeverities(project)
        val checkBoxes = severities.associate { it.name to JBCheckBox() }
        // Beside the check box rather than its text, which cannot carry an icon: a check box's own icon replaces its box.
        val labels = severities.associate { severity ->
            val checkBox = checkBoxes.getValue(severity.name)
            val icon = severityIcon(severity)
            severity.name to JBLabel(severity.displayCapitalizedName, icon, JBLabel.LEADING).apply {
                labelFor = checkBox
                // Not every look and feel makes one of an icon that is not an image, and a label with none is as narrow
                // as its text: the popup, as wide as it opened, would then cut the text short once the label is enabled.
                disabledIcon = icon?.let(IconLoader::getDisabledIcon)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (checkBox.isEnabled) checkBox.doClick()
                    }
                })
            }
        }
        val lowest = ComboBox(CollectionComboBoxModel(severities, severities.first { it.name == loadLowestSeverityName(project) }))
        lowest.renderer = SimpleListCellRenderer.create { label, severity, _ ->
            label.text = severity?.displayCapitalizedName.orEmpty()
            label.icon = severity?.let(::severityIcon)
        }
        val selectedMode = JBRadioButton(InspectionFilterBundle.message("dialog.severities.mode.selected"))
        val thisAndHigherMode = JBRadioButton(InspectionFilterBundle.message("dialog.severities.mode.thisAndHigher"))
        when (loadSeverityMode(project)) {
            SeverityMode.SELECTED -> selectedMode.isSelected = true
            SeverityMode.THIS_AND_HIGHER -> thisAndHigherMode.isSelected = true
        }

        fun update() {
            val thisAndHigher = thisAndHigherMode.isSelected
            lowest.isEnabled = thisAndHigher
            changed()
            for ((name, checkBox) in checkBoxes) {
                checkBox.isEnabled = !thisAndHigher
                checkBox.isSelected = name in reportedSeverityNames
                labels.getValue(name).isEnabled = !thisAndHigher
            }
        }

        selectedMode.addActionListener {
            storeSeverityMode(project, SeverityMode.SELECTED)
            update()
        }
        thisAndHigherMode.addActionListener {
            storeSeverityMode(project, SeverityMode.THIS_AND_HIGHER)
            update()
        }
        lowest.addActionListener {
            (lowest.selectedItem as? HighlightSeverity)?.let { storeLowestSeverityName(project, it.name) }
            update()
        }
        for (checkBox in checkBoxes.values) {
            checkBox.addActionListener {
                // A run with no severity chosen would switch every inspection off, so the last one cannot be cleared.
                if (checkBoxes.values.none { it.isSelected }) {
                    checkBox.isSelected = true
                }
                storeSelectedSeverityNames(project, checkBoxes.filterValues { it.isSelected }.keys)
                update()
            }
        }
        update()

        return panel {
            // Unbound: the DSL only makes the two exclusive, the mode is stored by the listeners above.
            // "Selected" last, just above the check boxes it goes with.
            buttonsGroup {
                row {
                    cell(thisAndHigherMode)
                    cell(lowest)
                }
                row { cell(selectedMode) }
            }
            indent {
                for ((name, checkBox) in checkBoxes) {
                    row {
                        cell(checkBox).gap(RightGap.SMALL)
                        cell(labels.getValue(name))
                    }
                }
            }
        }.apply { border = JBUI.Borders.empty(8, 12) }
    }

    private fun editSeverities() {
        val action = ActionManager.getInstance().getAction(EDIT_SEVERITIES_ACTION_ID) ?: return
        val event = AnActionEvent.createEvent(action, SimpleDataContext.getProjectContext(project), null,
                                              ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        // The editor is a modal dialog, so it is closed by the time this returns.
        ActionUtil.performAction(action, event)
        changed()
    }
}

/** All that is stored of the choice of severities, whichever the mode. */
private class StoredSeverities(val mode: SeverityMode, val lowestName: String, val selectedNames: Set<String>) {

    fun store(project: Project) {
        storeSeverityMode(project, mode)
        storeLowestSeverityName(project, lowestName)
        storeSelectedSeverityNames(project, selectedNames)
    }

    companion object {
        fun load(project: Project) =
            StoredSeverities(loadSeverityMode(project), loadLowestSeverityName(project), loadSelectedSeverityNames(project))
    }
}

/**
 * Sums up the severities a run reports in [project], as they were last chosen there, with the severity whose icon goes
 * with the summary, if there is one.
 */
internal fun severitySummary(project: Project): Pair<Summary, HighlightSeverity?> {
    val severities = availableSeverities(project)
    val reported = loadReportedSeverityNames(project)
    if (severities.all { it.name in reported }) {
        return Summary(InspectionFilterBundle.message("dialog.severities.all")) to null
    }
    if (loadSeverityMode(project) == SeverityMode.THIS_AND_HIGHER) {
        val lowest = severities.first { it.name == loadLowestSeverityName(project) }
        return Summary(InspectionFilterBundle.message("dialog.severities.thisAndHigher", lowest.displayCapitalizedName)) to lowest
    }
    val chosen = severities.filter { it.name in reported }
    return summaryOf(chosen.map { it.displayCapitalizedName }) to chosen.singleOrNull()
}

/** The icon the inspection settings show beside [severity], custom severities included. */
private fun severityIcon(severity: HighlightSeverity): Icon? = HighlightDisplayLevel.find(severity)?.icon
