package cz.loplex.dogvision.desktop

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** The application's icon, the packages' own, for a window's title bar and the desktop's task bar. */
fun windowIcon(): BufferedImage {
    val icon = checkNotNull(WindowIcon::class.java.getResource("dog-vision.png")) { "No dog-vision.png beside it" }
    return ImageIO.read(icon)
}

/** What the icon is looked up beside. */
private object WindowIcon
