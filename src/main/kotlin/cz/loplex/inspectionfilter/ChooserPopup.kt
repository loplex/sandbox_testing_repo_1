package cz.loplex.inspectionfilter

import com.intellij.CommonBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/** How many names a summary gives before it says only how many more there are; its tooltip names them all. */
private const val NAMED_IN_SUMMARY = 3

/** A choice summed up in one line of the dialog, with the whole of it in the tooltip when the line cannot hold it. */
internal data class Summary(val text: String, val tooltip: String? = null)

/** Sums up [names]: `A, B, C and 2 more`, with all of them in the tooltip; or all of them, when they are few. */
internal fun summaryOf(names: List<String>): Summary {
    val named = names.take(NAMED_IN_SUMMARY).joinToString(", ")
    if (names.size <= NAMED_IN_SUMMARY) {
        return Summary(named)
    }
    return Summary(InspectionFilterBundle.message("dialog.summary.more", named, names.size - NAMED_IN_SUMMARY), linesOf(names))
}

/** [lines] as the HTML of a tooltip, one to a line. */
internal fun linesOf(lines: List<String>): String =
    HtmlChunk.html().children(lines.flatMap { listOf(HtmlChunk.text(it), HtmlChunk.br()) }.dropLast(1)).toString()

/**
 * Shows [content] under [owner] the way every choice of the dialog is shown: every change in it is applied, and
 * stored, as it is made, so its OK only closes it, and so do Enter and a click outside it; Esc closes it after [revert]
 * has gone back to what was chosen when it opened. [links] go at the bottom, left of the OK; [focused] gets the focus.
 */
internal fun showChooserPopup(
    owner: JComponent,
    content: JComponent,
    focused: JComponent? = null,
    links: List<JComponent> = emptyList(),
    revert: () -> Unit,
): JBPopup {
    val ok = JButton(CommonBundle.getOkButtonText()).apply {
        // As high as the links beside it rather than as a dialog's buttons; what the platform's own popups set.
        putClientProperty("ActionToolbar.smallVariant", true)
    }
    val panel = JPanel(BorderLayout()).apply {
        add(content, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(12), 0)).apply { links.forEach(::add) }, BorderLayout.WEST)
            add(ok, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }
    val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, focused ?: ok)
        .setRequestFocus(true)
        .setResizable(true)
        .setMovable(true)
        .setCancelOnClickOutside(true)
        // The popup's own Esc would close it as a click outside does, keeping the changes.
        .setCancelKeyEnabled(false)
        .createPopup()
    ok.addActionListener { popup.closeOk(null) }
    // Esc closes an open list of a combo box first, and Enter picks from it.
    onKeyUnlessListOpen(CommonShortcuts.ESCAPE, panel) {
        revert()
        popup.cancel()
    }
    onKeyUnlessListOpen(CommonShortcuts.ENTER, panel) { focused ->
        // As in a dialog, a button with the focus is pressed: else a link such as Revert would close the popup instead.
        if (focused is JButton && focused.isEnabled) focused.doClick() else popup.closeOk(null)
    }
    popup.showUnderneathOf(owner)
    return popup
}

/** Has [shortcut] in [component] do [perform] with the focus owner, unless that is a combo box with its list open. */
private fun onKeyUnlessListOpen(shortcut: ShortcutSet, component: JComponent, perform: (focused: Component?) -> Unit) {
    fun focusOwner(): Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    object : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (focusOwner() as? JComboBox<*>)?.isPopupVisible != true
        }

        override fun actionPerformed(e: AnActionEvent) = perform(focusOwner())
    }.registerCustomShortcutSet(shortcut, component)
}
