package cz.loplex.dogvision.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.loplex.dogvision.R
import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import kotlin.math.roundToInt

/** The simulation's controls, in sections that open and close, as the desktop window has them. */
@Composable
fun Controls(view: View, onChange: ((View) -> View) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val params = view.params
    fun setParams(change: Params.() -> Params) = onChange { it.copy(params = it.params.change()) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section(R.string.species, startsOpen = true) {
            SpeciesChoice(
                label = stringResource(R.string.species),
                choices = Species.entries,
                selected = params.species,
                onSelect = { species -> setParams { copy(species = species!!) } },
            )
        }
        Section(R.string.simulation, startsOpen = true) {
            PercentSlider(R.string.adaptation, params.adaptation) { value -> setParams { copy(adaptation = value) } }
            PercentSlider(R.string.strength, params.strength) { value -> setParams { copy(strength = value) } }
            Text(stringResource(R.string.colour_saturation), style = MaterialTheme.typography.labelLarge)
            ChromaScale.entries.forEach { scale ->
                Choice(
                    text = stringResource(if (scale == ChromaScale.FIXED) R.string.chroma_fixed else R.string.chroma_rnl),
                    selected = params.chromaScale == scale,
                    onClick = { setParams { copy(chromaScale = scale) } },
                )
            }
        }
        Section(R.string.acuity, startsOpen = params.acuity) {
            Check(stringResource(R.string.acuity_blur), params.acuity) { on -> setParams { copy(acuity = on) } }
            LabelledSlider(
                label = stringResource(R.string.field_of_view),
                value = params.fieldOfView.roundToInt(),
                range = 10..120,
                enabled = params.acuity,
            ) { degrees -> setParams { copy(fieldOfView = degrees.toDouble()) } }
        }
        Section(R.string.view, startsOpen = view.compare != null || view.difference) {
            Toggle(stringResource(R.string.side_by_side), view.sideBySide) { on -> onChange { it.copy(sideBySide = on) } }
            SpeciesChoice(
                label = stringResource(R.string.compare_with),
                choices = listOf(null) + Species.entries,
                selected = view.compare,
                enabled = view.sideBySide,
                onSelect = { species -> onChange { it.copy(compare = species) } },
            )
            Toggle(stringResource(R.string.difference), view.difference, enabled = view.sideBySide) { on ->
                onChange { it.copy(difference = on) }
            }
        }
        OutlinedButton(onClick = onReset, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(stringResource(R.string.reset))
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
    choices: List<Species?>,
    selected: Species?,
    onSelect: (Species?) -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val original = stringResource(R.string.original)
    val name = { species: Species? -> species?.let(context::speciesLabel) ?: original }
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = name(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
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
}

/** A share from 0 to 1, shown and set in whole percent. */
@Composable
private fun PercentSlider(label: Int, value: Double, onChange: (Double) -> Unit) =
    LabelledSlider(stringResource(label), (value * 100).roundToInt(), 0..100) { onChange(it / 100.0) }

@Composable
private fun LabelledSlider(label: String, value: Int, range: IntRange, enabled: Boolean = true, onChange: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.labelLarge)
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
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, onValueChange = onChange, role = Role.Checkbox).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Toggle(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
