package cz.loplex.dogvision.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/model.md states what core does: its two tables list every species, in order, with the peaks, S-cone shares,
 * acuities and sources of [Species], and the neutral points it quotes are the model's.
 */
class ModelDocTest {
    private val docs = File(checkNotNull(System.getProperty("dogvision.docs")) { "dogvision.docs is not set" })
    private val model = File(docs, "model.md").readText()

    @Test
    fun `the species table is Species`() {
        assertEquals(emptyList(), speciesTableErrors(model))
    }

    @Test
    fun `the acuity table is Species`() {
        assertEquals(emptyList(), acuityTableErrors(model))
    }

    @Test
    fun `the neutral points quoted hold`() {
        assertTrue(model.contains("while it matches the dog's 480 nm"), "model.md no longer quotes the dog's")
        assertEquals(480.0, neutralPoint(Species.DOG), 5.0, "model.md says the model matches the dog's 480 nm")
        val deuteranope = neutralPoint(Species.DEUTERANOPE)
        assertTrue(deuteranope < 505 - 15, "model.md says $deuteranope nm is well short of the deuteranope's 505 nm")
    }

    @Test
    fun `a table that differs is caught`() {
        val wrongPeak = model.replace("| 429    | –      | 555    |", "| 429    | –      | 556    |")
        val wrongShare = model.replace("| 10–18       |", "| 10–19       |")
        val missingRow = model.lines().filterNot { it.startsWith("| `ferret`") }.joinToString("\n")
        val wrongAcuity = model.replace("| 11.6               |", "| 11.7               |")
        val wrongSource = model.replace("| Hanke & Dehnhardt 2009, in air ", "| Hanke & Dehnhardt 2009        ")
        for (probe in listOf(wrongPeak, wrongShare, missingRow)) {
            assertTrue(probe != model, "A probe did not change model.md")
            assertTrue(speciesTableErrors(probe).isNotEmpty(), "A wrong species table went unnoticed")
        }
        for (probe in listOf(wrongAcuity, wrongSource, missingRow)) {
            assertTrue(probe != model, "A probe did not change model.md")
            assertTrue(acuityTableErrors(probe).isNotEmpty(), "A wrong acuity table went unnoticed")
        }
    }

    private companion object {
        val ROW = Regex("""^\| `([\w-]+)` +\|(.*)\|$""")

        /** The rows of the table whose header starts with [header], by species id, each its cells after the id. */
        fun table(markdown: String, header: Regex): Map<String, List<String>> {
            val start = header.find(markdown)?.range?.first ?: return emptyMap()
            return markdown.substring(start).split("\n\n").first().lines().drop(2).mapNotNull { line ->
                val match = ROW.find(line) ?: return@mapNotNull null
                match.groupValues[1] to match.groupValues[2].split("|").map(String::trim)
            }.toMap()
        }

        fun number(value: Double, digits: Int) = formatSignificant(value, digits, '.')

        fun source(source: Source) = listOfNotNull(source.citation, source.note?.english).joinToString(", ")

        fun percent(share: Double) = formatFixed(share * 100, 0, '.')

        fun speciesRow(species: Species): List<String> {
            val peaks = species.peaks
            val slots = when (peaks.size) {
                3 -> peaks
                2 -> listOf(peaks[0], null, peaks[1])
                else -> listOf(null, null, peaks[0])
            }
            val cells = slots.map { it?.let { peak -> number(peak, 6) } ?: "–" } + source(species.peaksFrom)
            val share = species.sCones
            return when {
                peaks.size == 1 -> cells + listOf("–", "")
                share == null -> cells + listOf(percent(ASSUMED_S_CONE_FRACTION) + " *", "assumed")
                percent(share.low) == percent(share.high) -> cells + listOf(percent(share.low), share.source)
                else -> cells + listOf(percent(share.low) + "–" + percent(share.high), share.source)
            }
        }

        fun acuityRow(species: Species): List<String> {
            val acuity = species.acuity ?: return listOf("–", "–", "not found measured")
            return listOf(number(acuity.across, 3), number(acuity.up, 3), source(acuity.source))
        }

        fun errors(rows: Map<String, List<String>>, name: String, expected: (Species) -> List<String>): List<String> {
            val ids = Species.entries.map { it.id }
            if (rows.keys.toList() != ids) return listOf("The $name table lists ${rows.keys}, Species has $ids")
            return Species.entries.mapNotNull { species ->
                val row = rows.getValue(species.id)
                val wanted = expected(species)
                if (row == wanted) null else "The $name table's ${species.id}: $row, core has $wanted"
            }
        }

        val SPECIES_HEADER = Regex("""^\| `--species` +\| S \[nm]""", RegexOption.MULTILINE)
        val ACUITY_HEADER = Regex("""^\| `--species` +\| Side by side""", RegexOption.MULTILINE)

        fun speciesTableErrors(markdown: String) = errors(table(markdown, SPECIES_HEADER), "species", ::speciesRow)

        fun acuityTableErrors(markdown: String) = errors(table(markdown, ACUITY_HEADER), "acuity", ::acuityRow)
    }
}
