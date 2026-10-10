package cz.loplex.dogvision.swing

import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun click(text: WrappedText, x: Int) {
        val y = text.height / 2
        text.dispatchEvent(MouseEvent(text, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
    }
}
