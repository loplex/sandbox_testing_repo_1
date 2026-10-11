package cz.loplex.dogvision.layout

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/** Where a file holds its prose: in Markdown, in comments of one of these shapes, or in troff's text lines. */
enum class Kind { MARKDOWN, KOTLIN, C_LIKE, HASH, PROPERTIES, POWERSHELL, XML, HTML, TROFF }

/**
 * The kind of a file by its path from the repository's root, or null for a file that holds no prose of the project's:
 * `gradlew`, which Gradle writes, has no extension listed, nor have the licences' texts and the test data,
 * nor the module graphs' SVG, which Graphviz draws.
 */
fun kindOf(path: String): Kind? {
    val name = path.substringAfterLast('/')
    return when {
        path == "packaging/web/dog-vision-web" -> Kind.HASH

        name in setOf(".editorconfig", ".gitignore", ".gitattributes") -> Kind.HASH

        else -> when (name.substringAfterLast('.', "")) {
            "md" -> Kind.MARKDOWN
            "kt", "kts" -> Kind.KOTLIN
            "java", "c", "h", "js", "ts", "css", "dot" -> Kind.C_LIKE
            "sh", "yml", "yaml", "toml", "py" -> Kind.HASH
            "properties" -> Kind.PROPERTIES
            "ps1" -> Kind.POWERSHELL
            "xml", "wxs", "wxl" -> Kind.XML
            "html" -> Kind.HTML
            "1" -> Kind.TROFF
            else -> null
        }
    }
}

/** A line that breaks the layout, numbered from 1. */
data class Problem(val line: Int, val message: String)

/**
 * The lines of [text], a file of [kind], that break the layout docs/developing.md describes:
 * a line of prose longer than [limit] that could have been broken,
 * and in Markdown and KDoc a `\` that ends a paragraph, or a block that starts under a paragraph's line.
 */
fun layoutProblems(kind: Kind, text: String, limit: Int): List<Problem> {
    val lines = text.lines()
    val rendered = when (kind) {
        Kind.MARKDOWN -> markdownProblems(lines, 0)
        Kind.KOTLIN -> docComments(lines).flatMap { (first, comment) -> markdownProblems(comment, first) }
        else -> emptyList()
    }
    return (tooLong(kind, lines, limit) + rendered).sortedBy { it.line }
}

private val fence = Regex("""^\s*(```|~~~)""")

// Markdown's lines are measured without a link's target or a bare URL, which no break can shorten.
private val target = Regex("""(?<=\]\()[^)]*(?=\))|https?://\S+""")

// What a line starts with that is no word of its prose: indentation, a comment's marker, a quote's, a list item's;
// and what it ends with: a comment's end, a hard break.
private val prefix = Regex("""^\s*(?:(?://+|/\*+|\*+|#+|<!--|\.\\"|>|[-+]|\d+[.)])\s*)*""")
private val suffix = Regex("""\s*\\?\s*(?:\*/|-->)?\s*$""")

// What cannot be broken: a link or an image with its label, inline or by reference, inline code, a URL.
private val unbreakable = Regex("""!?\[[^\]]*](?:\([^)]*\)|\[[^\]]*])?|(`+).+?\1|<https?://[^>]*>|https?://\S+""")
private val onlyOne = Regex("""[^\p{L}\p{N}\u0000]*\u0000[^\p{L}\p{N}\u0000]*""")

/** Whether a line's prose is one thing that cannot be broken, with nothing but punctuation around it. */
private fun cannotBreak(line: String): Boolean =
    onlyOne.matches(line.replace(prefix, "").replace(suffix, "").replace(unbreakable, "\u0000"))

private fun tooLong(kind: Kind, lines: List<String>, limit: Int): List<Problem> =
    proseLines(kind, lines).mapNotNull { i ->
        val line = lines[i]
        val counted = if (kind == Kind.MARKDOWN) line.replace(target, "") else line
        val width = counted.codePointCount(0, counted.length)
        when {
            width <= limit || cannotBreak(line) -> null
            kind == Kind.MARKDOWN -> Problem(i + 1, "$width > $limit characters but links' targets")
            else -> Problem(i + 1, "$width > $limit characters")
        }
    }

/**
 * The indices of the lines that are prose: Kotlin's every line, as ktlint leaves one that is a comment alone;
 * Markdown's but code blocks and tables;
 * elsewhere, the lines that start in a comment, and troff's comments and text lines, which are not requests to it.
 */
