package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.ChromaScale
import cz.loplex.dogvision.core.Params
import cz.loplex.dogvision.core.Species
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.speciesFacts
import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import kotlin.math.roundToInt

/**
 * The controls of the view, in the sections and ranges of the Android app's panel, built into [container]. Each
 * change is handed to [onChange] as a function of the view; [show] sets every control to what a view holds.
 *
 * The language chooser at the end shows [language], null for the browser's, and hands a choice to [onLanguage].
 */
class Controls(
    private val container: HTMLElement,
    private val texts: Texts,
    language: String?,
    private val onChange: ((View) -> View) -> Unit,
    onLanguage: (String?) -> Unit,
) {
    private val speciesOptions = Species.entries.map { it.name to texts.speciesLabel(it) }
    private val species = select(speciesOptions)
    private val adaptation = slider(0..100)
    private val strength = slider(0..100)
    private val chroma = ChromaScale.entries.associateWith { radio("chroma") }
    private val acuity = checkbox()
    private val fieldOfView = slider(10..120)
    private val sideBySide = checkbox()
    private val compare = select(listOf("" to texts.get("original")) + speciesOptions)
    private val difference = checkbox()

    /** What is known about the species chosen, a row per fact, and the species it was built for. */
    private val facts = document.createElement("div") as HTMLElement
    private var factsOf: Species? = null

    /** The value shown beside each slider's label. */
    private val outputs = mutableMapOf<HTMLInputElement, HTMLElement>()
    private val reset = (document.createElement("button") as HTMLButtonElement).apply {
        type = "button"
        textContent = texts.get("reset")
    }

    init {
        section("species") {
            labelled("species", "about_species", species)
        }
        section("selected_species") {
            appendChild(facts)
        }
        section("simulation") {
            labelled("adaptation", "about_adaptation", adaptation)
            labelled("strength", "about_strength", strength)
            val heading = document.createElement("p") as HTMLElement
            heading.className = "label"
            heading.textContent = texts.get("colour_saturation")
            appendChild(heading)
            inline("chroma_fixed", "about_chroma_fixed", chroma.getValue(ChromaScale.FIXED))
            inline("chroma_rnl", "about_chroma_rnl", chroma.getValue(ChromaScale.RNL))
        }
        section("acuity") {
            inline("acuity_blur", "about_acuity_blur", acuity)
            labelled("field_of_view", "about_field_of_view", fieldOfView)
        }
        section("view") {
            inline("side_by_side", "about_side_by_side", sideBySide)
            labelled("compare_with", "about_compare_with", compare)
            inline("difference", "about_difference", difference)
            val row = document.createElement("div") as HTMLElement
            row.className = "row"
            row.appendChild(reset)
            appendChild(row)
            about(row, "about_reset")
        }

        // In a Params.copy, the params' own names hide the controls'.
        val controls = this
        species.onChange { setParams { copy(species = Species.valueOf(controls.species.value)) } }
        adaptation.onChange { setParams { copy(adaptation = controls.adaptation.valueAsNumber / 100) } }
        strength.onChange { setParams { copy(strength = controls.strength.valueAsNumber / 100) } }
        chroma.forEach { (scale, input) ->
            input.onChange { if (input.checked) setParams { copy(chromaScale = scale) } }
        }
        acuity.onChange { setParams { copy(acuity = controls.acuity.checked) } }
        fieldOfView.onChange { setParams { copy(fieldOfView = controls.fieldOfView.valueAsNumber) } }
        sideBySide.onChange { onChange { it.copy(sideBySide = sideBySide.checked) } }
        compare.onChange {
            val species = compare.value.takeIf(String::isNotEmpty)?.let(Species::valueOf)
            onChange { it.copy(compare = species) }
        }
        difference.onChange { onChange { it.copy(difference = difference.checked) } }
        reset.addEventListener("click", { onChange { View() } })

        // Each language named in itself, as the Android app's LanguageChoice names them.
        val languages = listOf("" to texts.get("browser_language")) +
            Texts.LANGUAGES.map { it to Texts.languageName(it) }
        val languageChoice = select(languages)
        languageChoice.value = language.orEmpty()
        languageChoice.onChange { onLanguage(languageChoice.value.takeIf(String::isNotEmpty)) }
        val choice = document.createElement("div") as HTMLElement
        choice.className = "language"
        val row = document.createElement("div") as HTMLElement
        row.className = "row"
        val label = document.createElement("label") as HTMLElement
        languageChoice.id = "control-language"
        label.setAttribute("for", languageChoice.id)
        label.textContent = texts.get("language")
        row.appendChild(label)
        choice.appendChild(row)
        choice.appendChild(languageChoice)
        container.appendChild(choice)
    }

    /**
     * Sets every control to what [view] holds, and enables those that apply to it; while [recording], not those that
     * change how many images the view has, as in the Android app.
     */
    fun show(view: View, recording: Boolean = false) {
        val params = view.params
        species.value = params.species.name
        if (params.species != factsOf) showFacts(params.species)
        setSlider(adaptation, (params.adaptation * 100).roundToInt())
        setSlider(strength, (params.strength * 100).roundToInt())
        chroma.forEach { (scale, input) -> input.checked = scale == params.chromaScale }
        acuity.checked = params.acuity
        setSlider(fieldOfView, params.fieldOfView.roundToInt())
        fieldOfView.disabled = !params.acuity
        sideBySide.checked = view.sideBySide
        sideBySide.disabled = recording
        compare.value = view.compare?.name.orEmpty()
        compare.disabled = !view.sideBySide
        difference.checked = view.difference
        difference.disabled = !view.sideBySide || recording
    }

    /**
     * Shows what is known about [species], as the Android app's Facts. A value breaks only between its pieces, unless a
     * piece is longer than the whole line, so that a share stays with its source's opening and a citation's authors
     * stay together.
     */
    private fun showFacts(species: Species) {
        facts.innerHTML = ""
        speciesFacts(species, FactWording(texts)).forEach { fact ->
            val name = fact.label.name.lowercase()
            val row = document.createElement("div") as HTMLElement
            row.className = "row fact"
            val label = document.createElement("span") as HTMLElement
            label.className = "fact-label"
            label.textContent = texts.get("fact_$name")
            row.appendChild(label)
            val value = document.createElement("span") as HTMLElement
            value.className = "fact-value"
            fact.value.forEachIndexed { i, text ->
                if (i > 0) value.append(" ")
                val piece = document.createElement("span") as HTMLElement
                piece.className = "piece"
                piece.textContent = text
                value.appendChild(piece)
            }
            row.appendChild(value)
            facts.appendChild(row)
            facts.about(row, "about_fact_$name")
        }
        factsOf = species
    }

    /** Sets [slider] and the value shown beside its label. */
    private fun setSlider(slider: HTMLInputElement, value: Int) {
        slider.value = value.toString()
        outputs[slider]?.textContent = slider.value
    }

    private fun setParams(change: Params.() -> Params) = onChange { it.copy(params = it.params.change()) }

    private fun section(title: String, build: HTMLElement.() -> Unit) {
        val section = document.createElement("section") as HTMLElement
        val heading = document.createElement("h2") as HTMLElement
        heading.textContent = texts.get(title)
        section.appendChild(heading)
        section.build()
        container.appendChild(section)
    }

    /** A control under its label, with the value of a slider beside the label, and what it means. */
    private fun HTMLElement.labelled(label: String, about: String, control: HTMLElement) {
        val id = "control-$label"
        control.id = id
        val row = document.createElement("div") as HTMLElement
        row.className = "row"
        val text = document.createElement("label") as HTMLElement
        text.setAttribute("for", id)
        text.textContent = texts.get(label)
        row.appendChild(text)
        (control as? HTMLInputElement)?.takeIf { it.type == "range" }?.let { slider ->
            val value = document.createElement("output") as HTMLElement
            value.setAttribute("for", id)
            value.textContent = slider.value
            slider.addEventListener("input", { value.textContent = slider.value })
            outputs[slider] = value
            row.appendChild(value)
        }
        appendChild(row)
        appendChild(control)
        about(row, about)
    }

    /** A check box or radio button with its label after it, and what it means. */
    private fun HTMLElement.inline(label: String, about: String, input: HTMLInputElement) {
        val row = document.createElement("div") as HTMLElement
        row.className = "row"
        val text = document.createElement("label") as HTMLElement
        text.appendChild(input)
        text.append(" " + texts.get(label))
        row.appendChild(text)
        appendChild(row)
        about(row, about)
    }

    /**
     * A button that shows what a control means, into [row], and the text it shows, hidden until then, at the end of
     * this section.
     */
    private fun HTMLElement.about(row: HTMLElement, name: String) {
        val text = document.createElement("div") as HTMLElement
        text.className = "about"
        text.id = "about-$name"
        text.hidden = true
        texts.get(name).split("\n\n").forEach { paragraph ->
            val p = document.createElement("p") as HTMLElement
            p.textContent = paragraph
            text.appendChild(p)
        }
        val button = document.createElement("button") as HTMLButtonElement
        button.type = "button"
        button.className = "info"
        button.textContent = "i"
        button.title = texts.get("about")
        button.setAttribute("aria-label", texts.get("about"))
        button.setAttribute("aria-expanded", "false")
        button.setAttribute("aria-controls", text.id)
        button.addEventListener("click", {
            text.hidden = !text.hidden
            button.setAttribute("aria-expanded", (!text.hidden).toString())
        })
        row.appendChild(button)
        appendChild(text)
    }

    private fun select(options: List<Pair<String, String>>): HTMLSelectElement =
        (document.createElement("select") as HTMLSelectElement).apply {
            options.forEach { (value, label) ->
                val option = document.createElement("option") as HTMLOptionElement
                option.value = value
                option.textContent = label
                appendChild(option)
            }
        }

    private fun slider(range: IntRange): HTMLInputElement = input("range").apply {
        min = range.first.toString()
        max = range.last.toString()
        step = "1"
    }

    private fun checkbox(): HTMLInputElement = input("checkbox")

    private fun radio(group: String): HTMLInputElement = input("radio").apply { name = group }

    private fun input(type: String): HTMLInputElement =
        (document.createElement("input") as HTMLInputElement).apply { this.type = type }
}

private fun HTMLElement.onChange(handler: () -> Unit) {
    addEventListener(if (this is HTMLInputElement && type == "range") "input" else "change", { handler() })
}
