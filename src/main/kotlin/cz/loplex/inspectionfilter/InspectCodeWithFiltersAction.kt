package cz.loplex.inspectionfilter

import com.intellij.analysis.AnalysisScope
import com.intellij.analysis.BaseAnalysisActionDialog
import com.intellij.application.options.schemes.SchemesCombo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.actions.CodeInspectionAction
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel

private const val GRAZIE_WARNING_SHOWN = "shown"
private const val GRAZIE_WARNING_HIDDEN = "hidden"

/**
 * "Inspect Code", with a choice of the severities, groups and kinds of inspections to report, and of their problems.
 *
 * The dialog is the one "Inspect Code" shows, with the choice added under the profile. The inspections are then run
 * with a copy of the chosen profile in which all the others are switched off - see [FilteredProfile] - so the results
 * hold nothing else, and "Rerun" in the results keeps the same filter.
 */
internal class InspectCodeWithFiltersAction : CodeInspectionAction() {

    /** The number of the run started last, which tells the "Rerun" of an older one that its state is gone. */
    private var lastRunId = 0

    /** The context of the run being started, or of the one its "Rerun" repeats. */
    private var context: GlobalInspectionContextEx? = null

    override fun getAdditionalActionSettings(project: Project, dialog: BaseAnalysisActionDialog): JComponent {
        val profileSettings = super.getAdditionalActionSettings(project, dialog)
        // Read rather than myExternalProfile: a combo notifies the listener added last first, so while ours runs, the
        // one that sets myExternalProfile has not run yet.
        val profilesCombo = profileSettings?.let { UIUtil.findComponentOfType(it, SchemesCombo::class.java) }
        val severityChooser = SeverityChooser(project)
        val groupChooser = GroupChooser(project)
        val propertyChooser = PropertyChooser(project)
        val problemChooser = ProblemChooser(project)
        val baselineVersion = ApplicationInfo.getInstance().build.baselineVersion
        val grazieWarning = JBLabel(InspectionFilterBundle.message("dialog.grazie.warning.text"), AllIcons.General.Warning,
                                    JBLabel.LEADING)
        // The dialog sizes its content once, when it opens, and scrolls whatever grows later out of sight. The warning
        // therefore keeps its place while hidden: the layout is as large as the larger of its cards.
        val grazieWarningLayout = CardLayout()
        val grazieWarningCards = JPanel(grazieWarningLayout).apply {
            isOpaque = false
            add(grazieWarning, GRAZIE_WARNING_SHOWN)
            add(JPanel().apply { isOpaque = false }, GRAZIE_WARNING_HIDDEN)
        }

        fun selectedProfile(): InspectionProfileImpl =
            profilesCombo?.selectedScheme as? InspectionProfileImpl
            ?: InspectionProjectProfileManager.getInstance(project).currentProfile

        fun updateGrazieWarning() {
            val profile = selectedProfile()
            val filter = InspectionFilter(severityChooser.reportedSeverityNames, groupChooser.selection,
                                          propertyChooser.requiredProperties)
            val shown = filterBreaksGrazieRunner(profile, InspectionProjectProfileManager.getInstance(project).currentProfile,
                                                 filter, project, baselineVersion)
            if (shown) {
                grazieWarning.toolTipText = grazieWarningTooltip(profile, project)
            }
            grazieWarningLayout.show(grazieWarningCards, if (shown) GRAZIE_WARNING_SHOWN else GRAZIE_WARNING_HIDDEN)
        }

        severityChooser.addChangeListener { updateGrazieWarning() }
        groupChooser.addChangeListener { updateGrazieWarning() }
        propertyChooser.addChangeListener { updateGrazieWarning() }
        profilesCombo?.addActionListener {
            groupChooser.setProfile(selectedProfile())
            updateGrazieWarning()
        }
        groupChooser.setProfile(selectedProfile())
        updateGrazieWarning()

        return panel {
            if (profileSettings != null) {
                row { cell(profileSettings).align(AlignX.FILL) }
            }
            // Just above the filters, where it is seen, taking over the gap before them; and only where the bug is,
            // so as not to leave an empty row everywhere else.
            val hasGrazieWarningRow = grazieRunnerWorkaroundApplies(
                InspectionProjectProfileManager.getInstance(project).currentProfile, project, baselineVersion)
            if (hasGrazieWarningRow) {
                row { cell(grazieWarningCards) }.topGap(TopGap.MEDIUM)
            }
            group(InspectionFilterBundle.message("dialog.filters.title")) {
                // One line each, so that the dialog keeps its height: it scrolls whatever grows after it opened.
                row(InspectionFilterBundle.message("dialog.severities.label")) {
                    cell(severityChooser.summary).resizableColumn()
                    cell(severityChooser.button)
                }
                row(InspectionFilterBundle.message("dialog.groups.label")) {
                    cell(groupChooser.summary).resizableColumn()
                    cell(groupChooser.button)
                }
                row(InspectionFilterBundle.message("dialog.properties.label")) {
                    cell(propertyChooser.summary).resizableColumn()
                    cell(propertyChooser.button)
                }
                row(InspectionFilterBundle.message("dialog.problems.label")) {
                    cell(problemChooser.summary).resizableColumn()
                    cell(problemChooser.button)
                }
            }.apply { if (hasGrazieWarningRow) topGap(TopGap.NONE) }
        }
    }

