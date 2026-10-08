package cz.loplex.inspectionfilter

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.codeInspection.ex.GlobalInspectionContextEx
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.ex.PairedUnfairLocalInspectionTool
import com.intellij.codeInspection.options.OptPane
import com.intellij.codeInspection.reference.RefElement
import com.intellij.codeInspection.reference.RefEntity
import com.intellij.codeInspection.reference.RefManager
import com.intellij.codeInspection.reference.RefVisitor
import com.intellij.codeInspection.ui.util.SynchronizedBidiMultiMap
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import org.jdom.Element

/**
 * [wrapper], changed so that it reports only the problems [filter] accepts - or [wrapper] itself, when it is not
 * one of the inspections filtered this way.
 *
 * A filter of the whole run (`GlobalInspectionContextEx.setReportedProblemFilter`) could not do it for local
 * inspections. It decides for the problems of one inspection in one element - a file, a class, a method - all at once;
 * and the results of "Inspect Code" show a local inspection's problems whatever it decides, so that a problem it
 * leaves out is shown all the same. A local inspection is therefore wrapped, and its problems filtered one by one as
 * it registers them, before the run sees them.
 *
 * Global inspections are left as they are, and filtered by the run instead: they report through a processor rather
 * than a holder, and the results show only what the run has kept. The same goes for the local inspections that run
 * in batch mode as a global one, which the run finds by the type of the local one's tool: wrapped, it would not.
 *
 * A wrapped inspection is not run at all in a file where [filter] can accept no problem - one with no changed line,
 * when only the problems on those are reported.
 */
internal fun wrapForProblemFilter(wrapper: InspectionToolWrapper<*, *>, filter: RunProblemFilter): InspectionToolWrapper<*, *> {
    if (wrapper !is LocalInspectionToolWrapper) {
        return wrapper
    }
    val tool = wrapper.tool
    if (tool is PairedUnfairLocalInspectionTool && tool !is ExternalAnnotatorBatchInspection) {
        return wrapper
    }
    return FilteringLocalInspectionToolWrapper.of(wrapper, filter)
}

/**
 * Sets [context] up to filter by [filter] the problems that the profile of its run cannot - those of the inspections
 * [wrapForProblemFilter] leaves as they are - or to filter none, when [filter] is null or leaves nothing out. The
 * context also keeps [filter] as [RUN_PROBLEM_FILTER], for each of its runs to find the lines changed for it.
 *
 * The run decides for all the problems an inspection reports in one element - a class, a method, a file - at once, and
 * keeps them all when [filter] accepts any one. Those [filter] does not accept are left out as the results are built
 * from what the run has kept, which an export reads as well.
 *
 * "Unused declaration" also shows the classes, methods and fields nothing uses, with no problem reported in them: what
 * the results say of those is made up as they are shown. [filter] judges them by that, by their name, and as elements
 * with a quick fix - see [RunProblemFilter.acceptsUnusedDeclaration]. They are the elements its results are built from
 * with no problem kept: the results are given the elements nothing uses and then those with a problem the run has kept,
 * and the run keeps the problems of an element only when [filter] accepts one of them, which is still there once the
 * others are left out. An element whose problems are fixed since is not judged so: the results may be built again
 * after a fix.
 *
 * An element left out is left out of an export of the results too, which is why it is excluded in the results as
 * well: an export goes through every element the run knows of, not through what the results show, and leaves out only
 * what is excluded. It also reaches an element through each one that refines to it, as an implicit constructor does to
 * its class, and those are excluded with it.
 */
internal fun filterProblems(context: GlobalInspectionContextEx, filter: RunProblemFilter?) {
    context.putUserData(RUN_PROBLEM_FILTER, filter)
    if (filter == null || !filter.filter.isActive) {
        context.setReportedProblemFilter(null)
        context.setGlobalReportedProblemFilter(null)
        return
    }
    context.setReportedProblemFilter { _, problems -> problems.any(filter::accepts) }
    val refinedFrom = RefinedFrom()
    val withProblems = WithProblems()
    // Asked for every element the results are to show, and so the one place to decide on those with no problem kept.
    context.setGlobalReportedProblemFilter { element, shortName ->
        // An inspection with another severity in a scope has a tool of its own there, which reports on its own.
        val states = context.tools[shortName]?.tools ?: return@setGlobalReportedProblemFilter true
        val presentations = states.map { context.getPresentation(it.tool) }
        // One with a problem the run has kept keeps one [filter] accepts. One with none is shown as an element nothing
        // uses - unless it had problems that are fixed since.
        val hadProblems = withProblems.note(shortName, element, presentations.any { it.problemElements.containsKey(element) },
                                            context.refManager)
        presentations.forEach { keepOnlyAccepted(it.problemElements, element, filter) }
        val kept = presentations.any { it.problemElements.containsKey(element) } ||
                   !hadProblems && shortName == UNUSED_DECLARATION && element is RefElement && filter.acceptsUnusedDeclaration(element)
        if (!kept) {
            // Not shown, so an exclusion nobody can see or undo.
            val excluded = listOf(element) + refinedFrom.of(element, context.refManager)
            presentations.forEach { presentation -> excluded.forEach(presentation::exclude) }
        }
        kept
    }
}

