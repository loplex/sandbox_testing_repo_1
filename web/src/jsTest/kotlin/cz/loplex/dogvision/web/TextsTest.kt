package cz.loplex.dogvision.web

import kotlin.test.Test
import kotlin.test.assertEquals

/** The page words a plural as Android's getQuantityString does, by the language's plural rules. */
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
}
