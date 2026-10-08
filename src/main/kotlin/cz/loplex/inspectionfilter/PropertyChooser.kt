package cz.loplex.inspectionfilter

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JButton

/**
 * The dialog's choice of the properties an inspection must have to be reported: a [summary] of the choice, and a
 * [button] that opens a check box for each, stored at once on every change, like the other choices.
 */
internal class PropertyChooser(private val project: Project) {

    /** The properties currently required. */
    var requiredProperties: Set<InspectionProperty> = loadRequiredProperties(project)
        private set

    val summary = JBLabel()
    val button = JButton(InspectionFilterBundle.message("dialog.choose")).apply { addActionListener { showPopup() } }
    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        updateSummary()
    }

    /** Calls [listener] whenever [requiredProperties] has changed. */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    private fun choose(chosen: Set<InspectionProperty>) {
        requiredProperties = chosen
        storeRequiredProperties(project, chosen)
        updateSummary()
        changeListeners.forEach { it() }
    }

    private fun updateSummary() {
        val (text, tooltip) = propertySummary(requiredProperties)
        summary.text = text
        summary.toolTipText = tooltip
    }

    private fun showPopup() {
        val opened = requiredProperties
        val checkBoxes = InspectionProperty.entries.associateWith { property ->
            JBCheckBox(InspectionFilterBundle.message(property.messageKey), property in requiredProperties)
        }
        for (checkBox in checkBoxes.values) {
            checkBox.addActionListener { choose(checkBoxes.filterValues { it.isSelected }.keys) }
        }
        val content = panel {
            for ((property, checkBox) in checkBoxes) {
                row { cell(checkBox).comment(InspectionFilterBundle.message(property.descriptionKey)) }
            }
        }.apply { border = JBUI.Borders.empty(8, 12) }
        showChooserPopup(button, content) { choose(opened) }
    }
}

/** Sums up [required]: their short names, with what each means in the tooltip. */
internal fun propertySummary(required: Set<InspectionProperty>): Summary {
    if (required.isEmpty()) {
        return Summary(InspectionFilterBundle.message("dialog.properties.all"))
    }
    val properties = InspectionProperty.entries.filter { it in required }
    val text = properties.joinToString(", ") { InspectionFilterBundle.message(it.shortKey) }.replaceFirstChar(Char::uppercaseChar)
    return Summary(text, linesOf(properties.map {
        "${InspectionFilterBundle.message(it.messageKey)}: ${InspectionFilterBundle.message(it.descriptionKey)}"
    }))
}
