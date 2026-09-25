package cz.loplex.dogvision.ui

import android.content.Context
import androidx.annotation.StringRes
import cz.loplex.dogvision.R
import cz.loplex.dogvision.core.ColourVision
import cz.loplex.dogvision.core.FactLabel
import cz.loplex.dogvision.core.FactTexts
import cz.loplex.dogvision.core.Note
import java.text.DecimalFormatSymbols

/** The facts worded in the language of [context]'s resources, with its decimal separator. */
class AndroidTexts(private val context: Context) : FactTexts {
    override val decimalSeparator =
        DecimalFormatSymbols.getInstance(context.resources.configuration.locales[0]).decimalSeparator

    override fun colourVision(kind: ColourVision) = context.getString(kind.nameRes)

    override fun coneTypes(kind: String, count: Int) =
        context.resources.getQuantityString(R.plurals.cone_types, count, kind, count)

    override fun percent(value: String) = context.getString(R.string.percent, value)

    override fun percentRange(low: String, high: String) = context.getString(R.string.percent_range, low, high)

    override fun shareOfCones(share: String) = context.getString(R.string.share_of_cones, share)

    override val assumed = context.getString(R.string.assumed)
    override val noColourAxis = context.getString(R.string.no_colour_axis)
    override val seesOnlyGrey = context.getString(R.string.sees_only_grey)

    override fun rnlOfFixed(gains: List<String>) =
        context.getString(R.string.rnl_of_fixed, gains.joinToString(context.getString(R.string.gains_and)))

    override val notFoundMeasured = context.getString(R.string.not_found_measured)
    override val leftSharp = context.getString(R.string.left_sharp)

    override fun acuity(value: String) = context.getString(R.string.acuity_value, value)

    override fun acuityAcross(value: String) = context.getString(R.string.acuity_across, value)

    override fun acuityUp(value: String) = context.getString(R.string.acuity_up, value)

    override fun note(note: Note) = context.getString(
        when (note) {
            Note.L_SHIFTED -> R.string.note_l_shifted
            Note.M_SHIFTED -> R.string.note_m_shifted
            Note.S_ASSUMED -> R.string.note_s_assumed
            Note.RANGE_11_7_TO_14 -> R.string.note_range_11_7_to_14
            Note.RANGE_1_8_TO_3_8 -> R.string.note_range_1_8_to_3_8
            Note.ABOUT_60 -> R.string.note_about_60
            Note.IN_AIR -> R.string.note_in_air
            Note.STRIPES_8_2_ARCMIN -> R.string.note_stripes_8_2_arcmin
        },
    )
}

@get:StringRes
val FactLabel.nameRes: Int
    get() = when (this) {
        FactLabel.COLOUR_VISION -> R.string.fact_colour_vision
        FactLabel.CONE_PEAKS -> R.string.fact_cone_peaks
        FactLabel.S_CONES -> R.string.fact_s_cones
        FactLabel.L_TO_M -> R.string.fact_l_to_m
        FactLabel.NEUTRAL_POINT -> R.string.fact_neutral_point
        FactLabel.RNL_SCALE -> R.string.fact_rnl_scale
        FactLabel.ACUITY -> R.string.fact_acuity
    }

/** What the fact means, for a reader who does not know the model. */
@get:StringRes
val FactLabel.aboutRes: Int
    get() = when (this) {
        FactLabel.COLOUR_VISION -> R.string.about_fact_colour_vision
        FactLabel.CONE_PEAKS -> R.string.about_fact_cone_peaks
        FactLabel.S_CONES -> R.string.about_fact_s_cones
        FactLabel.L_TO_M -> R.string.about_fact_l_to_m
        FactLabel.NEUTRAL_POINT -> R.string.about_fact_neutral_point
        FactLabel.RNL_SCALE -> R.string.about_fact_rnl_scale
        FactLabel.ACUITY -> R.string.about_fact_acuity
    }
