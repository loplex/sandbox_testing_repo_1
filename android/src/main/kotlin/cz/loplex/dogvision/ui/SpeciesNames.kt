package cz.loplex.dogvision.ui

import android.content.Context
import androidx.annotation.StringRes
import cz.loplex.dogvision.R
import cz.loplex.dogvision.core.ColourVision
import cz.loplex.dogvision.core.Species

/** The species' name; a when, so that a species added without one does not compile. */
@get:StringRes
val Species.nameRes: Int
    get() = when (this) {
        Species.DOG -> R.string.species_dog
        Species.CAT -> R.string.species_cat
        Species.HORSE -> R.string.species_horse
        Species.COW -> R.string.species_cow
        Species.SHEEP -> R.string.species_sheep
        Species.GOAT -> R.string.species_goat
        Species.PIG -> R.string.species_pig
        Species.FALLOW_DEER -> R.string.species_fallow_deer
        Species.WHITE_TAILED_DEER -> R.string.species_white_tailed_deer
        Species.GUINEA_PIG -> R.string.species_guinea_pig
        Species.TREE_SQUIRREL -> R.string.species_tree_squirrel
        Species.GROUND_SQUIRREL -> R.string.species_ground_squirrel
        Species.FERRET -> R.string.species_ferret
        Species.PROTANOPE -> R.string.species_protanope
        Species.DEUTERANOPE -> R.string.species_deuteranope
        Species.HUMAN -> R.string.species_human
        Species.PROTANOMALOUS -> R.string.species_protanomalous
        Species.DEUTERANOMALOUS -> R.string.species_deuteranomalous
        Species.MACAQUE -> R.string.species_macaque
        Species.HOWLER_MONKEY -> R.string.species_howler_monkey
        Species.MARMOSET_FEMALE -> R.string.species_marmoset_female
        Species.HARBOUR_SEAL -> R.string.species_harbour_seal
        Species.BOTTLENOSE_DOLPHIN -> R.string.species_bottlenose_dolphin
    }

@get:StringRes
val ColourVision.nameRes: Int
    get() = when (this) {
        ColourVision.MONOCHROMAT -> R.string.monochromat
        ColourVision.DICHROMAT -> R.string.dichromat
        ColourVision.TRICHROMAT -> R.string.trichromat
    }

/** The species' name with its kind of colour vision, for lists and captions: "dog (dichromat)". */
fun Context.speciesLabel(species: Species): String =
    getString(R.string.species_label, getString(species.nameRes), getString(species.colourVision.nameRes))
