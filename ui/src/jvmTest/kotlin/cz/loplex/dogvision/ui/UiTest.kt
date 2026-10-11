package cz.loplex.dogvision.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.time.Duration.Companion.minutes

/**
 * Compose's runComposeUiTest with two minutes per test instead of its one:
 * a test that passes quickly on its own ran out of the minute
 * while ./gradlew check built and tested other modules in parallel.\
 * A hang still fails, after two minutes.
 */
@OptIn(ExperimentalTestApi::class)
fun runUiTest(block: suspend ComposeUiTest.() -> Unit) = runComposeUiTest(testTimeout = 2.minutes, block = block)
