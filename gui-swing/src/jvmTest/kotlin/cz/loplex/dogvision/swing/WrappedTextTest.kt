package cz.loplex.dogvision.swing

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a [WrappedText] breaks its pieces into lines. */
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
}
