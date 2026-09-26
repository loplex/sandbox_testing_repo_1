package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.ColourVision
import cz.loplex.dogvision.core.Species
import kotlinx.browser.window
import org.w3c.dom.asList
import org.w3c.dom.parsing.DOMParser

/**
 * The page's wording in one language: the Android app's string resources, which the build compiles into the page, and
 * the few things only the page says.
 */
class Texts(val language: String, private val strings: Map<String, String>) {
    /** The string [name], with each %1$s, %2$d and so on replaced by [args] and %% by %, as Android's getString. */
    fun get(name: String, vararg args: Any): String {
        val template = strings[name] ?: error("No string $name")
        if (args.isEmpty()) return template
        return PLACEHOLDER.replace(template) { match ->
            val index = match.groupValues[1]
            if (index.isEmpty()) "%" else args[index.toInt() - 1].toString()
        }
    }

    /** The plural [name] for [count], its quantity as the language's plural rules choose it, as getQuantityString. */
    fun plural(name: String, count: Int, vararg args: Any): String {
        val locale = language
        val quantity: String = js("new Intl.PluralRules(locale).select(count)").unsafeCast<String>()
        val key = "$name#$quantity".takeIf { it in strings } ?: "$name#other"
        return get(key, *args)
    }

    val decimalSeparator: Char get() = if (language == "cs") ',' else '.'

    /** The species' name with its kind of colour vision, for lists and captions: "dog (dichromat)". */
    fun speciesLabel(species: Species): String =
        get("species_label", get("species_" + species.name.lowercase()), colourVision(species.colourVision))

    fun colourVision(kind: ColourVision): String = get(kind.name.lowercase())

    companion object {
        private val PLACEHOLDER = Regex("""%(?:(\d+)\$[sd]|%)""")

        /** The languages there are strings for, the first the one a string missing from another is taken from. */
        val LANGUAGES = listOf("en") + (ANDROID_STRINGS.keys - "en").sorted()

        /** The first of the browser's languages there are strings for, or English. */
        fun preferredLanguage(): String = window.navigator.languages.asList()
            .map { it.substringBefore('-').lowercase() }
            .firstOrNull { it in LANGUAGES } ?: LANGUAGES.first()

        /** The strings of [language], with English for any it lacks. */
        fun of(language: String): Texts {
            val strings = listOf(LANGUAGES.first(), language).distinct()
                .fold(emptyMap<String, String>()) { all, it -> all + parseStrings(ANDROID_STRINGS.getValue(it)) }
            return Texts(language, strings + WEB_TEXTS.getValue(language))
        }
    }
}

/**
 * The <string> resources of an Android strings.xml by name, their escapes and quotes resolved as aapt2 does, and each
 * quantity of its <plurals> by the name "name#quantity".
 */
fun parseStrings(xml: String): Map<String, String> {
    val document = DOMParser().parseFromString(xml, "text/xml")
    val strings = document.getElementsByTagName("string").asList().associate { element ->
        element.getAttribute("name").orEmpty() to androidText(element.textContent.orEmpty())
    }
    val plurals = document.getElementsByTagName("plurals").asList().flatMap { plural ->
        plural.getElementsByTagName("item").asList().map { item ->
            plural.getAttribute("name") + "#" + item.getAttribute("quantity") to androidText(item.textContent.orEmpty())
        }
    }
    return strings + plurals
}

/**
 * The text of a string resource as Android shows it: a backslash escapes the character after it (\n and \t stand for
 * a newline and a tab), double quotes are dropped and keep the whitespace between them, and elsewhere a run of
 * whitespace is one space and none is left at either end.
 */
fun androidText(raw: String): String {
    val text = StringBuilder()
    var quoted = false
    var space = false
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\\' && i + 1 < raw.length -> {
                if (space) text.append(' ')
                space = false
                i++
                text.append(
                    when (raw[i]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> raw[i]
                    },
                )
            }

            c == '"' -> {
                if (space) text.append(' ')
                space = false
                quoted = !quoted
            }

            c.isWhitespace() && !quoted -> space = text.isNotEmpty()

            else -> {
                if (space) text.append(' ')
                space = false
                text.append(c)
            }
        }
        i++
    }
    return text.toString()
}

/** What only the page says, which the Android app has no string for. */
private val WEB_TEXTS = mapOf(
    "en" to mapOf(
        "open_photo" to "Open a photo",
        "choose_photo" to "Open a photo, or drop one here, to see it as the chosen animal sees it.",
        "photo_failed" to "Cannot open %1\$s as a photo",
        "no_webgl2" to "This browser cannot draw the view: it has no WebGL 2.",
        "gl_failed" to "This browser cannot draw the view: %1\$s",
    ),
    "cs" to mapOf(
        "open_photo" to "Otevřít fotku",
        "choose_photo" to "Otevřete fotku nebo ji sem přetáhněte a uvidíte ji tak, jak ji vidí vybraný živočich.",
        "photo_failed" to "%1\$s nejde otevřít jako fotka",
        "no_webgl2" to "Tento prohlížeč neumí zobrazení vykreslit, protože nepodporuje WebGL 2.",
        "gl_failed" to "Tento prohlížeč neumí zobrazení vykreslit: %1\$s",
    ),
)
