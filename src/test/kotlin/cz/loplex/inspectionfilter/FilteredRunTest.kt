package cz.loplex.inspectionfilter

import com.intellij.analysis.AnalysisScope
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.CleanupLocalInspectionTool
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalSimpleInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.NamedScopesHolder
import com.intellij.psi.search.scope.packageSet.PackageSetBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.reflect.KClass

/**
 * Runs "Inspect Code" for real - the global inspection context the action runs, local inspections in batch mode and
 * global ones - over two files, and compares what a run with a [FilteredProfile] reports with what the filter keeps.
 *
 * Every inspection here reports exactly one problem in every file it inspects, so a run's findings name the
 * inspections that ran, and where. The findings expected are written out rather than derived from the filter.
 */
class FilteredRunTest : BasePlatformTestCase() {

    private lateinit var savedScopes: Array<NamedScope>

    override fun setUp() {
        super.setUp()
        // Without it a unit-test run leaves a profile's tools uninitialized, and the profile empty.
        InspectionProfileImpl.INIT_INSPECTIONS = true
        val scopes = NamedScopeManager.getInstance(project)
        savedScopes = scopes.editableScopes
        scopes.addScope(FILE_B_SCOPE)
        myFixture.addFileToProject("a.txt", "Text of a")
        myFixture.addFileToProject("b.txt", "Text of b")
    }

    override fun tearDown() {
        try {
            InspectionProfileImpl.INIT_INSPECTIONS = false
            forgetStoredFilter(project)
            NamedScopeManager.getInstance(project).scopes = savedScopes
        }
        finally {
            super.tearDown()
        }
    }

    fun testAnUnfilteredRunReportsEveryInspectionInEveryFile() {
        val all = findingsOf(AnError::class, AWarning::class, AWeakWarning::class, AnErrorOfAnotherGroup::class, ACleanupError::class,
                             ABatchModeError::class)

        assertEquals(all, run(sourceProfile()))
    }

    fun testARunReportsOnlyTheInspectionsOfTheSeveritiesChosen() {
        val filtered = FilteredProfile(sourceProfile(), InspectionFilter(ERRORS), project)

        assertEquals(findingsOf(AnError::class, AnErrorOfAnotherGroup::class, ACleanupError::class, ABatchModeError::class), run(filtered))
    }

