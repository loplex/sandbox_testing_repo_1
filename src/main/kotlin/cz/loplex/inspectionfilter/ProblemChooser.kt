package cz.loplex.inspectionfilter

import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.event.DocumentEvent

/**
 * The dialog's choice of the problems to report: a [summary] of the choice, and a [button] that opens check boxes for
 * those on changed lines and those with a quick fix, and a field for the text of their message, stored at once on
 * every change, like the other choices.
 */
internal class ProblemChooser(private val project: Project) {

    /** The filter currently chosen. */
    var filter: ProblemFilter = loadProblemFilter(project)
        private set

    val summary = JBLabel()
    val button = JButton(InspectionFilterBundle.message("dialog.choose")).apply { addActionListener { showPopup() } }

    init {
        updateSummary()
    }

    private fun choose(chosen: ProblemFilter) {
        filter = chosen
        storeProblemFilter(project, chosen)
        updateSummary()
    }

    private fun updateSummary() {
        summary.text = problemSummary(filter)
    }

    private fun showPopup() {
        val opened = filter
        val onChangedLines = JBCheckBox(InspectionFilterBundle.message("dialog.problems.onChangedLines"), filter.onChangedLines)
        onChangedLines.addActionListener { choose(filter.copy(onChangedLines = onChangedLines.isSelected)) }
        val withQuickFix = JBCheckBox(InspectionFilterBundle.message("dialog.problems.withQuickFix"), filter.withQuickFix)
        withQuickFix.addActionListener { choose(filter.copy(withQuickFix = withQuickFix.isSelected)) }
        val messageContains = JBTextField(filter.messageContains, 24)
        messageContains.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                choose(filter.copy(messageContains = messageContains.text))
            }
        })
        val content = panel {
            row { cell(onChangedLines).comment(InspectionFilterBundle.message("dialog.problems.onChangedLines.description")) }
            row { cell(withQuickFix).comment(InspectionFilterBundle.message("dialog.problems.withQuickFix.description")) }
            row(InspectionFilterBundle.message("dialog.problems.messageContains")) {
                cell(messageContains).align(AlignX.FILL).comment(InspectionFilterBundle.message("dialog.problems.messageContains.description"))
            }
        }.apply { border = JBUI.Borders.empty(8, 12) }
        showChooserPopup(button, content, focused = messageContains) { choose(opened) }
    }
}

/** Sums up [filter]: what a problem must have to be reported. */
internal fun problemSummary(filter: ProblemFilter): String {
    val conditions = buildList {
        if (filter.onChangedLines) {
            add(InspectionFilterBundle.message("dialog.problems.summary.onChangedLines"))
        }
        if (filter.withQuickFix) {
            add(InspectionFilterBundle.message("dialog.problems.summary.withQuickFix"))
        }
        if (filter.messageContains.isNotBlank()) {
            add(InspectionFilterBundle.message("dialog.problems.summary.messageContains", filter.messageContains))
        }
    }
    if (conditions.isEmpty()) {
        return InspectionFilterBundle.message("dialog.problems.all")
    }
    return conditions.joinToString(", ").replaceFirstChar(Char::uppercaseChar)
}