/**
 * Leaves in [problems] only those of [element] that [filter] accepts - after the run has kept them all, when any one
 * passed - or none of them.
 */
private fun keepOnlyAccepted(problems: SynchronizedBidiMultiMap<RefEntity, CommonProblemDescriptor>, element: RefEntity,
                             filter: RunProblemFilter) {
    val all = problems.get(element) ?: return
    val accepted = all.filter(filter::accepts)
    if (accepted.size < all.size) {
        problems.remove(element)
        if (accepted.isNotEmpty()) {
            problems.put(element, *accepted.toTypedArray())
        }
    }
}

/** The elements of a run's graph each inspection has had a problem in, whether or not it is left out or fixed since. */
private class WithProblems {
    private var refManager: RefManager? = null
    private val elements = HashSet<Pair<String, RefEntity>>()

    /**
     * Notes whether [element] has any problem of the inspection [shortName] now, as [hasProblems] tells, in the graph of
     * [refManager], which every run has one of its own; and tells whether it has had any in that run.
     */
    @Synchronized
    fun note(shortName: String, element: RefEntity, hasProblems: Boolean, refManager: RefManager): Boolean {
        if (this.refManager !== refManager) {
            this.refManager = refManager
            elements.clear()
        }
        if (hasProblems) {
            elements += shortName to element
        }
        return shortName to element in elements
    }
}

/** The elements of a run's graph that refine to each element, other than the element itself. */
private class RefinedFrom {
    private var refManager: RefManager? = null
    private var sources: Map<RefEntity, List<RefEntity>> = emptyMap()

    /** Those that refine to [element] in the graph of [refManager], which every run has one of its own. */
    @Synchronized
    fun of(element: RefEntity, refManager: RefManager): List<RefEntity> {
        if (this.refManager !== refManager) {
            val sources = HashMap<RefEntity, MutableList<RefEntity>>()
            refManager.iterate(object : RefVisitor() {
                override fun visitElement(elem: RefEntity) {
                    val refined = refManager.getRefinedElement(elem)
                    if (refined !== elem) {
                        sources.getOrPut(refined) { mutableListOf() } += elem
                    }
                }
            })
            this.refManager = refManager
            this.sources = sources
        }
        return sources[element].orEmpty()
    }
}

/** The wrapper of a filtering tool, which keeps the extension - and so the name, language etc. - of [original]. */
private class FilteringLocalInspectionToolWrapper : LocalInspectionToolWrapper {
    private val original: LocalInspectionToolWrapper
    private val filter: RunProblemFilter

    constructor(original: LocalInspectionToolWrapper, ep: LocalInspectionEP, filter: RunProblemFilter) : super(ep) {
        this.original = original
        this.filter = filter
        myTool = filteringTool(original.tool, filter)
    }

    constructor(original: LocalInspectionToolWrapper, filter: RunProblemFilter) : super(filteringTool(original.tool, filter)) {
        this.original = original
        this.filter = filter
    }

    override fun createCopy(): LocalInspectionToolWrapper = of(original.createCopy(), filter)

    // With no extension, as for Structural Search, a wrapper finds these by the type of its tool - here the filtering
    // one, not the original. Whether a tool is in a dynamic group cannot be passed on: only the platform can ask it.
    override fun isCleanupTool(): Boolean = original.isCleanupTool
    override fun isUnfair(): Boolean = original.isUnfair

    companion object {
        fun of(original: LocalInspectionToolWrapper, filter: RunProblemFilter): LocalInspectionToolWrapper {
            val ep = original.extension
            return if (ep != null) FilteringLocalInspectionToolWrapper(original, ep, filter) else FilteringLocalInspectionToolWrapper(original, filter)
        }

        private fun filteringTool(tool: LocalInspectionTool, filter: RunProblemFilter): LocalInspectionTool =
            if (tool is ExternalAnnotatorBatchInspection) FilteringExternalAnnotatorTool(tool, filter) else FilteringLocalInspectionTool(tool, filter)
    }
}

/**
 * A local inspection that runs [delegate], and passes on only the problems [filter] accepts - in the files where it can
 * accept any.
 */
