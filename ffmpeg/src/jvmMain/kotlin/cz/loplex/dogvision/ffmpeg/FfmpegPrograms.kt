package cz.loplex.dogvision.ffmpeg

import java.io.File
import java.io.IOException

/** How installing ffmpeg from the window ended. */
sealed interface FfmpegInstall {
    /** ffmpeg and ffprobe are found, and the feeds run them from where they are. */
    data object Found : FfmpegInstall

    /** winget cannot be run, as on a Windows without Microsoft's App Installer. */
    data object NoWinget : FfmpegInstall

    /** winget ran, and ffmpeg is still not found; [reason] is the last thing winget said. */
    class Failed(val reason: String) : FfmpegInstall
}

/**
 * Where a window's feeds and the command line run ffmpeg and ffprobe from:
 * the PATH the app started with, or, once they are installed from the window, where they were put;
 * on Windows, if they are in neither, the folder that [FfmpegDownload] unpacks them into,
 * which the command line uses too.
 *
 * Windows has no ffmpeg of its own, so the window offers to download it with [FfmpegDownload],
 * and, if winget is on the PATH, to install Gyan's build through winget,
 * the package manager that comes with Windows 10 and 11;
 * winget unpacks it for the current user only and puts it on the user's PATH.\
 * That PATH is written to the registry, and only programs started afterwards see it,
 * so ffmpeg is looked for on the PATH the registry holds,
 * the one a program would get if it were started from the Start menu now.
 */
object FfmpegPrograms {
    private val PROGRAMS = listOf("ffmpeg", "ffprobe")

    /** The winget command that installs Gyan's build of ffmpeg for the current user, without asking anything. */
    @Suppress("ktlint:standard:argument-list-wrapping")
    private val WINGET = listOf(
        "winget",
        "install",
        "--id", "Gyan.FFmpeg",
        "--exact",
        "--source", "winget",
        "--scope", "user",
        "--disable-interactivity",
        "--accept-package-agreements",
        "--accept-source-agreements",
    )

    /** ffmpeg's download page for Windows builds, for when winget is missing. */
    const val DOWNLOAD_PAGE = "https://ffmpeg.org/download.html#build-windows"

    /** Each program's full path, if it was found after the window started. */
    @Volatile
    private var found = emptyMap<String, String>()

    /**
     * What runs [program]: its full path, if it was found after the window started,
     * or, on Windows, the downloaded one if it is not on the PATH the app started with; otherwise its name.
     */
    fun command(program: String): String = found[program] ?: downloaded(program) ?: program

    /** Whether winget is on the PATH the window started with; the App Installer puts it there. */
    fun wingetOnPath(): Boolean = find(listOf("winget"), folders(System.getenv("PATH").orEmpty())).isNotEmpty()

    private fun downloaded(program: String): String? {
        if (!onWindows || find(listOf(program), folders(System.getenv("PATH").orEmpty())).isNotEmpty()) return null
        return FfmpegDownload.program(program).takeIf(File::isFile)?.path
    }

    /**
     * Looks for ffmpeg on the PATH the registry holds, which has it if it was installed after the window started,
     * and installs it through winget if it is not there.\
     * Blocks until winget ends, which takes as long as the download.
     */
    fun install(): FfmpegInstall = if (locate()) FfmpegInstall.Found else installThroughWinget()

    /** Installs ffmpeg through winget, and finds it where winget put it. */
    private fun installThroughWinget(): FfmpegInstall {
        val process = try {
            ProcessBuilder(WINGET).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return FfmpegInstall.NoWinget
        }
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        val status = process.waitFor()
        return if (locate()) FfmpegInstall.Found else FfmpegInstall.Failed(lastSaid(output) ?: "status $status")
    }

    /** Whether ffmpeg and ffprobe are both on the PATH the registry holds; they are run from there if they are. */
    private fun locate(): Boolean {
        val (machine, user) = registryPath()
        val programs = find(PROGRAMS, folders("$machine;$user"))
        if (programs.size == PROGRAMS.size) found = programs
        return programs.size == PROGRAMS.size
    }

    /** Each of [programs] found in [folders], with its full path in the first folder that has it. */
    internal fun find(programs: List<String>, folders: List<File>): Map<String, String> = programs.mapNotNull { name ->
        folders.map { File(it, "$name.exe") }.firstOrNull(File::isFile)?.let { name to it.path }
    }.toMap()

    /** The folders a Windows PATH lists; an entry may be quoted, and two semicolons in a row leave an empty one. */
    internal fun folders(path: String): List<File> =
        path.split(';').map { it.trim().trim('"') }.filter(String::isNotEmpty).map(::File)

    /**
     * The machine's and the user's PATH as the registry holds them now, with their variables expanded,
     * the way Windows joins them for a program it starts; empty if PowerShell cannot tell.
     */
    private fun registryPath(): Pair<String, String> {
        val script = $$"[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; " +
            "[Environment]::GetEnvironmentVariable('Path', 'Machine'); " +
            "[Environment]::GetEnvironmentVariable('Path', 'User')"
        val lines = try {
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.outputStream.close()
            // Read as text, as FfmpegFeed's pipes are: readAllBytes asks a pipe for its position, which Wine refuses.
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.waitFor()
            @Suppress("SpellCheckingInspection")
            output.removePrefix("\uFEFF").lines()
        } catch (_: IOException) {
            emptyList()
        }
        return lines.getOrElse(0) { "" } to lines.getOrElse(1) { "" }
    }

    /** The last line of [output] with words in it, past the progress bars and spinners that winget draws. */
    internal fun lastSaid(output: String): String? =
        output.split('\r', '\n').map(String::trim).lastOrNull { line -> line.any(Char::isLetter) }

    /**
     * Starts [command], taking its program from where this object runs it,
     * with its standard input closed, unless [input] says the program reads it.\
     * Throws [FfmpegMissing] if the program cannot be run.
     */
    fun start(command: List<String>, input: Boolean = false): Process = try {
        val program = command(command.first())
        ProcessBuilder(listOf(program) + command.drop(1)).start().apply { if (!input) outputStream.close() }
    } catch (error: IOException) {
        throw FfmpegMissing(command.first(), error)
    }
}

/**
 * Thrown when [program], ffmpeg or ffprobe, cannot be run,
 * for example because it is not installed or not on the PATH.
 */
class FfmpegMissing(val program: String, cause: IOException) :
    IOException("Cannot run $program: ${cause.message}", cause)

/** Whether this is Windows, which has no ffmpeg of its own. */
internal val onWindows = System.getProperty("os.name").startsWith("Windows")
