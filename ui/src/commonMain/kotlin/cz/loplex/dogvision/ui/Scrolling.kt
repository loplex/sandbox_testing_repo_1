package cz.loplex.dogvision.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [content] in a column that scrolls when it is taller than the room it has, [padding] inside the scrolled part, with
 * a scrollbar at its end where the platform shows one.
 */
@Composable
internal fun ScrollingColumn(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.verticalScroll(scroll).padding(padding), content = content)
        // As tall as the column shows, without making the column take more room than its content needs.
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Scrollbar(scroll, Modifier.fillMaxHeight())
        }
    }
}

/** The bar that shows and drags where [state] has scrolled a column to: a desktop window has one, Android none. */
@Composable
internal expect fun Scrollbar(state: ScrollState, modifier: Modifier)
