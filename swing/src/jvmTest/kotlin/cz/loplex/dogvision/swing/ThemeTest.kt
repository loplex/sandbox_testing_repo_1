package cz.loplex.dogvision.swing

import java.awt.RenderingHints
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The system's dark or light, read from what dbus-send prints of the portal and `reg query` of the registry, and the
 * text smoothing the look and feel is given.
 */
class ThemeTest {
    @Test
    fun thePortalsColourSchemeIsReadFromReadOneAndFromRead() {
        assertEquals(1, portalColourScheme("   variant       uint32 1\n"))
        assertEquals(2, portalColourScheme("   variant       variant       uint32 2\n"))
        assertNull(portalColourScheme("Error org.freedesktop.DBus.Error.ServiceUnknown: The name is not activatable\n"))
    }

    @Test
    fun theRegistrySaysDarkWhereAppsUseNoLightTheme() {
        val key = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n"
        assertTrue(registryPrefersDark("\r\n$key    AppsUseLightTheme    REG_DWORD    0x0\r\n\r\n"))
        assertFalse(registryPrefersDark("\r\n$key    AppsUseLightTheme    REG_DWORD    0x1\r\n\r\n"))
        assertFalse(
            registryPrefersDark("ERROR: The system was unable to find the specified registry key or value.\r\n"),
        )
    }

    @Test
    fun textSmoothedAsTheGaspTableSaysIsSmoothedAtEverySizeAndLcdSmoothingStays() {
        val key = RenderingHints.KEY_TEXT_ANTIALIASING
        val defaults = UIManager.getLookAndFeelDefaults()
        val before = defaults[key]
        try {
            defaults[key] = RenderingHints.VALUE_TEXT_ANTIALIAS_GASP
            smoothTextAtEverySize()
            assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON, UIManager.get(key))
            UIManager.put(key, null)
            defaults[key] = RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
            smoothTextAtEverySize()
            assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB, UIManager.get(key))
        } finally {
            UIManager.put(key, null)
            defaults[key] = before
        }
    }
}
