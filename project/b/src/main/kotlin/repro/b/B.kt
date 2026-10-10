package repro.b

fun describe(name: String): String {
    val trimmed = name.trim()
    return "b: $trimmed"
}