private open class FilteringLocalInspectionTool(
    val delegate: LocalInspectionTool,
    val filter: RunProblemFilter,
) : LocalInspectionTool() {

    /** Where a session keeps the filtering holder made for the engine's, so that every call gets the same one. */
    private val holderKey = Key.create<Pair<ProblemsHolder, ProblemsHolder>>("filtering holder of ${delegate.shortName}")

    private fun filtering(holder: ProblemsHolder, session: LocalInspectionToolSession?): ProblemsHolder {
        session?.getUserData(holderKey)?.let { (original, filtering) -> if (original === holder) return filtering }
        val filtering = FilteringProblemsHolder(holder, filter)
        session?.putUserData(holderKey, holder to filtering)
        return filtering
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor =
        delegate.buildVisitor(filtering(holder, session), isOnTheFly, session)

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        delegate.buildVisitor(filtering(holder, null), isOnTheFly)

    override fun inspectionStarted(session: LocalInspectionToolSession, isOnTheFly: Boolean) =
        delegate.inspectionStarted(session, isOnTheFly)

    override fun inspectionFinished(session: LocalInspectionToolSession, problemsHolder: ProblemsHolder) =
        delegate.inspectionFinished(session, filtering(problemsHolder, session))

    // All else is the delegate's.
    override fun getShortName(): String = delegate.shortName
    override fun getDisplayName(): String = delegate.displayName
    override fun getGroupDisplayName(): String = delegate.groupDisplayName
    override fun getGroupPath(): Array<String> = delegate.groupPath
    override fun getGroupKey(): String? = delegate.groupKey
    override fun getID(): String = delegate.id
    override fun getAlternativeID(): String? = delegate.alternativeID
    override fun runForWholeFile(): Boolean = delegate.runForWholeFile()
    // Asked by the run before it builds a visitor.
    override fun isAvailableForFile(file: PsiFile): Boolean = filter.mayAcceptIn(file) && delegate.isAvailableForFile(file)
    override fun getProblemElement(psiElement: PsiElement): PsiNamedElement? = delegate.getProblemElement(psiElement)
    override fun isSuppressedFor(element: PsiElement): Boolean = delegate.isSuppressedFor(element)
    override fun getBatchSuppressActions(element: PsiElement?): Array<SuppressQuickFix> = delegate.getBatchSuppressActions(element)
    override fun getLanguage(): String? = delegate.language
    override fun getDefaultLevel(): HighlightDisplayLevel = delegate.defaultLevel
    override fun isEnabledByDefault(): Boolean = delegate.isEnabledByDefault
    override fun initialize(context: GlobalInspectionContext) = delegate.initialize(context)
    override fun cleanup(project: Project) = delegate.cleanup(project)
    override fun readSettings(node: Element) = delegate.readSettings(node)
    override fun writeSettings(node: Element) = delegate.writeSettings(node)
    override fun getMainToolId(): String? = delegate.mainToolId
    override fun getStaticDescription(): String? = delegate.staticDescription
    override fun getDescriptionFileName(): String? = delegate.descriptionFileName
    override fun loadDescription(): String? = delegate.loadDescription()
    override fun getDescriptionAddendum(): HtmlChunk = delegate.descriptionAddendum
    override fun getEditorAttributesKey(): String? = delegate.editorAttributesKey
    override fun getOptionsPane(): OptPane = delegate.optionsPane
}

/**
 * A filtering tool for an external one, such as ShellCheck: the run finds those by the type of their tool, and runs
 * them with [checkFile] rather than a visitor.
 */
private class FilteringExternalAnnotatorTool(delegate: LocalInspectionTool, filter: RunProblemFilter) :
    FilteringLocalInspectionTool(delegate, filter), ExternalAnnotatorBatchInspection {

    private val external: ExternalAnnotatorBatchInspection get() = delegate as ExternalAnnotatorBatchInspection

    override fun getInspectionForBatchShortName(): String = external.inspectionForBatchShortName

    override fun checkFile(file: PsiFile, context: GlobalInspectionContext, manager: InspectionManager): Array<ProblemDescriptor> =
        // Not asked whether it is available for the file first, unlike the others.
        if (!filter.mayAcceptIn(file)) ProblemDescriptor.EMPTY_ARRAY
        else external.checkFile(file, context, manager).filter(filter::accepts).toTypedArray()
}

/** Registers in [target] only the problems [filter] accepts. */
private class FilteringProblemsHolder(
    private val target: ProblemsHolder,
    private val filter: RunProblemFilter,
) : ProblemsHolder(target.manager, target.file, target.isOnTheFly) {
    override fun registerProblem(problemDescriptor: ProblemDescriptor) {
        if (filter.accepts(problemDescriptor)) {
            target.registerProblem(problemDescriptor)
        }
    }
}
