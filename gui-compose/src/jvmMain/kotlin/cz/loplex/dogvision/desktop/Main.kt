package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.runCommandLine
import kotlin.system.exitProcess

/**
 * `dog-vision`, as the Python program's: a photo given alone is converted by the command line, and anything else
 * opens the window.
 */
fun main(args: Array<String>) {
    exitProcess(runCommandLine(args.toList(), System.out, System.err, ::showWindow))
}
