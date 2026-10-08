package cz.loplex.inspectionfilter

import com.intellij.codeInspection.CleanupLocalInspectionTool
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.UnfairLocalInspectionTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A wrapped inspection with no extension - as Structural Search and custom RegExp ones are - is what it wraps. */
class ProblemFilteringToolsTest : BasePlatformTestCase() {

    private val filter = RunProblemFilter(ProblemFilter(withQuickFix = true))

    fun testAWrappedInspectionIsACleanupAndUnfairOneAsTheOriginalIs() {
        val wrapped = wrapForProblemFilter(LocalInspectionToolWrapper(Marked()), filter) as LocalInspectionToolWrapper

        assertNotInstanceOf<Marked>(wrapped.tool)
        assertTrue(wrapped.isCleanupTool)
        assertTrue(wrapped.isUnfair)
        val copy = wrapped.createCopy()
        assertTrue(copy.isCleanupTool)
        assertTrue(copy.isUnfair)
    }

    fun testAWrappedInspectionIsNeitherWhenTheOriginalIsNot() {
        val wrapped = wrapForProblemFilter(LocalInspectionToolWrapper(Plain()), filter) as LocalInspectionToolWrapper

        assertFalse(wrapped.isCleanupTool)
        assertFalse(wrapped.isUnfair)
    }

    private class Marked : LocalInspectionTool(), CleanupLocalInspectionTool, UnfairLocalInspectionTool {
        override fun getShortName() = "Marked"
    }

    private class Plain : LocalInspectionTool() {
        override fun getShortName() = "Plain"
    }

    private inline fun <reified T> assertNotInstanceOf(value: Any) =
        assertFalse("${value.javaClass.name} is a ${T::class.java.name}", value is T)
}