private fun proseLines(kind: Kind, lines: List<String>): List<Int> {
    if (kind == Kind.MARKDOWN) return markdownProse(lines)
    val text = lines.joinToString("\n")
    val comments = comments(kind, text)
    val starts = lines.runningFold(0) { start, line -> start + line.length + 1 }
    return lines.indices.filter { i ->
        val trimmed = lines[i].trim()
        val first = starts[i] + lines[i].indexOfFirst { !it.isWhitespace() }
        val inComment = trimmed.isNotEmpty() && comments.any { first in it }
        when (kind) {
            Kind.KOTLIN -> true
            Kind.HASH -> trimmed.startsWith("#")
            Kind.PROPERTIES -> trimmed.startsWith("#") || trimmed.startsWith("!")
            Kind.POWERSHELL -> trimmed.startsWith("#") || inComment
            Kind.TROFF -> trimmed.startsWith(".\\\"") || !(trimmed.startsWith(".") || trimmed.startsWith("'"))
            Kind.MARKDOWN, Kind.C_LIKE, Kind.XML, Kind.HTML -> inComment
        }
    }
}

private val markdownCode =
    setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, GFMElementTypes.TABLE)

/** The indices of Markdown's [lines] but those of its code blocks and tables, as the parser finds them. */
private fun markdownProse(lines: List<String>): List<Int> {
    val markdown: CharSequence = lines.joinToString("\n")
    val starts = lines.runningFold(0) { start, line -> start + line.length + 1 }
    fun lineOf(offset: Int) = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
    val code = mutableSetOf<Int>()
    fun visit(node: ASTNode) {
        if (node.type in markdownCode) {
            code += lineOf(node.startOffset)..lineOf(maxOf(node.startOffset, node.endOffset - 1))
        } else {
            node.children.forEach(::visit)
        }
    }
    visit(parser.buildMarkdownTreeFromString(markdown))
    return lines.indices.filter { it !in code }
}

