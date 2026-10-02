package cz.loplex.dogvision.swing

import java.awt.Dimension
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a [WrappedText] breaks its pieces into lines, and that a link among them opens when it alone is clicked. */
class WrappedTextTest {
    private val text = WrappedText()

    private fun width(line: String) = text.getFontMetrics(text.font).stringWidth(line)

    @Test
    fun aPieceThatFitsOnALineMovesWholeToTheNext() {
        text.pieces = listOf("35 %", "(Neitz 1989)")
        assertEquals(listOf("35 %", "(Neitz 1989)"), text.lines(width("35 % (Neitz 19")))
    }

    @Test
    fun aPieceWiderThanTheLineBreaksAtItsSpacesOnLinesOfItsOwn() {
        text.pieces = listOf("L 555 nm", "(Neitz, Geist & Jacobs 1989)", "M 0")
        val lines = text.lines(width("(Neitz, Geist &"))
        assertEquals(listOf("L 555 nm", "(Neitz, Geist &", "Jacobs 1989)", "M 0"), lines)
    }

    @Test
    fun aWordWiderThanTheLineBreaksBetweenItsCharacters() {
        val word = "Donaudampfschifffahrtsgesellschaft"
        text.pieces = listOf("A", word, "B")
        val width = width("Donaudampf")
        val lines = text.lines(width)
        assertEquals("A", lines.first())
        assertEquals("B", lines.last())
        assertEquals(word, lines.drop(1).dropLast(1).joinToString(""))
        lines.forEach { assertTrue(width(it) <= width, "\"$it\" is wider than the line") }
    }

    @Test
    fun aLinkBrokenAcrossLinesOpensFromEachPart() {
        val opened = mutableListOf<String>()
        val link = "https://ffmpeg.org/download.html#build-windows"
        val text = WrappedText().apply {
            pieces = "see $link".split(' ')
            this.link = link
            onLink = { opened += it }
        }
        text.size = Dimension(width("https://ffmpeg") + text.insets.left + text.insets.right, 1)
        text.size = Dimension(text.width, text.preferredSize.height)
        val lines = text.lines(width("https://ffmpeg"))
        assertTrue(lines.size >= 3, "the link is not broken: $lines")
        val lineHeight = text.getFontMetrics(text.font).height
        click(text, 2, text.insets.top + lineHeight / 2)
        assertEquals(emptyList(), opened)
        for (line in 1 until lines.size) click(text, 2, text.insets.top + line * lineHeight + lineHeight / 2)
        assertEquals(List(lines.size - 1) { link }, opened)
    }

    @Test
    fun onlyTheLinkOpensWhenClicked() {
        val opened = mutableListOf<String>()
        val link = "https://ffmpeg.org/download.html#build-windows"
        val text = WrappedText().apply {
            pieces = "ffmpeg can be downloaded from $link".split(' ')
            this.link = link
            onLink = { opened += it }
            size = preferredSize
        }
        click(text, 2)
        assertEquals(emptyList(), opened)
        click(text, text.width - 2)
        assertEquals(listOf(link), opened)
    }

    private fun click(text: WrappedText, x: Int, y: Int = text.height / 2) {
        text.dispatchEvent(MouseEvent(text, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
    }
}
