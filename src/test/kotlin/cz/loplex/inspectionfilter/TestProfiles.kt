package cz.loplex.inspectionfilter

import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.BaseInspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProfileManager

/** Supplies [inspections], and nothing else, to the profiles made with it. */
internal fun supplierOf(vararg inspections: InspectionProfileEntry): InspectionToolsSupplier = object : InspectionToolsSupplier() {
    override fun createTools(): List<InspectionToolWrapper<*, *>> = inspections.map {
        when (it) {
            is LocalInspectionTool -> LocalInspectionToolWrapper(it)
            is GlobalInspectionTool -> GlobalInspectionToolWrapper(it)
            else -> error("Neither local nor global: $it")
        }
    }
}

/**
 * A profile of the inspections [supplier] supplies, as they are in [base]. Its tools are created only while
 * [InspectionProfileImpl.INIT_INSPECTIONS] is set.
 */
internal fun newProfile(name: String, supplier: InspectionToolsSupplier, base: InspectionProfileImpl, project: Project): InspectionProfileImpl {
    val profile = InspectionProfileImpl(name, supplier, InspectionProfileManager.getInstance() as BaseInspectionProfileManager,
                                        base, null)
    profile.initInspectionTools(project)
    return profile
}
