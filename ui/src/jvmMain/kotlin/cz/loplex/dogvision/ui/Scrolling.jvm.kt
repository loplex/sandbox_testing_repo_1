package cz.loplex.dogvision.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Compose's own scrollbar is black at any theme's background, so on a dark one it takes the theme's text colour.
@Composable
internal actual fun Scrollbar(state: ScrollState, modifier: Modifier) {
    val colour = MaterialTheme.colorScheme.onSurface
    VerticalScrollbar(
        rememberScrollbarAdapter(state),
        modifier,
        style = LocalScrollbarStyle.current.copy(
            unhoverColor = colour.copy(alpha = 0.25f),
            hoverColor = colour.copy(alpha = 0.5f),
        ),
    )
}
