package cz.loplex.dogvision.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The GPU renders what core's CPU pipeline renders, within [TOLERANCE] of 8 bits, in an offscreen
 * OpenGL ES 3.0 context of this device's GPU.
 */
@RunWith(AndroidJUnit4::class)
class ViewRendererTest {
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var surface: EGLSurface
    private val frames = FrameExchange()
    private var drawn: Drawn? = null
    private val renderer = ViewRenderer(frames) { drawn = it }

    @Before
    fun makeContext() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, IntArray(1), 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 256, EGL14.EGL_HEIGHT, 256, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 256, 256)
    }

    @After
    fun releaseContext() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    /** Neighbouring pixels differ in every channel, as in core's reference pattern. */
    private fun pattern(width: Int, height: Int) = Image(width, height, IntArray(width * height) {
        val x = it % width
        val y = it / width
        rgb((x * 37 + y * 11) % 256, (x * 13 + y * 71) % 256, (x * 101 + y * 29) % 256)
    })

    private fun publish(image: Image, rotation: Int = 0, mirrored: Boolean = false): Frame {
        val frame = Frame.allocate(image.width, image.height)
        for (pixel in image.pixels) {
            frame.pixels.put(red(pixel).toByte()).put(green(pixel).toByte()).put(blue(pixel).toByte()).put(-1)
        }
        frame.rotation = rotation
        frame.mirrored = mirrored
        frames.publish(frame)
        return frame
    }

    /** What core renders of [image] for [view], cut into its images. */
    private fun expected(image: Image, view: View): Pair<List<Image>, Double?> {
        val row = view.copy(arrangement = Arrangement.ROW)
        val (width, height) = composedSize(row, image.width, image.height)
        val composed = Image(width, height)
        val share = compose(image, row, composed, { meanLinearRgb(image) })
        val images = List(view.images) { i ->
            Image(image.width, image.height, IntArray(image.width * image.height) {
                composed[i * image.width + it % image.width, it / image.width]
            })
        }
        return images to share
    }

    private fun assertClose(expected: Image, actual: Image, what: String) {
        assertEquals("$what: width", expected.width, actual.width)
        assertEquals("$what: height", expected.height, actual.height)
        var worst = 0
        for (i in expected.pixels.indices) {
            for (channel in listOf(::red, ::green, ::blue)) {
                worst = maxOf(worst, abs(channel(expected.pixels[i]) - channel(actual.pixels[i])))
            }
        }
        assertTrue("$what: a channel differs by $worst", worst <= TOLERANCE)
    }

    /**
     * The GPU's images are core's, within [TOLERANCE]. The map of differences is held to the map core
     * draws from the GPU's own two images: a pixel near the threshold of one just-noticeable
     * difference may cross it for images a step apart, and then turns from grey to red.
     */
    private fun check(view: View, image: Image = pattern(96, 64)) {
        publish(image)
        renderer.view = view
        renderer.onDrawFrame(null)
        val (expected, share) = expected(image, view)
        val actual = renderer.readImages()
        assertEquals(expected.size, actual.size)
        expected.zip(actual).take(2).forEachIndexed { i, (e, a) -> assertClose(e, a, "$view image $i") }
        if (share == null) {
            assertNull(drawn!!.differenceShare)
            return
        }
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
        assertEquals(noticeable.toDouble() / (map.width * map.height), drawn!!.differenceShare!!, 1e-9)
        assertEquals(share, drawn!!.differenceShare!!, 0.02)
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
    fun aTrichromatOnTheRnlScale() = check(View(Params(Species.MACAQUE, chromaScale = ChromaScale.RNL), sideBySide = false))

    @Test
    fun theAcuityBlur() = check(View(Params(acuity = true, fieldOfView = 2.0), sideBySide = false))

    @Test
    fun theCattleBlurDiffersByDirection() = check(View(Params(Species.COW, acuity = true, fieldOfView = 4.0), sideBySide = false))

    @Test
    fun theMapOfDifferences() = check(View(compare = Species.DEUTERANOPE, difference = true))

    @Test
    fun theMapOfDifferencesWithBlur() = check(View(Params(acuity = true, fieldOfView = 3.0), difference = true))

    @Test
    fun aFrameIsTurnedUprightAndMirrored() {
        val image = pattern(40, 24)
        for (rotation in listOf(0, 90, 180, 270)) {
            for (mirrored in listOf(false, true)) {
                val frame = publish(image, rotation, mirrored)
                renderer.view = View(Params(strength = 0.0), sideBySide = false)
                renderer.onDrawFrame(null)
                val upright = Image(frame.uprightWidth, frame.uprightHeight, IntArray(frame.uprightWidth * frame.uprightHeight) {
                    frame.uprightPixel(it % frame.uprightWidth, it / frame.uprightWidth)
                })
                assertClose(upright, renderer.readImages().single(), "rotation $rotation, mirrored $mirrored")
            }
        }
    }

    @Test
    fun aQuarterTurnClockwisePutsTheLeftOnTop() {
        val a = rgb(200, 10, 10)
        val b = rgb(10, 10, 200)
        publish(Image(2, 1, intArrayOf(a, b)), rotation = 90)
        renderer.view = View(Params(strength = 0.0), sideBySide = false)
        renderer.onDrawFrame(null)
        assertEquals(listOf(a, b), renderer.readImages().single().pixels.toList())
        publish(Image(2, 1, intArrayOf(a, b)), rotation = 0, mirrored = true)
        renderer.onDrawFrame(null)
        assertEquals(listOf(b, a), renderer.readImages().single().pixels.toList())
    }

    private companion object {
        /** One step for the rounding of a float; one more where a blur keeps its half-way result in 8 bits. */
        const val TOLERANCE = 2
    }
}
