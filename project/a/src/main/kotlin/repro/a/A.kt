package repro.a

import repro.lib.Old

fun describe(old: Old): String {
    val name = old.name
    return "a: $name"
}