    override fun runInspections(project: Project, scope: AnalysisScope) {
        val profile = myExternalProfile ?: InspectionProjectProfileManager.getInstance(project).currentProfile
        // A run the platform repeats itself, such as one stopped for a missing JDK and repeated once a JDK is chosen,
        // comes back here with the profile of the run it repeats, already filtered. Filtering it again by what is
        // chosen now could only narrow it, and a repeated run is meant to repeat the run. ("Rerun" in the results does
        // not come back here: it runs the same inspection context, profile and all, again.)
        if (profile !is FilteredProfile) {
            myExternalProfile = FilteredProfile(profile, loadInspectionFilter(project), project)
        }
        runInspectionsInOwnContext(project, scope)
    }

    override fun analyze(project: Project, scope: AnalysisScope) {
        try {
            super.analyze(project, scope)
        }
        finally {
            context = null
        }
    }

    /**
     * What [CodeInspectionAction.runInspections] does, step for step, but in a context this action holds itself, so
     * that it can set the context up before the run starts: the one the platform's action holds is private to it.
     * What it sets up is the filter of the problems that the profile cannot filter - see [filterProblems].
     */
    private fun runInspectionsInOwnContext(project: Project, scope: AnalysisScope) {
        val runId = ++lastRunId
        scope.setSearchInLibraries(false)
        FileDocumentManager.getInstance().saveAllDocuments()

        val externalProfile = myExternalProfile
        val context = context ?: (InspectionManager.getInstance(project) as InspectionManagerEx).createNewGlobalContext()
            .also { context = it }
        context.setRerunAction {
            DumbService.getInstance(project).smartInvokeLater {
                // Another run has started since, and the state of this one is gone.
                if (runId != lastRunId || project.isDisposed || !scope.isValid) {
                    return@smartInvokeLater
                }
                myExternalProfile = externalProfile
                this.context = context
                FileDocumentManager.getInstance().saveAllDocuments()
                analyze(project, scope)
            }
        }
        context.setExternalProfile(externalProfile)
        filterProblems(context, (externalProfile as? FilteredProfile)?.problemFilter)
        context.setCurrentScope(scope)
        context.doInspections(scope)
    }
}

/** Names the Grazie inspections and their severities as [profile] has them, which is how the dialog lists them. */
private fun grazieWarningTooltip(profile: InspectionProfileImpl, project: Project): String {
    val children = grazieRunnerChildren(profile, project)
    return HtmlChunk.raw(InspectionFilterBundle.message(
        "dialog.grazie.warning.tooltip",
        children.joinToString(" and ") { it.defaultState.tool.displayName },
        children.joinToString(" and ") { it.defaultState.level.severity.displayCapitalizedName },
    )).wrapWith(HtmlChunk.html()).toString()
}
