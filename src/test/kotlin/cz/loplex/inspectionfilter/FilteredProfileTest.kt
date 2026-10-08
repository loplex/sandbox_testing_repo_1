package cz.loplex.inspectionfilter

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.CleanupLocalInspectionTool
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.PairedUnfairLocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.search.scope.TestsScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FilteredProfileTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Without it a unit-test run leaves a profile's tools uninitialized, and the profile empty.
        InspectionProfileImpl.INIT_INSPECTIONS = true
    }

    override fun tearDown() {
        try {
            InspectionProfileImpl.INIT_INSPECTIONS = false
        }
        finally {
            super.tearDown()
        }
    }

    fun testInspectionsOfOtherSeveritiesAreSwitchedOff() {
        val source = profileOf(AnError(), AWarning(), AWeakWarning())

        val filtered = FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.ERROR.name)), project)

        assertEquals(listOf("AnError"), enabledToolNames(filtered))
    }

    fun testAnInspectionStaysOnInTheScopesWhereItsSeverityIsWanted() {
        val inspection = AWeakWarning()
        val source = profileOf(inspection)
        source.addScope(source.getInspectionTool(inspection.shortName, project)!!, TestsScope.INSTANCE,
                        HighlightDisplayLevel.WARNING, true, project)

        val filtered = FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.WARNING.name)), project)

        val tools = filtered.getTools(inspection.shortName, project)
        assertTrue(tools.isEnabled)
        assertEquals(
            mapOf(TestsScope.INSTANCE.scopeId to true, null to false),
            tools.tools.associate { (if (it === tools.defaultState) null else it.scopeName) to it.isEnabled },
        )
    }

    fun testTheSourceProfileIsLeftAsItWas() {
        val source = profileOf(AnError(), AWarning())

        FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.ERROR.name)), project)

        assertEquals(listOf("AWarning", "AnError"), enabledToolNames(source))
    }

    fun testInspectionsOfTheGroupsLeftOutAreSwitchedOff() {
        val source = profileOf(AnError(), AWarning(), AnErrorOfAnotherGroup())
        val groups = GroupSelection(mapOf(listOf("Other") to false))

        val filtered = FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.ERROR.name), groups), project)

        assertEquals(listOf("AnError"), enabledToolNames(filtered))
    }

    fun testBefore261TheGrazieRunnerIsSwitchedOffWithItsGroup() {
        val source = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())
        val grammar = severityOf(source, "GrazieInspection")
        // Read from the profile, like the severities: where Grazie is loaded, the group is Grazie's own.
        val group = source.getToolsOrNull(GRAZIE_RUNNER, project)!!.groupPath
        val filter = InspectionFilter(setOf(grammar, HighlightSeverity.WARNING.name), GroupSelection(mapOf(group to false)))

        assertEquals(emptyList<String>(), enabledToolNames(FilteredProfile(source, filter, project, BUGGY_BUILD)))
        assertFalse(filterBreaksGrazieRunner(source, source, filter, project, BUGGY_BUILD))
    }

    fun testOnlyCleanupInspectionsAreKeptWhenAsked() {
        val source = profileOf(AnError(), ACleanupError())

        val filtered = FilteredProfile(source, InspectionFilter(ERRORS, requiredProperties = setOf(InspectionProperty.CLEANUP)),
                                       project)

        assertEquals(listOf("ACleanupError"), enabledToolNames(filtered))
    }

    fun testOnlyInspectionsModifiedAgainstTheDefaultsAreKeptWhenAsked() {
        val (source, defaults) = profileAndDefaultsOf(AnError(), AWarning())
        source.getToolsOrNull("AWarning", project)!!.defaultState.level = HighlightDisplayLevel.ERROR

        val filter = InspectionFilter(ERRORS, requiredProperties = setOf(InspectionProperty.MODIFIED))
        val filtered = FilteredProfile(source, filter, project, baseProfile = defaults)

        assertEquals(listOf("AWarning"), enabledToolNames(filtered))
        assertTrue(source.getToolsOrNull("AWarning", project)!!.isKeptBy(filter, defaults))
        assertFalse(source.getToolsOrNull("AnError", project)!!.isKeptBy(filter, defaults))
    }

    fun testOnlyBatchModeInspectionsAreKeptWhenAsked() {
        val source = profileOf(AnError(), ABatchModeError())

        val filtered = FilteredProfile(source, InspectionFilter(ERRORS, requiredProperties = setOf(InspectionProperty.BATCH_ONLY)),
                                       project)

        assertEquals(listOf("ABatchModeError"), enabledToolNames(filtered))
    }

    fun testBefore261TheGrazieRunnerFollowsItsChildrenWhateverItsOwnProperties() {
        val (source, defaults) = profileAndDefaultsOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())
        val grammar = source.getToolsOrNull("GrazieInspection", project)!!
        grammar.defaultState.level = HighlightDisplayLevel.ERROR
        val filter = InspectionFilter(setOf(HighlightSeverity.ERROR.name, HighlightSeverity.WARNING.name, severityOf(source, "GrazieStyle")),
                                      requiredProperties = setOf(InspectionProperty.MODIFIED))

        // The runner itself is not modified, and runs for the child that is.
        assertEquals(listOf("GrazieInspection", "GrazieInspectionRunner"),
                     enabledToolNames(FilteredProfile(source, filter, project, BUGGY_BUILD, defaults)))
        // And reports under the other child too, which is left out.
        assertTrue(filterBreaksGrazieRunner(source, source, filter, project, BUGGY_BUILD, defaults))
    }

    fun testBefore261TheGrazieRunnerIsSwitchedOffWithNoneOfItsChildren() {
        val source = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())

        val filtered = FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.WARNING.name)), project, BUGGY_BUILD)

        assertEquals(emptyList<String>(), enabledToolNames(filtered))
    }

    fun testBefore261TheGrazieRunnerRunsForAChildWhateverItsOwnSeverity() {
        val source = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())

        val filtered = FilteredProfile(source, InspectionFilter(setOf(severityOf(source, "GrazieInspection"))), project,
                                       BUGGY_BUILD)

        assertEquals(listOf("GrazieInspection", "GrazieInspectionRunner"), enabledToolNames(filtered))
    }

    fun testBefore261AGrazieRunnerSwitchedOffInTheSourceStaysOff() {
        val source = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())
        source.getToolsOrNull(GRAZIE_RUNNER, project)!!.isEnabled = false

        val grammar = severityOf(source, "GrazieInspection")

        assertEquals(listOf("GrazieInspection"),
                     enabledToolNames(FilteredProfile(source, InspectionFilter(setOf(grammar)), project, BUGGY_BUILD)))
        assertFalse(breaksGrazieRunner(source, source, grammar, build = BUGGY_BUILD))
    }

    fun testFrom261TheGrazieRunnerIsFilteredBySeverityLikeAnyOther() {
        val source = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())

        assertEquals(listOf("GrazieInspection"),
                     enabledToolNames(FilteredProfile(source, InspectionFilter(setOf(severityOf(source, "GrazieInspection"))),
                                                      project, FIXED_BUILD)))
        assertEquals(listOf("GrazieInspectionRunner"),
                     enabledToolNames(FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.WARNING.name)), project,
                                                      FIXED_BUILD)))
    }

    fun testBefore261ChoosingOneGrazieChildWithoutTheOtherBreaksTheRun() {
        val source = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())

        val grammar = severityOf(source, "GrazieInspection")
        val style = severityOf(source, "GrazieStyle")

        assertTrue(breaksGrazieRunner(source, source, grammar, build = BUGGY_BUILD))
        assertFalse(breaksGrazieRunner(source, source, grammar, style, build = BUGGY_BUILD))
        // Neither child: the runner does not run.
        assertFalse(breaksGrazieRunner(source, source, HighlightSeverity.WARNING.name, build = BUGGY_BUILD))
        assertFalse(breaksGrazieRunner(source, source, grammar, build = FIXED_BUILD))
    }

    fun testWhetherTheGrazieRunnerBreaksTheRunIsDecidedByTheProjectProfile() {
        val chosen = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())
        val projectProfile = profileOf(GrazieInspectionRunner(), GrazieInspection(), GrazieStyle())
        val grammar = severityOf(chosen, "GrazieInspection")

        // The runner reports only under the children that are on in the project's profile.
        projectProfile.getToolsOrNull("GrazieStyle", project)!!.isEnabled = false
        assertFalse(breaksGrazieRunner(chosen, projectProfile, grammar, build = BUGGY_BUILD))

        // And under those whatever the chosen profile says.
        chosen.getToolsOrNull("GrazieStyle", project)!!.isEnabled = false
        projectProfile.getToolsOrNull("GrazieStyle", project)!!.isEnabled = true
        assertTrue(breaksGrazieRunner(chosen, projectProfile, grammar, build = BUGGY_BUILD))
    }

    fun testWithoutGrazieTheWorkaroundDoesNothing() {
        val source = profileOf(AnError(), AWarning())

        assertEquals(listOf("AnError"),
                     enabledToolNames(FilteredProfile(source, InspectionFilter(setOf(HighlightSeverity.ERROR.name)), project, BUGGY_BUILD)))
        assertFalse(breaksGrazieRunner(source, source, HighlightSeverity.ERROR.name, build = BUGGY_BUILD))
    }

    fun testWithNoFilterOfProblemsNoInspectionIsWrapped() {
        val filtered = FilteredProfile(profileOf(AnError(), AnExternalError(), APairedUnfairError(), ABatchModeError()), InspectionFilter(ERRORS),
                                       project)

        assertEquals(mapOf("ABatchModeError" to listOf(false), "APairedUnfairError" to listOf(false), "AnError" to listOf(false),
                           "AnExternalError" to listOf(false)), wrappedByScope(filtered))
    }

    fun testWithAFilterOfProblemsLocalInspectionsAreWrappedInEveryScope() {
        val source = profileOf(AnError(), AnExternalError(), APairedUnfairError(), ABatchModeError())
        source.addScope(source.getInspectionTool(AnError().shortName, project)!!, TestsScope.INSTANCE, HighlightDisplayLevel.ERROR, true, project)

        val filtered = FilteredProfile(source, InspectionFilter(ERRORS, problems = ProblemFilter(withQuickFix = true)), project)

        // A paired one runs in batch mode as the global one it names, which the run finds by the type of its tool.
        assertEquals(mapOf("ABatchModeError" to listOf(false), "APairedUnfairError" to listOf(false), "AnError" to listOf(true, true),
                           "AnExternalError" to listOf(true)), wrappedByScope(filtered))
    }

    fun testAWrappedInspectionKeepsWhatTheRunKnowsItBy() {
        val source = profileOf(AnError(), AnExternalError())

        val filtered = FilteredProfile(source, InspectionFilter(ERRORS, problems = ProblemFilter(withQuickFix = true)), project)

        for (shortName in listOf(AnError().shortName, AnExternalError().shortName)) {
            val original = source.getInspectionTool(shortName, project)!!
            val wrapped = filtered.getInspectionTool(shortName, project)!!
            for (wrapper in listOf(wrapped, wrapped.createCopy())) {
                assertNotSame(original.tool.javaClass, wrapper.tool.javaClass)
                assertEquals(original.shortName, wrapper.shortName)
                assertEquals(original.displayName, wrapper.displayName)
                assertEquals(original.groupPath.toList(), wrapper.groupPath.toList())
                assertEquals(original.tool is ExternalAnnotatorBatchInspection, wrapper.tool is ExternalAnnotatorBatchInspection)
            }
        }
    }

    private fun breaksGrazieRunner(
        profile: InspectionProfileImpl,
        projectProfile: InspectionProfileImpl,
        vararg severityNames: String,
        build: Int,
    ): Boolean = filterBreaksGrazieRunner(profile, projectProfile, InspectionFilter(severityNames.toSet()), project, build)

    private fun severityOf(profile: InspectionProfileImpl, shortName: String): String =
        profile.getToolsOrNull(shortName, project)!!.level.severity.name

    private fun profileOf(vararg inspections: InspectionProfileEntry): InspectionProfileImpl =
        newProfile("Source", supplierOf(*inspections), InspectionProfileImpl.BASE_PROFILE.get(), project)

    /** A profile of [inspections], and another of them as they are by default, for the first to be modified against. */
    private fun profileAndDefaultsOf(vararg inspections: InspectionProfileEntry): Pair<InspectionProfileImpl, InspectionProfileImpl> {
        val supplier = supplierOf(*inspections)
        val defaults = newProfile("Defaults", supplier, InspectionProfileImpl.BASE_PROFILE.get(), project)
        return newProfile("Source", supplier, defaults, project) to defaults
    }

    private fun enabledToolNames(profile: InspectionProfileImpl): List<String> =
        profile.getAllEnabledInspectionTools(project).map { it.tool.shortName }.sorted()

    /** For each inspection switched on in [profile], whether the tool of each of its scopes is wrapped. */
    private fun wrappedByScope(profile: InspectionProfileImpl): Map<String, List<Boolean>> =
        profile.tools.filter { it.isEnabled }.associate { tools ->
            tools.shortName to tools.tools.map { it.tool.tool.javaClass.enclosingClass != FilteredProfileTest::class.java }
        }

    // One class per inspection, each with a constructor taking nothing: copying a profile copies its inspections by
    // instantiating their classes anew, the way the platform instantiates those registered in plugin.xml.
    private abstract class TestInspection(
        private val level: HighlightDisplayLevel,
        private val group: GroupPath = listOf("Test"),
    ) : LocalInspectionTool() {
        override fun getShortName(): String = javaClass.simpleName
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = group.last()
        override fun getGroupPath(): Array<String> = group.toTypedArray()
        override fun getDefaultLevel(): HighlightDisplayLevel = level
        override fun isEnabledByDefault(): Boolean = true
    }

    private class AnError : TestInspection(HighlightDisplayLevel.ERROR)
    private class AWarning : TestInspection(HighlightDisplayLevel.WARNING)
    private class AWeakWarning : TestInspection(HighlightDisplayLevel.WEAK_WARNING)
    private class AnErrorOfAnotherGroup : TestInspection(HighlightDisplayLevel.ERROR, listOf("Other", "Subgroup"))
    private class ACleanupError : TestInspection(HighlightDisplayLevel.ERROR), CleanupLocalInspectionTool

    private class AnExternalError : TestInspection(HighlightDisplayLevel.ERROR), ExternalAnnotatorBatchInspection

    private class APairedUnfairError : TestInspection(HighlightDisplayLevel.ERROR), PairedUnfairLocalInspectionTool {
        override fun getInspectionForBatchShortName(): String = "ABatchModeError"
    }

    // Global, and with no local inspection to highlight with in the editor, so it runs in batch mode only.
    private class ABatchModeError : GlobalInspectionTool() {
        override fun getShortName(): String = javaClass.simpleName
        override fun getDisplayName(): String = shortName
        override fun getGroupDisplayName(): String = "Test"
        override fun getDefaultLevel(): HighlightDisplayLevel = HighlightDisplayLevel.ERROR
        override fun isEnabledByDefault(): Boolean = true
    }

    // Named as Grazie names them. A wrapper takes the level and group registered under its inspection's name, so where
    // Grazie is loaded, as it is in the IDE the tests run in, these get Grazie's own; the tests therefore read the
    // children's severities, and the group, from the profile. Either way the runner is a warning and each child is something else.
    private class GrazieInspectionRunner : TestInspection(HighlightDisplayLevel.WARNING)
    private class GrazieInspection : TestInspection(HighlightDisplayLevel.ERROR)
    private class GrazieStyle : TestInspection(HighlightDisplayLevel.WEAK_WARNING)

    private companion object {
        val ERRORS = setOf(HighlightSeverity.ERROR.name)
        const val BUGGY_BUILD = 253
        const val FIXED_BUILD = 261
    }
}
