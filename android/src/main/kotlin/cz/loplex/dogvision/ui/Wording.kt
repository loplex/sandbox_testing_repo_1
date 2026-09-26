package cz.loplex.dogvision.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import cz.loplex.dogvision.texts.Str
import cz.loplex.dogvision.texts.Texts

/** The texts the screens are worded in, provided by the activity in the language of its configuration. */
val LocalTexts = staticCompositionLocalOf<Texts> { error("No texts provided") }

/** The string [key] in the screens' language, as stringResource gives a resource. */
@Composable
@ReadOnlyComposable
fun text(key: Str, vararg args: Any?): String = LocalTexts.current.get(key, *args)

/** The texts in the language of this context's configuration, as its getString would pick a resource. */
val Context.texts: Texts
    get() = Texts.forLanguages(resources.configuration.locales.toLanguageTags().split(','))
