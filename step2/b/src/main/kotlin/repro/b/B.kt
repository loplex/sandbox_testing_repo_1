package repro.b

import repro.lib.New
import repro.lib.Old

fun describe(old: Old, new: New): String {
    val name = new.name
    return "b: ${old.name} $name"
}