private val xmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private val powerShellComment = Regex("""<#.*?#>""", RegexOption.DOT_MATCHES_ALL)
private val pageCode =
    Regex("""<(script|style)\b[^>]*>(.*?)</\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

/**
 * Where [text], a file of [kind], has its comments:
 * C's in a C-like file and in an HTML page's script and style, XML's elsewhere in a page and in XML,
 * PowerShell's block comments; a line comment of `#` is found by its line alone.
 */
private fun comments(kind: Kind, text: String): List<IntRange> = when (kind) {
    Kind.C_LIKE -> cComments(text, 0, text.length)

    Kind.XML -> xmlComment.findAll(text).map { it.range }.toList()

    Kind.POWERSHELL -> powerShellComment.findAll(text).map { it.range }.toList()

    Kind.HTML -> {
        val code = pageCode.findAll(text).map { checkNotNull(it.groups[2]).range }.toList()
        val markup = xmlComment.findAll(text).map { it.range }.filter { comment -> code.none { comment.first in it } }
        markup.toList() + code.flatMap { cComments(text, it.first, it.last + 1) }
    }

    else -> emptyList()
}

/**
 * The comments of C's kind, `//` and `/* */`, in [text] from [from] until [to],
 * past the strings in `"` and `'`, taken to end with their line, and in `` ` ``, which may run on.\
 * JavaScript's regular expressions are read as code, so one that holds a block comment's opening would open one.
 */
private fun cComments(text: String, from: Int, to: Int): List<IntRange> {
    val comments = mutableListOf<IntRange>()
    var i = from
    while (i < to) {
        i = when {
            text.startsWith("//", i) -> {
                val end = text.indexOf('\n', i).let { if (it < 0 || it > to) to else it }
                comments += i until end
                end
            }

            text.startsWith("/*", i) -> {
                val end = text.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > to) to else it + 2 }
                comments += i until end
                end
            }

            text[i] in "\"'`" -> stringEnd(text, i, to)

            else -> i + 1
        }
    }
    return comments
}

/**
 * Where the string that opens at [start] ends, past its escapes:
 * at its closing quote, or at its line's end but in `` ` ``.
 */
private fun stringEnd(text: String, start: Int, to: Int): Int {
    val quote = text[start]
    var i = start + 1
    while (i < to) {
        when (text[i]) {
            '\\' -> i++
            quote -> return i + 1
            '\n' -> if (quote != '`') return i
        }
        i++
    }
    return to
}

private val docLine = Regex("""^\s*\*(?!/)\s?""")

/**
 * The KDoc of a Kotlin file, each comment as the index of its first line and the Markdown of its lines, one to a line:
 * a comment is taken to start where a line starts with its opening marker,
 * as no string of the repository's starts a line so.\
 * A comment's block tags, from `@param` on, are paragraphs of their own, so each is given apart;
 * an `@` in a code block is no tag.
 */
private fun docComments(lines: List<String>): List<Pair<Int, List<String>>> {
    val comments = mutableListOf<Pair<Int, List<String>>>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].trimStart().startsWith("/**")) {
            i++
            continue
        }
        val first = i
        val body = mutableListOf<String>()
        while (i < lines.size) {
            val text = if (i == first) {
                lines[i].trimStart().removePrefix("/**").removePrefix(" ")
            } else {
                lines[i].replace(docLine, "")
            }
            val end = text.indexOf("*/")
            body += if (end >= 0) text.substring(0, end) else text
            i++
            if (end >= 0) break
        }
        var start = 0
        var inCode = false
        for (j in 1..body.size) {
            if (j < body.size && fence.containsMatchIn(body[j])) inCode = !inCode
            if (j == body.size || (!inCode && body[j].trimStart().startsWith("@"))) {
                comments += first + start to body.subList(start, j)
                start = j
            }
        }
    }
    return comments
}

private val parser = MarkdownParser(GFMFlavourDescriptor(), true, CancellationToken.NonCancellable)

// The blocks that end a paragraph when they start on the line under it,
// which the repository's text writes after a blank line, so that one there is a line of prose that starts with markup.
// A list of `-` is no such block: a list follows its lead-in so.
// A table is none either, as the parser lets no table end a paragraph.
private val interrupting: Map<IElementType, String> = mapOf(
    MarkdownElementTypes.ATX_1 to "a heading",
    MarkdownElementTypes.ATX_2 to "a heading",
    MarkdownElementTypes.ATX_3 to "a heading",
    MarkdownElementTypes.ATX_4 to "a heading",
    MarkdownElementTypes.ATX_5 to "a heading",
    MarkdownElementTypes.ATX_6 to "a heading",
    MarkdownElementTypes.BLOCK_QUOTE to "a quote",
    MarkdownElementTypes.HTML_BLOCK to "an HTML block",
    MarkdownElementTypes.CODE_FENCE to "a code block",
    MarkdownElementTypes.ORDERED_LIST to "a numbered list",
    MarkdownElementTypes.UNORDERED_LIST to "a list of `+` or `*`",
    MarkdownTokenTypes.HORIZONTAL_RULE to "a rule",
)

/** The problems [lines] of Markdown have once rendered, numbered as the file's line [first] is the first of them. */
private fun markdownProblems(lines: List<String>, first: Int): List<Problem> {
    val markdown: CharSequence = lines.joinToString("\n")
    val starts = lines.runningFold(0) { start, line -> start + line.length + 1 }
    fun lineOf(offset: Int) = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 } + first + 1
    val problems = mutableListOf<Problem>()
    fun visit(node: ASTNode) {
        val text = markdown.substring(node.startOffset, node.endOffset)
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> {
                val body = text.trimEnd()
                if (body.takeLastWhile { it == '\\' }.length % 2 == 1) {
                    problems += Problem(
                        lineOf(node.startOffset + body.length - 1),
                        "a `\\` ends the paragraph, where it renders as itself",
                    )
                }
            }

            MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2 -> problems += Problem(
                lineOf(node.endOffset - 1),
                "a line of `=` or `-` makes the paragraph above it a heading",
            )
        }
        // The parser has a paragraph and a rule for tokens, which the other tokens, a line's end and a marker, are not.
        val blocks = node.children.filter { it.type in blockTokens || !isToken(it.type) }
        blocks.zipWithNext { above, block ->
            val name = interrupting[block.type]
            val dash = block.type == MarkdownElementTypes.UNORDERED_LIST &&
                markdown.substring(block.startOffset, block.endOffset).trimStart().startsWith("-")
            val paragraph = lastParagraph(above)
            if (paragraph != null && name != null && !dash &&
                lineOf(block.startOffset) == lineOf(paragraph.endOffset - 1) + 1
            ) {
                problems += Problem(lineOf(block.startOffset), "$name starts under a paragraph's line, and ends it")
            }
        }
        node.children.forEach(::visit)
    }
    visit(parser.buildMarkdownTreeFromString(markdown))
    return problems
}

/**
 * The paragraph a block ends with, where a line under it could have been one of its own:
 * the block itself, or the last of a list or a quote, which takes a line that is not indented as its paragraph's.
 */
private fun lastParagraph(block: ASTNode): ASTNode? = when (block.type) {
    MarkdownElementTypes.PARAGRAPH -> block

    MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.LIST_ITEM,
    MarkdownElementTypes.BLOCK_QUOTE,
    -> block.children.lastOrNull { it.type in blockTokens || !isToken(it.type) }?.let(::lastParagraph)

    else -> null
}

private val blockTokens = setOf(MarkdownElementTypes.PARAGRAPH, MarkdownTokenTypes.HORIZONTAL_RULE)

private fun isToken(type: IElementType) = (type as? MarkdownElementType)?.isToken ?: true
