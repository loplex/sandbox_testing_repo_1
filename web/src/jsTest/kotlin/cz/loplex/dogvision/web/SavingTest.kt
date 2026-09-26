package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.rgb
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.test.Test
import kotlin.test.assertEquals

/** A snapshot holds the images of the view as the Android app's stitch puts them together. */
class SavingTest {
    private val left = Image(2, 1, intArrayOf(rgb(200, 10, 255), rgb(0, 128, 64)))
    private val right = Image(2, 1, intArrayOf(rgb(1, 2, 3), rgb(250, 251, 252)))

    @Test
    fun putsImagesSideBySide() {
        val canvas = stitch(listOf(left, right), Arrangement.ROW)
        assertEquals(4 to 1, canvas.width to canvas.height)
        assertEquals((left.pixels + right.pixels).toList(), pixels(canvas))
    }

    @Test
    fun putsImagesOneAboveAnother() {
        val canvas = stitch(listOf(left, right), Arrangement.COLUMN)
        assertEquals(2 to 2, canvas.width to canvas.height)
        assertEquals((left.pixels + right.pixels).toList(), pixels(canvas))
    }

    /** The canvas' pixels, the first row first, as opaque ARGB. */
    private fun pixels(canvas: HTMLCanvasElement): List<Int> {
        val context = canvas.getContext("2d").unsafeCast<CanvasRenderingContext2D>()
        val data = context.getImageData(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble()).data
        fun byte(i: Int) = data[i].toInt() and 0xFF
        return List(canvas.width * canvas.height) { rgb(byte(it * 4), byte(it * 4 + 1), byte(it * 4 + 2)) }
    }
}
