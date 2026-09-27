package cz.loplex.dogvision.packaging

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Makes each deb jpackage wrote to [debs] the one to ship, and packs it again with xz, which every dpkg reads, where
 * the build machine's dpkg-deb may choose zstd, which Debian's reads only from Debian 12 on:
 * - its Depends is [depends]';
 * - the window's desktop entry, [jpackageEntry] in the package's tree, is a file of the package at [entry], as Debian's
 *   packages ship theirs: dpkg makes its folder where it is missing and removes it with the package. jpackage's scripts
 *   install it and remove it with xdg-desktop-menu instead, which fails where the folder does not exist, as on a system
 *   with no desktop, and dpkg then leaves the package half-installed or half-removed.
 */
class RepackDeb(
    private val debs: Provider<Directory>,
    private val depends: Provider<RegularFile>,
    private val jpackageEntry: String,
    private val entry: String,
) : Action<Task> {
    override fun execute(task: Task) {
        for (deb in debs.get().asFile.listFiles { file -> file.extension == "deb" }.orEmpty()) {
            val tree = Files.createTempDirectory("deb").toFile()
            try {
                dpkgDeb("-R", deb.path, tree.path)
                val control = tree.resolve("DEBIAN/control")
                // In its place: the blank line jpackage ends the file with would end the stanza before it.
                val lines = control.readLines()
                    .map { if (it.startsWith("Depends:")) "Depends: ${depends.get().asFile.readText()}" else it }
                control.writeText(lines.joinToString("\n", postfix = "\n"))
                val desktopFile = tree.resolve(jpackageEntry)
                check(desktopFile.isFile) { "jpackage's deb has no $jpackageEntry" }
                val installed = tree.resolve(entry)
                // Each folder made here as Debian's are, rwxr-xr-x, whatever the build's umask.
                generateSequence(installed.parentFile) { it.parentFile }.takeWhile { it != tree }.toList().reversed()
                    .forEach { folder ->
                        folder.mkdir()
                        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
                    }
                check(desktopFile.renameTo(installed)) { "Cannot move $jpackageEntry to $entry" }
                removeLine(tree.resolve("DEBIAN/postinst"), "xdg-desktop-menu install /$jpackageEntry")
                removeLine(
                    tree.resolve("DEBIAN/prerm"),
                    "do_if_file_belongs_to_single_package /$jpackageEntry xdg-desktop-menu uninstall /$jpackageEntry",
                )
                dpkgDeb("--root-owner-group", "-Zxz", "-b", tree.path, deb.path)
            } finally {
                tree.deleteRecursively()
            }
        }
    }

    /** Removes the one line of [script] that is [line], and fails where jpackage's script has none. */
    private fun removeLine(script: File, line: String) {
        val lines = script.readLines()
        check(lines.count { it.trim() == line } == 1) { "${script.name} has no line $line" }
        script.writeText(lines.filterNot { it.trim() == line }.joinToString("\n", postfix = "\n"))
    }

    private fun dpkgDeb(vararg arguments: String) {
        val process = ProcessBuilder("dpkg-deb", *arguments).redirectErrorStream(true).start()
        val output = process.inputReader().readText()
        check(process.waitFor() == 0) { "dpkg-deb ${arguments.joinToString(" ")} failed: $output" }
    }
}
