package cz.loplex.dogvision.swing

import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** A link among the pieces of a [WrappedText] opens when it alone is clicked. */
class WrappedTextTest {
    @Test
    fun onlyTheLinkOpensWhenClicked() {
        val opened = mutableListOf<String>()
        val link = "https://ffmpeg.org/download.html#build-windows"
        val text = WrappedText().apply {
            pieces = "ffmpeg can be downloaded from $link".split(' ')
            this.link = link
            onLink = { opened += it }
            setSize(preferredSize)
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
