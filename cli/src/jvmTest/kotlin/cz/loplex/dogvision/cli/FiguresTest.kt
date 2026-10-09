package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.red
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The documents' figures are what core renders; ./gradlew :cli:renderFigures writes them again when they are not. */
class FiguresTest {
    private val images = File(checkNotNull(System.getProperty("dogvision.figures")) { "dogvision.figures is not set" })

    @Test
    fun everyFigureIsWhatCoreRenders() {
        val photo = applePhoto(images)
        val stale = FIGURES.filter { (name, render) -> !matches(render(photo), File(images, name)) }.keys
        assertTrue(stale.isEmpty(), "Out of date, run ./gradlew :cli:renderFigures: ${stale.joinToString()}")
    }

    @Test
    fun aFigureThatDiffersIsCaught() {
        val (name, render) = FIGURES.entries.first()
        val figure = render(applePhoto(images))
        val image = figure.images[1].image
        image.pixels[image.width * (image.height / 2) + image.width / 2] = 0xFF00FF00.toInt()
        assertTrue(!matches(figure, File(images, name)), "A changed pixel went unnoticed")
    }

    /**
     * Whether each image of [figure] is in [file] within [TOLERANCE], and not if [file] is missing; the texts are left
     * out, as fonts vary.
     */
    private fun matches(figure: Figure, file: File): Boolean {
        if (!file.isFile) return false
        val stored = assertNotNull(ImageIO.read(file), "No figure in $file")
        assertEquals(figure.width to figure.height, stored.width to stored.height, "Size of $file")
        return figure.images.all { placed ->
            val image = placed.image
            (0 until image.height).all { y ->
                (0 until image.width).all { x ->
                    val a = image[x, y]
                    val b = stored.getRGB(placed.x + x, placed.y + y)
                    abs(red(a) - red(b)) <= TOLERANCE &&
                        abs(green(a) - green(b)) <= TOLERANCE &&
                        abs(blue(a) - blue(b)) <= TOLERANCE
                }
            }
        }
    }

    private companion object {
        /** A change in the model moves pixels much further than a last bit of rounding. */
        const val TOLERANCE = 2
    }
}
