package cz.loplex.dogvision.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/** The windows' icon comes with the JAR, as the packages' 256 x 256 one. */
class WindowIconTest {
    @Test
    fun theIconIsThePackagesOwn() {
        val icon = windowIcon()
        assertEquals(256 to 256, icon.width to icon.height)
    }
}
