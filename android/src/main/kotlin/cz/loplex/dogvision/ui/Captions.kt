package cz.loplex.dogvision.ui

import android.content.Context
import cz.loplex.dogvision.R
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.formatFixed

/** What each image of [view] shows, left to right or top to bottom. */
fun Context.captions(view: View, differenceShare: Double?): List<String> {
    val right = speciesLabel(view.params.species)
    if (!view.sideBySide) return listOf(right)
    val left = view.compare?.let { speciesLabel(it) } ?: getString(R.string.original)
    if (!view.difference || differenceShare == null) return listOf(left, right)
    val share = getString(R.string.percent, formatFixed(differenceShare * 100, 0, '.'))
    return listOf(left, right, getString(R.string.difference_caption, share))
}
