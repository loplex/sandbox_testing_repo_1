package cz.loplex.dogvision.cli

import java.io.PrintStream
import kotlin.system.exitProcess

/** The command line alone, without the window: it converts a photo, and says to use the desktop app for the rest. */
fun main(args: Array<String>) {
    exitProcess(
        runCommandLine(args.toList(), System.out, System.err) {
            System.err.println("The window is the desktop app's; this command line only converts a photo")
            2
        },
    )
}

/**
 * Reads [args] and does what they ask for from the command line: prints the usage, or converts a photo; anything
 * else, the window, is [window]'s to do. Returns the exit status: 2 for a command line that cannot be read, as
 * Python's argparse has it.
 */
fun runCommandLine(args: List<String>, out: PrintStream, err: PrintStream, window: (Arguments) -> Int): Int {
    val arguments = try {
        parseArguments(args)
    } catch (error: UsageException) {
        err.println(USAGE.lineSequence().first())
        err.println("dog-vision: error: ${error.message}")
        return 2
    }
    return when {
        arguments.help -> 0.also { out.println(USAGE) }
        arguments.converts -> convertPhoto(arguments, out, err)
        else -> window(arguments)
    }
}
