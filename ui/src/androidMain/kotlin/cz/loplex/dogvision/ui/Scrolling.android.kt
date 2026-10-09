package cz.loplex.dogvision.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Compose has its scrollbar only on the desktop; on Android a column is scrolled by touch, without a bar.
@Composable
internal actual fun Scrollbar(state: ScrollState, modifier: Modifier) = Unit
