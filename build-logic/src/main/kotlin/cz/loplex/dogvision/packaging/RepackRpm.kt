package cz.loplex.dogvision.packaging

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * Makes the rpm jpackage wrote to [rpms] the one to ship, by running rpmbuild again on the spec and the app image
 * jpackage left in [temp], its --temp, with the spec changed:
 * - the window's desktop entry, [jpackageEntry] in the image, is a file of the package at [entry], as with the deb, and
 *   xdg-utils is not required. jpackage's scriptlets install and remove it with xdg-desktop-menu instead, which
 *   openSUSE's xdg-utils fails where /etc/xdg/menus does not exist, as on a system with no desktop: the entry is not
 *   installed, and %preun fails, so rpm cannot remove the package;
 * - the package owns no folder of the system's, wherever it is built. jpackage's spec leaves out the folders of the
 *   build machine's filesystem package, or where there is none, as off an rpm-based system, those of a list it prints
 *   on one line, which leaves out none: the rpm then owned /opt, /usr and /usr/share.
 *
 * jpackage cannot be given a spec of ours: it reads the last --resource-dir, and Compose passes its own after freeArgs.
 */
class RepackRpm(
    private val rpms: Provider<Directory>,
    private val temp: Provider<Directory>,
    private val jpackageEntry: String,
    private val entry: String,
) : Action<Task> {
    override fun execute(task: Task) {
        val temp = temp.get().asFile
        val spec = temp.resolve("SPECS").listFiles { file -> file.extension == "spec" }.orEmpty().singleOrNull()
            ?: error("jpackage left no single spec in ${temp.resolve("SPECS")}")
        val rpm = rpms.get().asFile.listFiles { file -> file.extension == "rpm" }.orEmpty().singleOrNull()
            ?: error("jpackage wrote no single rpm to ${rpms.get()}")
        var lines = spec.readLines()
        lines = replaceLine(lines, { it.startsWith("Requires: xdg-utils ,") }) {
            listOf("Requires: " + it.substringAfter(',').trim())
        }
        lines = replaceLine(lines, { it.startsWith("cp -r %{_sourcedir}/") }) {
            listOf(
                it,
                "install -d -m 755 %{buildroot}/${entry.substringBeforeLast('/')}",
                "mv %{buildroot}/$jpackageEntry %{buildroot}/$entry",
            )
        }
        lines = replaceLine(lines, { it.startsWith("{ rpm -ql filesystem ||") }) {
            val folders = "%{default_filesystem} /usr/share /${entry.substringBeforeLast('/')} %{_defaultlicensedir}"
            listOf("printf '%s\\n' $folders | sort > %{filesystem_filelist}")
        }
        lines = replaceLine(lines, { it.trim() == "xdg-desktop-menu install /$jpackageEntry" }) { emptyList() }
        lines = replaceLine(
            lines,
            {
                it.trim() ==
                    "do_if_file_belongs_to_single_package /$jpackageEntry xdg-desktop-menu uninstall /$jpackageEntry"
            },
        ) { emptyList() }
        spec.writeText(lines.joinToString("\n", postfix = "\n"))
        // As jpackage runs it.
        val process = ProcessBuilder(
            "rpmbuild", "-bb", spec.path,
            "--define", "%_sourcedir ${temp.resolve("image")}",
            "--define", "%_rpmdir ${rpm.parent}",
            "--define", "%_topdir $temp",
            "--define", "%_rpmfilename ${rpm.name}",
        ).redirectErrorStream(true).start()
        val output = process.inputReader().readText()
        check(process.waitFor() == 0) { "rpmbuild failed: $output" }
    }

    /** Replaces the one line of [lines] that [matches] by [by]'s, and fails where jpackage's spec has none. */
    private fun replaceLine(lines: List<String>, matches: (String) -> Boolean, by: (String) -> List<String>) =
        lines.singleOrNull(matches)?.let { line -> lines.flatMap { if (it === line) by(it) else listOf(it) } }
            ?: error("jpackage's spec has no single line to replace")
}
