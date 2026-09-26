package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.blue
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.differenceRow
import cz.loplex.dogvision.core.green
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.red
import cz.loplex.dogvision.core.rgb
import kotlinx.browser.document
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebGL 2 renders what core's CPU pipeline renders, within [TOLERANCE] of 8 bits, as the Android app's ViewRendererTest
 * holds its OpenGL ES renderer to it.
 */
class PassesTest {
    /** Neighbouring pixels differ in every channel, as in core's reference pattern. */
    private fun pattern(width: Int, height: Int) = Image(
        width,
        height,
        IntArray(width * height) {
            val x = it % width
            val y = it / width
            rgb((x * 37 + y * 11) % 256, (x * 13 + y * 71) % 256, (x * 101 + y * 29) % 256)
        },
    )

    private fun upload(image: Image) {
        val pixels = Uint8Array(image.width * image.height * 4)
        image.pixels.forEachIndexed { i, pixel ->
            pixels[i * 4] = red(pixel).toByte()
            pixels[i * 4 + 1] = green(pixel).toByte()
            pixels[i * 4 + 2] = blue(pixel).toByte()
            pixels[i * 4 + 3] = -1
        }
        passes.upload(image.width, image.height, pixels)
    }

    /** What core renders of [image] for [view], cut into its images. */
    private fun expected(image: Image, view: View): Pair<List<Image>, Double?> {
        val row = view.copy(arrangement = Arrangement.ROW)
        val (width, height) = composedSize(row, image.width, image.height)
        val composed = Image(width, height)
        val share = compose(image, row, composed, { meanLinearRgb(image) })
        val images = List(view.images) { i ->
            Image(
                image.width,
                image.height,
                IntArray(image.width * image.height) {
                    composed[i * image.width + it % image.width, it / image.width]
                },
            )
        }
        return images to share
    }

    private fun assertClose(expected: Image, actual: Image, what: String) {
        assertEquals(expected.width, actual.width, "$what: width")
        assertEquals(expected.height, actual.height, "$what: height")
        var worst = 0
        for (i in expected.pixels.indices) {
            for (channel in listOf(::red, ::green, ::blue)) {
                worst = maxOf(worst, abs(channel(expected.pixels[i]) - channel(actual.pixels[i])))
            }
        }
        assertTrue(worst <= TOLERANCE, "$what: a channel differs by $worst")
    }

    /**
     * The images WebGL renders are core's, within [TOLERANCE]. The map of differences is held to the map core draws
     * from WebGL's own two images: a pixel near the threshold of one just-noticeable difference may cross it for images
     * a step apart, and then turns from grey to red.
     */
    private fun check(view: View, image: Image = pattern(96, 64)) {
        upload(image)
        val actualShare = passes.compose(view)
        val (expected, share) = expected(image, view)
        val actual = passes.readImages(view.images)
        assertEquals(expected.size, actual.size)
        expected.zip(actual).take(2).forEachIndexed { i, (e, a) -> assertClose(e, a, "$view image $i") }
        if (share == null) {
            assertNull(actualShare)
            return
        }
        assertNotNull(actualShare)
        val (left, right, map) = actual
        val expectedMap = Image(map.width, map.height)
        var noticeable = 0
        val a = IntArray(map.width)
        val b = IntArray(map.width)
        val row = IntArray(map.width)
        for (y in 0 until map.height) {
            left.readRow(y, a)
            right.readRow(y, b)
            noticeable += differenceRow(a, b, row)
            expectedMap.writeRow(y, 0, row, 0, row.size)
        }
        assertClose(expectedMap, map, "$view map")
        assertEquals(noticeable.toDouble() / (map.width * map.height), actualShare, 1e-9)
        assertEquals(share, actualShare, 0.02)
    }

    @Test
    fun theSimulationAlone() = check(View(sideBySide = false))

    @Test
    fun theOriginalBesideTheSimulation() = check(View(Params(Species.HORSE)))

    @Test
    fun anotherSpeciesOnTheLeft() = check(View(Params(Species.DOG), compare = Species.CAT))

    @Test
    fun rnlAdaptationAndStrength() =
        check(View(Params(adaptation = 0.5, strength = 0.75, chromaScale = ChromaScale.RNL), sideBySide = false))

    @Test
    fun aMonochromat() = check(View(Params(Species.HARBOUR_SEAL), sideBySide = false))

    @Test
    fun aTrichromatOnTheRnlScale() =
        check(View(Params(Species.MACAQUE, chromaScale = ChromaScale.RNL), sideBySide = false))

    @Test
    fun theAcuityBlur() = check(View(Params(acuity = true, fieldOfView = 2.0), sideBySide = false))

    @Test
    fun theCattleBlurDiffersByDirection() =
        check(View(Params(Species.COW, acuity = true, fieldOfView = 4.0), sideBySide = false))

    @Test
    fun theMapOfDifferences() = check(View(compare = Species.DEUTERANOPE, difference = true))

    @Test
    fun theMapOfDifferencesWithBlur() = check(View(Params(acuity = true, fieldOfView = 3.0), difference = true))

    @Test
    fun aPhotoOfAnotherSizeReplacesTheFirst() {
        check(View(sideBySide = false), pattern(40, 24))
        check(View(sideBySide = false), pattern(24, 40))
    }

    private companion object {
        /** One step for the rounding of a float; one more where a blur keeps its half-way result in 8 bits. */
        const val TOLERANCE = 2

        /** One context for every test: a browser keeps only a few at a time, and loses the oldest. */
        val passes by lazy {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            Passes(checkNotNull(canvas.getContext("webgl2")) { "No WebGL 2" }.unsafeCast<WebGL2RenderingContext>())
        }
    }
}
