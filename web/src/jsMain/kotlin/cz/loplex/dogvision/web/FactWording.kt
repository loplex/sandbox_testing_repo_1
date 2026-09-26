package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.ColourVision
import cz.loplex.dogvision.core.FactTexts
import cz.loplex.dogvision.core.Note

/** The facts worded in the language of [texts], from the strings the Android app's AndroidTexts words them with. */
class FactWording(private val texts: Texts) : FactTexts {
    override val decimalSeparator = texts.decimalSeparator

    override fun colourVision(kind: ColourVision) = texts.colourVision(kind)

    override fun coneTypes(kind: String, count: Int) = texts.plural("cone_types", count, kind, count)

    override fun percent(value: String) = texts.get("percent", value)

    override fun percentRange(low: String, high: String) = texts.get("percent_range", low, high)

    override fun shareOfCones(share: String) = texts.get("share_of_cones", share)

    override val assumed = texts.get("assumed")
    override val noColourAxis = texts.get("no_colour_axis")
    override val seesOnlyGrey = texts.get("sees_only_grey")

    override fun rnlOfFixed(gains: List<String>) = texts.get("rnl_of_fixed", gains.joinToString(texts.get("gains_and")))

    override val notFoundMeasured = texts.get("not_found_measured")
    override val leftSharp = texts.get("left_sharp")

    override fun acuity(value: String) = texts.get("acuity_value", value)

    override fun acuityAcross(value: String) = texts.get("acuity_across", value)

    override fun acuityUp(value: String) = texts.get("acuity_up", value)

    override fun note(note: Note) = texts.get("note_" + note.name.lowercase())
}
