package cz.loplex.inspectionfilter

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInspection.deadCode.DeadHTMLComposer
import com.intellij.codeInspection.reference.RefElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction

/**
 * The short name of "Unused declaration", the one inspection that shows elements it has reported no problem in: the
 * classes, methods and fields nothing uses. Its parameters and variables nothing uses are problems like any other.
 */
internal val UNUSED_DECLARATION: String = HighlightInfoType.UNUSED_SYMBOL_SHORT_NAME

/** What the results of "Inspect Code" say of an element "Unused declaration" shows with no problem in it. */
internal interface UnusedDeclarationMessages {

    /** The message, as plain text, for [element]: "Method is never used.", for instance. */
    fun of(element: RefElement): String

    companion object {
        /** None without the Java plugin, which "Unused declaration" comes with. */
        val instance: UnusedDeclarationMessages?
            get() = ApplicationManager.getApplication().getService(UnusedDeclarationMessages::class.java)
    }
}

/** Those of the Java plugin, registered only along with it. */
internal class JavaUnusedDeclarationMessages : UnusedDeclarationMessages {
    override fun of(element: RefElement): String = ReadAction.compute<String, RuntimeException> {
        // As the preview of the element shows it, and as an export describes it.
        val synopsis = StringBuilder().also { DeadHTMLComposer.appendProblemSynopsis(element, it) }.toString()
        // Some are a list of alternatives.
        synopsis.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
