package cz.loplex.dogvision.web

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The page words a plural as Android's getQuantityString does, by the language's plural rules, and takes what else
 * differs between languages from the browser, so that a language the app gains needs nothing written for the page.
 */
class TextsTest {
    @Test
    fun czechTakesOneFewAndOther() {
        val texts = Texts.of("cs")
        assertEquals("monochromat, 1 typ čípků", texts.plural("cone_types", 1, "monochromat", 1))
        assertEquals("dichromat, 2 typy čípků", texts.plural("cone_types", 2, "dichromat", 2))
        assertEquals("trichromat, 3 typy čípků", texts.plural("cone_types", 3, "trichromat", 3))
        assertEquals("x, 5 typů čípků", texts.plural("cone_types", 5, "x", 5))
    }

    @Test
    fun englishTakesOneAndOther() {
        val texts = Texts.of("en")
        assertEquals("monochromat, 1 cone type", texts.plural("cone_types", 1, "monochromat", 1))
        assertEquals("dichromat, 2 cone types", texts.plural("cone_types", 2, "dichromat", 2))
    }

    @Test
    fun namesEachLanguageInItself() {
        assertEquals("English", Texts.languageName("en"))
        assertEquals("Čeština", Texts.languageName("cs"))
        assertEquals("Deutsch", Texts.languageName("de"))
    }

    @Test
    fun separatesDecimalsAsTheLanguageDoes() {
        assertEquals('.', Texts.of("en").decimalSeparator)
        assertEquals(',', Texts.of("cs").decimalSeparator)
    }

    @Test
    fun takesThePageStringsALanguageLacksFromEnglish() {
        assertEquals("Switch camera", pageStrings("de").getValue("switch_camera_short"))
        assertEquals("Přepnout kameru", pageStrings("cs").getValue("switch_camera_short"))
    }
}