    fun testAnInspectionReportsOnlyInTheScopesWhereItsSeverityIsChosen() {
        val source = sourceProfile()
        source.addScope(source.getInspectionTool(AWeakWarning::class.shortName, project)!!, FILE_B_SCOPE, HighlightDisplayLevel.WARNING, true,
                        project)

        val filtered = FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.WARNING.name)), project)

        assertEquals((findingsOf(AWarning::class) + "${AWeakWarning::class.shortName} in b.txt").sorted(), run(filtered))
    }

    fun testARunReportsNothingOfTheGroupsLeftOut() {
        val filter = InspectionFilter(ERRORS, GroupSelection(mapOf(listOf("Other") to false)))

        assertEquals(findingsOf(AnError::class, ACleanupError::class, ABatchModeError::class), run(FilteredProfile(sourceProfile(), filter, project)))
    }

    fun testARunReportsOnlyCleanupInspectionsWhenAsked() {
        val filter = InspectionFilter(ERRORS, requiredProperties = setOf(InspectionProperty.CLEANUP))

        assertEquals(findingsOf(ACleanupError::class), run(FilteredProfile(sourceProfile(), filter, project)))
    }

    fun testARunReportsOnlyInspectionsModifiedAgainstTheDefaultsWhenAsked() {
        val supplier = supplierOf(*inspections())
        val defaults = newProfile("Defaults", supplier, InspectionProfileImpl.BASE_PROFILE.get(), project)
        val source = newProfile("Source", supplier, defaults, project)
        source.getToolsOrNull(AWarning::class.shortName, project)!!.defaultState.level = HighlightDisplayLevel.ERROR

        val filter = InspectionFilter(ERRORS, requiredProperties = setOf(InspectionProperty.MODIFIED))

        assertEquals(findingsOf(AWarning::class), run(FilteredProfile(source, filter, project, baseProfile = defaults)))
    }

    fun testARunReportsOnlyBatchModeInspectionsWhenAsked() {
        val filter = InspectionFilter(ERRORS, requiredProperties = setOf(InspectionProperty.BATCH_ONLY))

        assertEquals(findingsOf(ABatchModeError::class), run(FilteredProfile(sourceProfile(), filter, project)))
    }

    fun testTheActionRunsWithTheFilterStoredAndItsRerunsKeepIt() {
        useAsProjectProfile(project, sourceProfile(), testRootDisposable)
        storeSelectedSeverityNames(project, ERRORS)
        storeRequiredProperties(project, setOf(InspectionProperty.CLEANUP))
        val action = InspectCodeWithFiltersAction()
        val scope = AnalysisScope(project)
        val contexts = (InspectionManager.getInstance(project) as InspectionManagerEx).runningContexts
        val contextsBefore = contexts.toSet()

        runAction(action, project, scope)
        val context = (contexts - contextsBefore).single()
        assertEquals(findingsOf(ACleanupError::class), findingsOf(context))

        // A filter chosen since does not change what a rerun reports. This one would leave out the one inspection
        // reported: as filtering a filtered profile again can only narrow it, a filter that kept it would not show
        // whether it had been applied.
        storeSelectedSeverityNames(project, setOf(HighlightSeverity.WARNING.name))
        storeRequiredProperties(project, emptySet())
        // "Rerun" in the results runs the same context again.
        runAndWait(context, scope)
        assertEquals(findingsOf(ACleanupError::class), findingsOf(context))
        // A run repeated after the JDK was chosen comes back to the action with the profile of the run it repeats.
        runAction(action, project, scope)
        assertEquals(findingsOf(ACleanupError::class), findingsOf(context))
    }

    private fun sourceProfile(): InspectionProfileImpl =
        newProfile("Source", supplierOf(*inspections()), InspectionProfileImpl.BASE_PROFILE.get(), project)

    private fun inspections(): Array<InspectionProfileEntry> =
        arrayOf(AnError(), AWarning(), AWeakWarning(), AnErrorOfAnotherGroup(), ACleanupError(), ABatchModeError())

    /** What a run with [profile] over the whole project reports. */
    private fun run(profile: InspectionProfileImpl): List<String> = runInspections(project, profile, ::findingsOf)

    /** The findings of [context]'s last run, as "inspection in file", sorted. */
    private fun findingsOf(context: GlobalInspectionContextImpl): List<String> =
        problemsOf(context).map { (wrapper, problem) ->
            "${wrapper.shortName} in ${(problem as ProblemDescriptor).psiElement.containingFile.name}"
        }.sorted()

    /** The findings of a run of [inspections] over both files, sorted as [findingsOf] sorts them. */
    private fun findingsOf(vararg inspections: KClass<*>): List<String> =
        inspections.flatMap { listOf("${it.shortName} in a.txt", "${it.shortName} in b.txt") }.sorted()

    // One class per inspection, each with a constructor taking nothing: copying a profile copies its inspections by
    // instantiating their classes anew.
    private abstract class ReportingInspection(
        private val level: HighlightDisplayLevel,
        private val group: GroupPath = listOf("Test"),
    ) : LocalInspectionTool() {
        override fun getShortName(): String = javaClass.simpleName
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = group.last()
        override fun getGroupPath(): Array<String> = group.toTypedArray()
        override fun getDefaultLevel(): HighlightDisplayLevel = level
        override fun isEnabledByDefault(): Boolean = true

        override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                holder.registerProblem(file, "Reported by $shortName")
            }
        }
    }

    private class AnError : ReportingInspection(HighlightDisplayLevel.ERROR)
    private class AWarning : ReportingInspection(HighlightDisplayLevel.WARNING)
    private class AWeakWarning : ReportingInspection(HighlightDisplayLevel.WEAK_WARNING)
    private class AnErrorOfAnotherGroup : ReportingInspection(HighlightDisplayLevel.ERROR, listOf("Other", "Subgroup"))
    private class ACleanupError : ReportingInspection(HighlightDisplayLevel.ERROR), CleanupLocalInspectionTool

    // Global, and with no local inspection to highlight with in the editor, so it runs in batch mode only.
    private class ABatchModeError : GlobalSimpleInspectionTool() {
        override fun getShortName(): String = javaClass.simpleName
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = "Test"
        override fun getDefaultLevel(): HighlightDisplayLevel = HighlightDisplayLevel.ERROR
        override fun isEnabledByDefault(): Boolean = true

        override fun checkFile(
            psiFile: PsiFile,
            manager: InspectionManager,
            problemsHolder: ProblemsHolder,
            globalContext: GlobalInspectionContext,
            problemDescriptionsProcessor: ProblemDescriptionsProcessor,
        ) {
            problemsHolder.registerProblem(psiFile, "Reported by $shortName")
        }
    }

    /** Only the file b.txt; a scope for an inspection to have another severity in. */
    private class FileBSet : PackageSetBase() {
        override fun contains(file: VirtualFile, project: Project, holder: NamedScopesHolder?): Boolean = file.name == "b.txt"
        override fun createCopy() = this
        override fun getText() = "file:b.txt"
        override fun getNodePriority() = 0
    }

    private companion object {
        val ERRORS = setOf(HighlightSeverity.ERROR.name)
        val FILE_B_SCOPE = NamedScope("File b", FileBSet())

        /** The inspection's short name, which each class here takes from its own. */
        val KClass<*>.shortName: String get() = java.simpleName
    }
}
