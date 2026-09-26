package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.compose
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.rgb
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversionTest {
    @TempDir
    lateinit var directory: File

    private val photo = Image(16, 12, IntArray(16 * 12) { rgb(it % 16 * 16, it / 16 * 20, 255 - it) })

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = runCommandLine(args.toList(), PrintStream(out, true), PrintStream(err, true)) { 99 }
        return Triple(status, out.toString(), err.toString())
    }

    @Test
    fun aConvertedFileIsNamedAfterItsOriginal() {
        assertEquals(File("dir/photo.dog.png"), convertedFile(File("dir/photo.jpg"), View(), null))
        assertEquals(File("photo.dog.png"), convertedFile(File("photo.jpg"), View(), null))
        assertEquals(File("out/photo.dog.png"), convertedFile(File("dir/photo.jpg"), View(), File("out")))
        assertEquals(File("noext.dog.png"), convertedFile(File("noext"), View(), null))
    }

    @Test
    fun aConvertedFileIsNamedAfterTheSpeciesItShows() {
        val cat = View(Params(Species.CAT))
        assertEquals(File("photo.cat.png"), convertedFile(File("photo.jpg"), cat, null))
        val compared = cat.copy(sideBySide = true, compare = Species.HORSE)
        assertEquals(File("photo.horse-vs-cat.png"), convertedFile(File("photo.jpg"), compared, null))
    }

    @Test
    fun aPhotoIsConvertedAsCoreComposesIt() {
        val input = File(directory, "photo.png").apply { writeBytes(PhotosTest.pngBytes(photo)) }
        val options = arrayOf("--species", "cat", "--compare", "dog", "--difference", "--strength", "0.8")
        val (status, out, err) = run(*options, "$input")
        assertEquals(0 to "", status to err)
        val output = File(directory, "photo.dog-vs-cat.png")
        assertTrue("Wrote $output" in out, out)
        assertTrue(out.lines().first().endsWith("% of pixels differ noticeably"), out)

        val view = View(Params(Species.CAT, strength = 0.8), compare = Species.DOG, difference = true)
        val (width, height) = composedSize(view, photo.width, photo.height)
        val expected = Image(width, height)
        compose(photo, view, expected, { meanLinearRgb(photo) })
        val written = readPhoto(output)!!
        assertEquals(width to height, written.width to written.height)
        assertContentEquals(expected.pixels, written.pixels)
    }

    @Test
    fun aConversionGoesIntoTheOutputFolder() {
        val input = File(directory, "photo.png").apply { writeBytes(PhotosTest.pngBytes(photo)) }
        val folder = File(directory, "new/folder")
        val (status, _, _) = run("--output-dir", "$folder", "$input")
        assertEquals(0, status)
        assertEquals(photo.width, readPhoto(File(folder, "photo.dog.png"))!!.width)
    }

    @Test
    fun whatIsNoPhotoIsReported() {
        val input = File(directory, "clip.mp4").apply { writeText("not a photo") }
        val (status, _, err) = run("$input")
        assertEquals(1, status)
        assertTrue("Cannot read $input as a photo" in err, err)
        val (missing, _, missingErr) = run("${File(directory, "missing.jpg")}")
        assertEquals(1, missing)
        assertTrue("Cannot read" in missingErr, missingErr)
    }

    @Test
    fun theWindowAndTheUsageAreNotConversions() {
        assertEquals(99, run().first)
        assertEquals(99, run("--window", "photo.jpg").first)
        val (help, out, _) = run("--help")
        assertEquals(0, help)
        assertTrue(out.startsWith("usage: dog-vision"), out)
        val (wrong, _, err) = run("--species", "unicorn")
        assertEquals(2, wrong)
        assertTrue("error: --species: no species unicorn" in err, err)
    }
}
