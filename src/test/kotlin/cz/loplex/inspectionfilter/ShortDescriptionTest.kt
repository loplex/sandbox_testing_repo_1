package cz.loplex.inspectionfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one sentence that says what the plugin does stands word for word as the line under the README's title and as the
 * first paragraph of the plugin's description, the one JetBrains Marketplace shows on the plugin's card.
 * It is the description of the GitHub repository too, which these tests cannot read.
 */
class ShortDescriptionTest {

    @Test
    fun theReadmeAndThePluginDescriptionOpenWithTheSameSentence() {
        assertEquals(readmeShortDescription(), pluginDescriptionFirstParagraph())
    }

    @Test
    fun theSentenceIsShorterThan120Characters() {
        // The limit of standard-readme, which also keeps the sentence whole on the card.
        val sentence = readmeShortDescription()
        assertTrue("${sentence.length} characters: $sentence", sentence.length < 120)
    }
}

private fun readmeShortDescription(): String =
    File("README.md").readLines().dropWhile { !it.startsWith("# ") }.drop(1).first { it.isNotBlank() }.trim()

private fun pluginDescriptionFirstParagraph(): String =
    File("src/main/resources/META-INF/plugin.xml").readText()
        .substringAfter("<description><![CDATA[").substringBefore("]]></description>")
        .substringBefore("<br>").trim().replace(Regex("\\s+"), " ")
