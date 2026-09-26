package cz.loplex.dogvision.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import cz.loplex.dogvision.R
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.speciesFacts
import kotlin.math.roundToInt

/**
 * The simulation's controls, in sections that open and close, as the desktop window has them. While
 * [recording], the controls that change how many images the view has are locked, as the desktop
 * locks them: a video cannot change its size.
 */
@Composable
fun Controls(
    view: View,
    recording: Boolean,
    onChange: ((View) -> View) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val params = view.params
    fun setParams(change: Params.() -> Params) = onChange { it.copy(params = it.params.change()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section(R.string.species, startsOpen = true) {
            SpeciesChoice(
                label = stringResource(R.string.species),
                about = R.string.about_species,
                choices = Species.entries,
                selected = params.species,
                onSelect = { species -> setParams { copy(species = species!!) } },
            )
        }
        Section(R.string.selected_species, startsOpen = true) {
            Facts(params.species)
        }
        Section(R.string.simulation, startsOpen = true) {
            PercentSlider(R.string.adaptation, R.string.about_adaptation, params.adaptation) { value ->
                setParams { copy(adaptation = value) }
            }
            PercentSlider(R.string.strength, R.string.about_strength, params.strength) { value ->
                setParams { copy(strength = value) }
            }
            Text(stringResource(R.string.colour_saturation), style = MaterialTheme.typography.labelLarge)
            ChromaScale.entries.forEach { scale ->
                val fixed = scale == ChromaScale.FIXED
                Choice(
                    text = stringResource(if (fixed) R.string.chroma_fixed else R.string.chroma_rnl),
                    about = if (fixed) R.string.about_chroma_fixed else R.string.about_chroma_rnl,
                    selected = params.chromaScale == scale,
                    onClick = { setParams { copy(chromaScale = scale) } },
                )
            }
        }
        Section(R.string.acuity, startsOpen = params.acuity) {
            Check(stringResource(R.string.acuity_blur), R.string.about_acuity_blur, params.acuity) { on ->
                setParams { copy(acuity = on) }
            }
            LabelledSlider(
                label = stringResource(R.string.field_of_view),
                about = R.string.about_field_of_view,
                value = params.fieldOfView.roundToInt(),
                range = 10..120,
                enabled = params.acuity,
            ) { degrees -> setParams { copy(fieldOfView = degrees.toDouble()) } }
        }
        Section(R.string.view, startsOpen = view.compare != null || view.difference) {
            Toggle(
                stringResource(R.string.side_by_side),
                R.string.about_side_by_side,
                view.sideBySide,
                enabled = !recording,
            ) { on -> onChange { it.copy(sideBySide = on) } }
            SpeciesChoice(
                label = stringResource(R.string.compare_with),
                about = R.string.about_compare_with,
                choices = listOf(null) + Species.entries,
                selected = view.compare,
                enabled = view.sideBySide,
                onSelect = { species -> onChange { it.copy(compare = species) } },
            )
            Toggle(
                stringResource(R.string.difference),
                R.string.about_difference,
                view.difference,
                enabled = view.sideBySide && !recording,
            ) { on -> onChange { it.copy(difference = on) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onReset, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.reset))
            }
            InfoButton(stringResource(R.string.reset), R.string.about_reset)
        }
        LanguageChoice()
    }
}

/**
 * The app's language: the system's, or one the app speaks, each named in itself as the desktop
 * window's Language menu names them. Android remembers the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageChoice() {
    val languages = listOf("" to stringResource(R.string.system_language), "en" to "English", "cs" to "Čeština")
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = languages.firstOrNull { it.first == current }?.second ?: languages.first().second,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                    },
                )
            }
        }
    }
}

/** A section: its title, which opens or closes it, and what it holds. */
@Composable
private fun Section(title: Int, startsOpen: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(startsOpen) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = null,
                modifier = Modifier.rotate(if (open) 180f else 0f),
            )
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = 8.dp)) { content() }
        }
        HorizontalDivider()
    }
}

/** A species out of [choices], null standing for the original image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeciesChoice(
    label: String,
    about: Int,
    choices: List<Species?>,
    selected: Species?,
    onSelect: (Species?) -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val original = stringResource(R.string.original)
    val name = { species: Species? -> species?.let(context::speciesLabel) ?: original }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = name(selected),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = { Text(label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            )
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                choices.forEach { species ->
                    DropdownMenuItem(
                        text = { Text(name(species)) },
                        onClick = {
                            onSelect(species)
                            expanded = false
                        },
                    )
                }
            }
        }
        InfoButton(label, about)
    }
}

/** A share from 0 to 1, shown and set in whole percent. */
@Composable
private fun PercentSlider(label: Int, about: Int, value: Double, onChange: (Double) -> Unit) =
    LabelledSlider(stringResource(label), about, (value * 100).roundToInt(), 0..100) { onChange(it / 100.0) }

@Composable
private fun LabelledSlider(
    label: String,
    about: Int,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.labelLarge)
            InfoButton(label, about)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun Choice(text: String, about: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 8.dp).weight(1f))
        InfoButton(text, about)
    }
}

@Composable
private fun Check(text: String, about: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, onValueChange = onChange, role = Role.Checkbox)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, modifier = Modifier.padding(start = 8.dp).weight(1f))
        InfoButton(text, about)
    }
}

@Composable
private fun Toggle(text: String, about: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        InfoButton(text, about)
    }
}

/** An info button that shows [about], what the control or fact called [title] means. */
@Composable
fun InfoButton(title: String, about: Int) {
    var shown by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { shown = true }) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.about))
    }
    if (shown) {
        AlertDialog(
            onDismissRequest = { shown = false },
            confirmButton = { TextButton(onClick = { shown = false }) { Text(stringResource(R.string.close)) } },
            title = { Text(title) },
            text = { Text(stringResource(about), modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }
}

/**
 * What is known about the species, a row per fact. A value breaks between its pieces, so that a
 * share stays with its source's opening and a citation's authors stay together; a piece wider than
 * the column on its own breaks at its spaces, as the web page's pieces do.
 */
@Composable
private fun Facts(species: Species) {
    val context = LocalContext.current
    val facts = remember(species, LocalConfiguration.current) { speciesFacts(species, AndroidTexts(context)) }
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val space = remember(measurer, style, density) { with(density) { measurer.measure(" ", style).size.width.toDp() } }
    facts.forEach { fact ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(fact.label.nameRes),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(112.dp),
            )
            // Laid out in pieces, read out as one text.
            FlowRow(
                Modifier.weight(1f).padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(space),
            ) {
                fact.value.forEach { Text(it, style = style) }
            }
            InfoButton(stringResource(fact.label.nameRes), fact.label.aboutRes)
        }
    }
}
