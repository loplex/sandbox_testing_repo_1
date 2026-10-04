package cz.loplex.dogvision.swing

import cz.loplex.dogvision.cli.runCommandLine
import cz.loplex.dogvision.cli.systemTexts
import kotlin.system.exitProcess

/**
 * `dog-vision-swing`, as `dog-vision-compose`: the command line answers --help and a command line it cannot read, and
 * converts a photo given alone, in the system's language; anything else opens the Swing window.
 */
fun main(args: Array<String>) {
    exitProcess(runCommandLine(args.toList(), systemTexts(), System.out, System.err, ::showWindow))
}
