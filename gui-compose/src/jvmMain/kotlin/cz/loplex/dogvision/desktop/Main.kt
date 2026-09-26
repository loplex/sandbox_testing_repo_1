package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.runCommandLine
import cz.loplex.dogvision.cli.systemTexts
import kotlin.system.exitProcess

/**
 * `dog-vision`: the command line answers --help and a command line it cannot read,
 * and converts a photo given alone, in the system's language; anything else opens the window.
 */
fun main(args: Array<String>) {
    exitProcess(runCommandLine(args.toList(), systemTexts(), System.out, System.err, ::showWindow))
}

/** Whether this runs on Windows, where the window draws through ANGLE or WGL and a camera comes through DirectShow. */
internal val onWindows = System.getProperty("os.name").startsWith("Windows")
