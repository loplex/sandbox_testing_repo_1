package cz.loplex.dogvision.cli

import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import java.io.File

/** The command line's options, as the Python program's `dog-vision` takes them. */
data class Arguments(
    /** The photo or video to convert, or to show if [window]. */
    val file: File? = null,
    /** Show [file] in the window instead of converting it. */
    val window: Boolean = false,
    /** The camera the window shows when no file is given: /dev/video followed by it. */
    val camera: Int = 0,
    val params: Params = Params(),
    val compare: Species? = null,
    val difference: Boolean = false,
    /** Where a converted file goes, in place of next to its original. */
    val outputDir: File? = null,
    val help: Boolean = false,
) {
    /** Whether this asks for [file] to be converted, rather than for the window. */
    val converts: Boolean get() = file != null && !window && !help

    /** The view a file is converted to: the simulation alone unless another species or the map is asked for. */
    val conversionView: View
        get() = View(params, sideBySide = compare != null || difference, compare = compare, difference = difference)

    /** The view the window starts with: side by side, as the Python window starts. */
    val windowView: View get() = View(params, sideBySide = true, compare = compare, difference = difference)
}

/** A command line that cannot be read, with what is wrong with it. */
class UsageException(message: String) : Exception(message)

/**
 * Reads [args] as `dog-vision [options] [file]` does: an option's value follows it or an equals sign. Throws
 * UsageException for anything it does not know.
 */
fun parseArguments(args: List<String>): Arguments {
    var arguments = Arguments()
    var params = Params()
    val remaining = ArrayDeque(args)
    while (remaining.isNotEmpty()) {
        val arg = remaining.removeFirst()
        if (!arg.startsWith("-") || arg == "-") {
            if (arguments.file != null) throw UsageException("Only one file can be given, not also $arg")
            arguments = arguments.copy(file = File(arg))
            continue
        }
        val name = arg.substringBefore('=')
        val inline = if ('=' in arg) arg.substringAfter('=') else null

        fun value(): String = inline ?: remaining.removeFirstOrNull() ?: throw UsageException("$name needs a value")

        fun flag() {
            if (inline != null) throw UsageException("$name takes no value")
        }
        when (name) {
            "-h", "--help" -> flag().also { arguments = arguments.copy(help = true) }
            "--window" -> flag().also { arguments = arguments.copy(window = true) }
            "--difference" -> flag().also { arguments = arguments.copy(difference = true) }
            "--acuity" -> flag().also { params = params.copy(acuity = true) }
            "--camera" -> arguments = arguments.copy(camera = camera(value()))
            "--output-dir" -> arguments = arguments.copy(outputDir = File(value()))
            "--species" -> params = params.copy(species = species(name, value()))
            "--compare" -> arguments = arguments.copy(compare = species(name, value()))
            "--adaptation" -> params = params.copy(adaptation = share(name, value()))
            "--strength" -> params = params.copy(strength = share(name, value()))
            "--chroma-scale" -> params = params.copy(chromaScale = chromaScale(value()))
            "--fov" -> params = params.copy(fieldOfView = fieldOfView(value()))
            else -> throw UsageException("Unknown option $name")
        }
    }
    return arguments.copy(params = params)
}

/** How to call it, for --help and after a mistake. */
val USAGE = """
    |usage: dog-vision [options] [file]
    |
    |Simulates how an animal sees colour, on a live camera or a photo.
    |
    |  dog-vision                      the live camera in the window
    |  dog-vision photo.jpg            converts a photo, writes photo.dog.png
    |  dog-vision --window clip.mp4    shows a photo or a video in the window
    |
    |options:
    |  --species ID       the animal to simulate (default ${Params().species.id})
    |  --compare ID       shows this species beside --species instead of the original
    |  --difference       adds a map of where the two images differ noticeably
    |  --adaptation X     adaptation to the scene's mean, 0-1 (default ${Params().adaptation})
    |  --strength X       0 = the original, 1 = the full simulation (default ${Params().strength})
    |  --chroma-scale S   fixed or rnl (default ${Params().chromaScale.name.lowercase()})
    |  --acuity           blurs to the species' visual acuity
    |  --fov DEGREES      degrees the image spans across, for --acuity (default ${Params().fieldOfView})
    |  --window           shows the file in the window instead of converting it
    |  --camera N         the camera the window shows, /dev/videoN (default 0)
    |  --output-dir DIR   writes a converted file there instead of next to its original
    |  -h, --help         shows this and exits
    |
    |species: ${Species.entries.joinToString(" ") { it.id }}
""".trimMargin()

private fun species(option: String, id: String): Species =
    Species.entries.firstOrNull { it.id == id } ?: throw UsageException("$option: no species $id")

private fun chromaScale(value: String): ChromaScale = ChromaScale.entries.firstOrNull { it.name.lowercase() == value }
    ?: throw UsageException("--chroma-scale: $value is neither fixed nor rnl")

private fun number(option: String, value: String): Double =
    value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw UsageException("$option: $value is not a number")

private fun share(option: String, value: String): Double =
    number(option, value).takeIf { it in 0.0..1.0 } ?: throw UsageException("$option: $value is not between 0 and 1")

private fun fieldOfView(value: String): Double =
    number("--fov", value).takeIf { it > 0 && it < 360 } ?: throw UsageException("--fov: $value is not an angle")

private fun camera(value: String): Int =
    value.toIntOrNull()?.takeIf { it >= 0 } ?: throw UsageException("--camera: $value is not a camera's number")
