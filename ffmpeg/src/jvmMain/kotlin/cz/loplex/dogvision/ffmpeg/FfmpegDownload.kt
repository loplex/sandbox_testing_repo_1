package cz.loplex.dogvision.ffmpeg

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Downloads ffmpeg for Windows on x86-64 when the window offers it:
 * BtbN's newest build of [BRANCH] with its DLLs, one of the builds that ffmpeg's download page lists;
 * [BRANCH] is the release branch that libs.versions.toml pins.\
 * The zip is checked against the release's checksums.sha256,
 * and its bin folder is unpacked into [folder], under %ProgramData%,
 * where every user of the machine finds it, and from where the MSI removes it when the app is uninstalled.
 */
object FfmpegDownload {
    private val properties = checkNotNull(javaClass.getResourceAsStream("ffmpeg.properties")) { "no ffmpeg.properties" }
        .use { stream -> Properties().apply { load(stream) } }

    /** The release branch, such as 9.0, whose newest build is downloaded. */
    val BRANCH: String = properties.getProperty("windowsBranch")

    /**
     * The app's English name, app_name in texts, which is also the MSI's name
     * and names the app's folder under %ProgramData%.
     */
    private val APP_NAME: String = properties.getProperty("appName")

    /** Where BtbN's newest builds are: a release of their own, with checksums.sha256 next to them. */
    private const val RELEASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"

    /** The zip of [BRANCH]'s newest build, with ffmpeg's libraries as DLLs next to the programs. */
    internal val ZIP = "ffmpeg-n$BRANCH-latest-win64-gpl-shared-$BRANCH.zip"

    /** The programs in the zip that the windows run, which the zip must contain. */
    private val PROGRAMS = listOf("ffmpeg.exe", "ffprobe.exe")

    /**
     * The folder the download is unpacked into, inside the app's own folder under %ProgramData%,
     * which bears the app's English name and which the MSIs remove as well.\
     * The download folder is named after the branch,
     * so that two versions of the app that use different branches each keep their own.
     */
    val folder: File by lazy {
        val programData = System.getenv("ProgramData") ?: "C:\\ProgramData"
        File(File(programData, APP_NAME), "ffmpeg-$BRANCH")
    }

    /** Where [program], ffmpeg or ffprobe, is once it is downloaded. */
    fun program(program: String): File = File(File(folder, "bin"), "$program.exe")

    /**
     * Downloads ffmpeg into [folder], unless it is already there,
     * telling [onPercent] how much of the zip has arrived so far.\
     * Blocks until it is unpacked, or says why not.
     */
    @Suppress("ReturnCount")
    fun download(onPercent: (Int) -> Unit): FfmpegInstall {
        if (PROGRAMS.all { File(File(folder, "bin"), it).isFile }) return FfmpegInstall.Found
        return try {
            val sums = open("$RELEASE/checksums.sha256") { input, _ -> input.bufferedReader().readText() }
            val expected = sha256Of(sums, ZIP) ?: return FfmpegInstall.Failed("checksums.sha256 names no $ZIP")
            val home = folder.parentFile.apply { mkdirs() }.toPath()
            val zip = Files.createTempFile(home, "ffmpeg-", ".zip")
            try {
                val actual = fetch("$RELEASE/$ZIP", zip, onPercent)
                if (actual != expected) return FfmpegInstall.Failed("$ZIP is not the zip that checksums.sha256 names")
                unpack(zip, folder.toPath())
            } finally {
                Files.deleteIfExists(zip)
            }
            FfmpegInstall.Found
        } catch (error: IOException) {
            FfmpegInstall.Failed(error.message ?: error.toString())
        }
    }

    /** The SHA-256 that [sums], in sha256sum's format, list for [file], in lower case; null if they list none. */
    internal fun sha256Of(sums: String, file: String): String? = sums.lines()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == file }
        ?.first()?.lowercase()

    /**
     * Unpacks the programs and the DLLs of [zip]'s bin folder, except ffplay, which the windows do not run,
     * into [destination]'s bin: first into a folder next to [destination], which then takes its place,
     * so that a half-unpacked folder is never found.\
     * Fails if the zip lacks ffmpeg or ffprobe.
     */
    internal fun unpack(zip: Path, destination: Path) {
        val parent = checkNotNull(destination.parent)
        val unpacked = Files.createTempDirectory(parent, "ffmpeg-")
        try {
            val bin = Files.createDirectory(unpacked.resolve("bin"))
            ZipFile(zip.toFile()).use { archive ->
                for (entry in archive.entries()) {
                    val parts = entry.name.split('/')
                    // The zip holds a single folder for the build, with bin in it.
                    // A file is unpacked under the last part of its entry's name alone,
                    // never under the folders before it.
                    val name = parts.last()
                    val program = !entry.isDirectory && parts.size == 3 && parts[1] == "bin"
                    if (!program || name == "ffplay.exe") continue
                    archive.getInputStream(entry).use { Files.copy(it, bin.resolve(name)) }
                }
            }
            val missing = PROGRAMS.filterNot { Files.isRegularFile(bin.resolve(it)) }
            if (missing.isNotEmpty()) throw IOException("the zip has no ${missing.joinToString(" or ")} in bin")
            deleteRecursively(destination)
            Files.move(unpacked, destination, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            deleteRecursively(unpacked)
        }
    }

    /** Downloads [url] into [file], and returns its SHA-256 in lower case. */
    private fun fetch(url: String, file: Path, onPercent: (Int) -> Unit): String = open(url) { input, length ->
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        var percent = -1
        input.use {
            Files.newOutputStream(file).use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    read += count
                    val now = if (length > 0) (read * 100 / length).toInt() else 0
                    if (now != percent) {
                        percent = now
                        onPercent(now)
                    }
                }
            }
        }
        HexFormat.of().formatHex(digest.digest())
    }

    /**
     * What [read] returns for [url]'s body and its length, which is -1 if the server gives none.\
     * HttpURLConnection, which is in java.base, follows GitHub's redirects to where the files are kept,
     * since both use HTTPS.
     */
    private fun <T> open(url: String, read: (InputStream, Long) -> T): T {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("$url answered $status")
            return read(connection.inputStream, connection.contentLengthLong)
        } finally {
            connection.disconnect()
        }
    }

    private fun deleteRecursively(path: Path) {
        if (Files.exists(path)) path.toFile().deleteRecursively()
    }
}
