package cz.loplex.inspectionfilter

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInsight.actions.VcsFacade
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

/**
 * Runs "Inspect Code" with every inspection the IDE the tests run in registers - Java's among them - over a Java
 * file, as a check that the properties the filter asks of an inspection hold for inspections as they are registered,
 * and not only for those the other tests make up.
 *
 * The findings of a filtered run are compared with those of an unfiltered run of the inspections with the property.
 * Two inspections known to report on the file anchor the comparison: one with the property, which the filtered run
 * must report, and one without it, which it must not.
 */
class FilteredRunOfBuiltInInspectionsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Without it a unit-test run leaves a profile's tools uninitialized, and the profile empty.
        InspectionProfileImpl.INIT_INSPECTIONS = true
        myFixture.addFileToProject("Foo.java", """
            public final class Foo {
                private int written;

                public Foo() { written = 1; }

                public Foo(int value) { written = value; }

                int read() { return written; }

                boolean pointless(boolean value) { return value && true; }

                void recurse() { recurse(); }

                void unusedParameter(int never) { System.out.println(written); }

                private static class Nested {}

                // Its annotation, its name and its body each on a line of their own.
                @Deprecated
                void annotated() {
                    System.out.println("annotated");
                }
            }
        """.trimIndent())
    }

    override fun tearDown() {
        try {
            InspectionProfileImpl.INIT_INSPECTIONS = false
            project.putUserData(TEST_CHANGED_FILES, null)
        }
        finally {
            super.tearDown()
        }
    }

    fun testARunReportsOnlyBatchModeInspectionsWhenAsked() {
        // "Field can be final" is a global inspection with no counterpart in the editor.
        checkFilteredBy(InspectionProperty.BATCH_ONLY, kept = "CanBeFinal", leftOut = "PointlessBooleanExpression") {
            (it as? GlobalInspectionToolWrapper)?.worksInBatchModeOnly() == true
        }
    }

    fun testARunReportsOnlyCleanupInspectionsWhenAsked() {
        checkFilteredBy(InspectionProperty.CLEANUP, kept = "PointlessBooleanExpression", leftOut = "CanBeFinal") {
            it.isCleanupTool
        }
    }

    fun testWrappingAnInspectionChangesNothingItReports() {
        val source = InspectionProfileImpl("Built-in")
        val unfiltered = runInspections(project, source, ::problemsByInspection)
        val wrapped = InspectionProfileImpl("Wrapped").apply { initInspectionTools(project) }
        // With a filter that leaves nothing out, for which a filtered profile would not wrap them.
        for (tools in wrapped.tools) {
            for (state in tools.tools) {
                state.setTool(wrapForProblemFilter(state.tool, RunProblemFilter(ProblemFilter())))
            }
        }

        assertContainsElements(unfiltered.keys, "PointlessBooleanExpression", "InfiniteRecursion")
        assertEquals(unfiltered, runInspections(project, wrapped, ::problemsByInspection))
    }

    fun testLocalInspectionsReportOnlyTheProblemsWithAQuickFixWhenAsked() {
        val source = InspectionProfileImpl("Built-in")
        val unfiltered = runInspections(project, source, ::problemsByInspection)
        // "Infinite recursion" comes with no fix.
        assertContainsElements(unfiltered.keys, "PointlessBooleanExpression", "InfiniteRecursion")

        val allSeverities = availableSeverities(project).mapTo(HashSet()) { it.name }
        val filter = InspectionFilter(allSeverities, problems = ProblemFilter(withQuickFix = true))
        val filtered = runInspections(project, FilteredProfile(source, filter, project), ::problemsByInspection)

        // The profile filters the problems of local inspections only: those of the others are filtered by the run,
        // which the action sets up.
        val expected = unfiltered.mapValues { (shortName, problems) ->
            if (source.getInspectionTool(shortName, project) is LocalInspectionToolWrapper) problems.filter { it.endsWith(WITH_FIX) } else problems
        }.filterValues { it.isNotEmpty() }
        assertEquals(expected, filtered)
        assertContainsElements(filtered.keys, "PointlessBooleanExpression")
        assertDoesntContain(filtered.keys, "InfiniteRecursion")
    }

    fun testUnusedDeclarationShowsOnlyTheElementsWithAProblemTheFilterKeeps() {
        // It shows the classes and methods nothing uses with no problem reported in them; it reports one only for a
        // parameter, as "Parameter never is not used".
        assertEquals(listOf("Foo()", "Foo(int)", "Nested", "annotated()", "pointless(boolean)", "read()", "recurse()",
                            "unusedParameter(int)"),
                     unusedDeclarationsShown(null))
        assertEquals(listOf("unusedParameter(int)"), unusedDeclarationsShown(ProblemFilter(messageContains = "parameter")))
    }

    fun testUnusedDeclarationExportsOnlyTheElementsItShows() {
        // An export goes through every element the run knows of, and reaches the class Nested through its implicit
        // constructor too.
        assertContainsElements(unusedDeclarationsExported(null), "Foo.Nested: Class is not instantiated.", "Foo int read()")
        assertEquals(listOf("Foo void unusedParameter(int never)", "Foo void unusedParameter(int never): Parameter never is not used"),
                     unusedDeclarationsExported(ProblemFilter(messageContains = "parameter")))
    }

    fun testUnusedDeclarationExportsOnlyTheElementsItShowsAfterARerun() {
        // A rerun builds the graph of its elements anew.
        assertEquals(listOf("Foo void unusedParameter(int never)", "Foo void unusedParameter(int never): Parameter never is not used"),
                     unusedDeclarationsExported(ProblemFilter(messageContains = "parameter"), rerun = true))
    }

    fun testUnusedDeclarationShowsAnElementWithNoProblemByWhatItSaysOfIt() {
        val filter = ProblemFilter(messageContains = "IS NOT INSTANTIATED")

        assertEquals(listOf("Nested"), unusedDeclarationsShown(filter))
        assertEquals(listOf("Foo.Nested: Class is not instantiated."), unusedDeclarationsExported(filter))
    }

    fun testUnusedDeclarationMatchesWhatItSaysOfAnElementAsPlainText() {
        // "Method owner class is never instantiated OR All instantiations are not reachable from the entry points.",
        // which is a list. The method with an unused parameter is one nothing uses as well, whatever its problem.
        assertEquals(listOf("annotated()", "pointless(boolean)", "read()", "recurse()", "unusedParameter(int)"),
                     unusedDeclarationsShown(ProblemFilter(messageContains = "instantiated or all")))
    }

    fun testUnusedDeclarationShowsEveryElementWithNoProblemAsOneWithAQuickFix() {
        val filter = ProblemFilter(withQuickFix = true)

        assertEquals(unusedDeclarationsShown(null), unusedDeclarationsShown(filter))
        assertEquals(unusedDeclarationsExported(null), unusedDeclarationsExported(filter))
    }

    fun testUnusedDeclarationShowsAnElementWithNoProblemWhenItsNameIsOnAChangedLine() {
        val filter = ProblemFilter(onChangedLines = true)

        changeSinceTheLastCommit("void annotated() {", "void annotated() { // before")
        assertEquals(listOf("annotated()"), unusedDeclarationsShown(filter))
        assertEquals(listOf("Foo void annotated()"), unusedDeclarationsExported(filter))
    }

    fun testUnusedDeclarationLeavesOutAnElementWithNoProblemWhenOnlyAnotherLineOfItIsChanged() {
        val filter = ProblemFilter(onChangedLines = true)

        changeSinceTheLastCommit("@Deprecated", "@Deprecated // before")
        assertEquals(emptyList<String>(), unusedDeclarationsShown(filter))
        changeSinceTheLastCommit("System.out.println(\"annotated\");", "System.out.println(\"before\");")
        assertEquals(emptyList<String>(), unusedDeclarationsShown(filter))
        assertEquals(emptyList<String>(), unusedDeclarationsExported(filter))
    }

    fun testUnusedDeclarationDoesNotShowAnElementWhoseProblemsAreFixedAsOneNothingUses() {
        // A method called, and so not shown as one nothing uses, with a parameter it does not use - one named "ignored"
        // would not be reported.
        myFixture.addFileToProject("Main.java", """
            public class Main {
                public static void main(String[] args) { log(1); }

                private static void log(int level) {}
            }
        """.trimIndent())

        val (before, after) = runInspections(project, InspectionProfileImpl("Built-in"), read = { context ->
            val presentation = context.getPresentation(context.tools.getValue("unused").tools.single().tool)
            val shown = { presentation.updateContent(); presentation.content.values.flatten().map { it.name }.sorted() }
            val before = shown()
            // As a quick fix applied in the results does; they may then be built again, with the problems fixed shown
            // unless the results are to filter them out.
            context.uiOptions.FILTER_RESOLVED_ITEMS = false
            presentation.problemDescriptors.filter { presentation.problemElements.getKeyFor(it)?.name == "log(int)" }
                .forEach(presentation::resolveProblem)
            before to shown()
        }, setUp = { filterProblems(it, RunProblemFilter(ProblemFilter(withQuickFix = true))) })

        assertContainsElements(before, "log(int)")
        assertDoesntContain(after, "log(int)")
    }

    /** Makes Foo.java a file changed since the last commit, where it had [before] in place of [now]. */
    private fun changeSinceTheLastCommit(now: String, before: String) {
        val file = myFixture.findFileInTempDir("Foo.java")
        val psiFile = psiManager.findFile(file)!!
        assertTrue(now in psiFile.text)
        psiFile.putUserData(VcsFacade.TEST_REVISION_CONTENT, psiFile.text.replace(now, before))
        project.putUserData(TEST_CHANGED_FILES, listOf(file))
    }

    /** The elements the results of a run filtered by [filter] show under "Unused declaration". */
    private fun unusedDeclarationsShown(filter: ProblemFilter?): List<String> =
        runInspections(project, InspectionProfileImpl("Built-in"), read = { context ->
            // What the results would show, built for every inspection, as they are - those of the others first, which
            // report in the same elements; a unit test builds no results to show them in.
            val presentations = context.tools.values.flatMap { it.tools }.associate { it.tool.shortName to context.getPresentation(it.tool) }
            (presentations - "unused").values.forEach { it.updateContent() }
            val unused = presentations.getValue("unused").apply { updateContent() }
            unused.content.values.flatten().map { it.name }.sorted()
        }, setUp = { filterProblems(it, filter?.let(::RunProblemFilter)) })

    /**
     * What an export of the results of a run filtered by [filter] holds of "Unused declaration" - see [exportedOf] -
     * and, if [rerun], of the results of the run repeated.
     */
    private fun unusedDeclarationsExported(filter: ProblemFilter?, rerun: Boolean = false): List<String> =
        runInspections(project, InspectionProfileImpl("Built-in"), read = { context ->
            if (rerun) {
                // With the results of the first run built, and what they leave out excluded.
                exportedOf(context)
                runAndWait(context, AnalysisScope(project))
            }
            exportedOf(context)
        }, setUp = { filterProblems(it, filter?.let(::RunProblemFilter)) })

    /**
     * What an export of [context]'s results holds of "Unused declaration": each problem as the element it is about, and
     * the description of the problem, if one was reported. The results are built first, as an export is made from them.
     */
    private fun exportedOf(context: GlobalInspectionContextImpl): List<String> =
        context.tools.getValue("unused").tools.flatMap { state ->
            val presentation = context.getPresentation(state.tool).apply { updateContent() }
            val exported = mutableListOf<Element>()
            // As "Export" in the results calls it.
            presentation.exportResults(exported::add, presentation::isExcluded, presentation::isExcluded)
            exported.map { problem ->
                val element = problem.getChild("entry_point").getAttributeValue("FQNAME")
                // Only a problem reported has a description of its own; an element nothing uses has markup there.
                val description = problem.getChildText("description").takeUnless { it.startsWith("<") }
                listOfNotNull(element, description?.replace(Regex("<[^>]+>"), "")).joinToString(": ")
            }
        }.sorted()

    private fun checkFilteredBy(
        property: InspectionProperty,
        kept: String,
        leftOut: String,
        hasProperty: (InspectionToolWrapper<*, *>) -> Boolean,
    ) {
        // Every inspection registered, as it is by default.
        val source = InspectionProfileImpl("Built-in")
        val unfiltered = runInspections(project, source, ::problemsByInspection)
        assertContainsElements(unfiltered.keys, kept, leftOut)

        val allSeverities = availableSeverities(project).mapTo(HashSet()) { it.name }
        val filtered = runInspections(project, FilteredProfile(source, InspectionFilter(allSeverities, requiredProperties = setOf(property)),
                                                               project), ::problemsByInspection)

        assertEquals(unfiltered.filterKeys { hasProperty(source.getInspectionTool(it, project)!!) }, filtered)
        assertContainsElements(filtered.keys, kept)
        assertDoesntContain(filtered.keys, leftOut)
    }

    /** The problems [context]'s last run reported, as "line: description", and [WITH_FIX] if they come with one, by inspection. */
    private fun problemsByInspection(context: GlobalInspectionContextImpl): Map<String, List<String>> =
        problemsOf(context).groupBy({ (wrapper, _) -> wrapper.shortName }, { (_, problem) -> describe(problem) })
            .mapValues { it.value.sorted() }

    private fun describe(problem: CommonProblemDescriptor): String =
        "${(problem as? ProblemDescriptor)?.let { it.lineNumber + 1 }}: ${problem.descriptionTemplate}" +
        if (problem.fixes.isNullOrEmpty()) "" else WITH_FIX

    private companion object {
        const val WITH_FIX = " [fix]"
    }
}
