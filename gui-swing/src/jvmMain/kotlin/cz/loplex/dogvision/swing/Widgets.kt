package cz.loplex.dogvision.swing

import com.formdev.flatlaf.util.UIScale
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.text.BreakIterator
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.max
import kotlin.math.min

/** A row as wide as the column it is in and as high as it needs: BoxLayout would otherwise stretch it. */
internal open class Row(layout: LayoutManager = BorderLayout(UIScale.scale(8), 0)) : JPanel(layout) {
    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** Rows one under the other, each as wide as the column. */
internal open class Column : Row() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    /** Adds [components] under what it holds. */
    fun addAll(vararg components: Component) = components.forEach { add(it) }
}

/**
 * Text broken into lines between its [pieces], each kept whole where it fits on a line,
 * so that a share stays with its source's opening and a citation's authors together,
 * and a piece wider than the line on lines of its own,
 * broken at its spaces, and inside a word wider than the line, as the Compose controls and the web page break one;
 * its lines [centred] or from the left, and at most [maxWidth] wide.\
 * Its height follows the width it is given.\
 * A piece that is [link] is drawn as a link, on each line it is broken across,
 * and [onLink] is given it when it is clicked.
 */
internal class WrappedText(private val centred: Boolean = false) : JComponent() {
    var pieces: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    var link: String? = null
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    var onLink: (String) -> Unit = {}

    var maxWidth = Int.MAX_VALUE
        set(value) {
            if (field == value) return
            field = value
            revalidate()
        }

    init {
        lookAsLabel()
        val mouse = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val link = link ?: return
                if (linkAreas().any { it.contains(event.point) }) onLink(link)
            }

            override fun mouseMoved(event: MouseEvent) {
                val overLink = linkAreas().any { it.contains(event.point) }
                cursor = Cursor.getPredefinedCursor(if (overLink) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    override fun updateUI() {
        super.updateUI()
        lookAsLabel()
    }

    private fun lookAsLabel() {
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
        // Measured with the smoothing it is drawn with, which a component without a UI delegate is not given:
        // text smoothed is wider than text that is not, and a line measured without it would run past the edge.
        putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING))
    }

    /**
     * The lines [pieces] take in [width]: each piece whole where it fits on a line,
     * and one wider than [width] on lines of its own, broken at its spaces, with what follows it on the next line;
     * a word wider than [width] is broken between its characters.
     */
    internal fun lines(width: Int): List<String> = laidOut(width).map { it.text }

    private fun laidOut(width: Int): List<WrappedLine> {
        val lines = WrappedLines(width, link, getFontMetrics(font)::stringWidth)
        pieces.forEach(lines::addPiece)
        return lines.finish()
    }

    /** The width the lines are broken in: what it is given once it is laid out, and at most [maxWidth]. */
    private fun textWidth(): Int {
        val whole = getFontMetrics(font).stringWidth(pieces.joinToString(" "))
        val given = if (width > 0) width - insets.left - insets.right else whole
        return max(1, min(min(given, whole), maxWidth))
    }

    override fun getPreferredSize(): Dimension {
        if (isPreferredSizeSet) return super.getPreferredSize()
        val width = min(getFontMetrics(font).stringWidth(pieces.joinToString(" ")), maxWidth)
        val height = lines(textWidth()).size * getFontMetrics(font).height
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val before = if (this.width > 0) lines(textWidth()).size else -1
        super.setBounds(x, y, width, height)
        // Its height is known only once its width is:
        // another pass lays out what is under it, after this one, which would mark it valid again.
        if (lines(textWidth()).size != before) SwingUtilities.invokeLater(::revalidate)
    }

    /** Where [line] starts, on the left. */
    private fun lineX(line: String): Int {
        val inner = width - insets.left - insets.right
        return insets.left + if (centred) (inner - getFontMetrics(font).stringWidth(line)) / 2 else 0
    }

    /** Where [link] is drawn, a part on each line it is broken across, or nothing where it is none of the pieces. */
    private fun linkAreas(): List<Rectangle> {
        val metrics = getFontMetrics(font)
        return laidOut(textWidth()).mapIndexedNotNull { i, line ->
            val link = line.link ?: return@mapIndexedNotNull null
            val x = lineX(line.text) + metrics.stringWidth(line.text.substring(0, link.first))
            val width = metrics.stringWidth(line.text.substring(link))
            Rectangle(x, insets.top + i * metrics.height, width, metrics.height)
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            antialiased(g2)
            g2.font = font
            val text = if (isEnabled) foreground else UIManager.getColor("Label.disabledForeground")
            val metrics = g2.fontMetrics
            laidOut(textWidth()).forEachIndexed { i, line ->
                val x = lineX(line.text)
                val y = insets.top + i * metrics.height + metrics.ascent
                g2.color = text
                val range = line.link
                if (range == null) {
                    g2.drawString(line.text, x, y)
                    return@forEachIndexed
                }
                val link = line.text.substring(range)
                val before = line.text.substring(0, range.first)
                val linkX = x + metrics.stringWidth(before)
                g2.drawString(before, x, y)
                g2.drawString(line.text.substring(range.last + 1), linkX + metrics.stringWidth(link), y)
                g2.color = UIManager.getColor("Component.linkColor") ?: text
                g2.drawString(link, linkX, y)
                g2.fillRect(linkX, y + 1, metrics.stringWidth(link), max(1, UIScale.scale(1)))
            }
        } finally {
            g2.dispose()
        }
    }
}

/** An on-off switch, as Material's Switch in the Compose controls, in the look and feel's accent colour. */
internal class Switch : JToggleButton() {
    init {
        isFocusPainted = false
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension =
        Dimension(UIScale.scale(TRACK_WIDTH + FOCUS_ROOM), UIScale.scale(TRACK_HEIGHT + FOCUS_ROOM))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            antialiased(g2)
            val trackWidth = UIScale.scale(TRACK_WIDTH)
            val trackHeight = UIScale.scale(TRACK_HEIGHT)
            val inset = UIScale.scale(THUMB_INSET)
            val x = (width - trackWidth) / 2
            val y = (height - trackHeight) / 2
            val accent = UIManager.getColor("Component.accentColor") ?: UIManager.getColor("Component.focusColor")
            val off = UIManager.getColor("Component.borderColor") ?: Color.GRAY
            if (!isEnabled) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA)
            g2.color = if (isSelected) accent else off
            g2.fillRoundRect(x, y, trackWidth, trackHeight, trackHeight, trackHeight)
            val thumb = trackHeight - 2 * inset
            g2.color = Color.WHITE
            g2.fillOval(if (isSelected) x + trackWidth - inset - thumb else x + inset, y + inset, thumb, thumb)
            if (isFocusOwner) {
                g2.color = UIManager.getColor("Component.focusColor") ?: accent
                g2.stroke = BasicStroke(UIScale.scale(2f))
                g2.drawRoundRect(x - 1, y - 1, trackWidth + 1, trackHeight + 1, trackHeight + 2, trackHeight + 2)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val TRACK_WIDTH = 36
        const val TRACK_HEIGHT = 20
        const val THUMB_INSET = 3
        const val DISABLED_ALPHA = 0.4f

        /** Around the track, where the focus ring is drawn. */
        const val FOCUS_ROOM = 4
    }
}

/**
 * A 24 x 24 icon of [shape], in its viewport of 24 x 24 as the Compose controls' icons are,
 * turned [degrees] about its centre, in the component's foreground colour.
 */
internal class ShapeIcon(private val shape: Shape, private val degrees: Double = 0.0) : Icon {
    override fun getIconWidth() = UIScale.scale(SIZE)

    override fun getIconHeight() = UIScale.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            antialiased(g2)
            g2.translate(x, y)
            g2.scale(iconWidth / SIZE.toDouble(), iconHeight / SIZE.toDouble())
            g2.rotate(Math.toRadians(degrees), SIZE / 2.0, SIZE / 2.0)
            val enabled = c?.isEnabled ?: true
            g2.color = if (enabled) {
                c?.foreground ?: UIManager.getColor("Label.foreground")
            } else {
                UIManager.getColor("Label.disabledForeground")
            }
            g2.fill(shape)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val SIZE = 24

        /** A chevron pointing down, as ui's EXPAND_ICON: M16.59,8.59 L12,13.17 7.41,8.59 6,10 l6,6 6,-6z. */
        val CHEVRON: Shape = Path2D.Double().apply {
            moveTo(16.59, 8.59)
            lineTo(12.0, 13.17)
            lineTo(7.41, 8.59)
            lineTo(6.0, 10.0)
            lineTo(12.0, 16.0)
            lineTo(18.0, 10.0)
            closePath()
        }

        /** An i in a circle, as ui's INFO_ICON: a ring from 8 to 10 about the centre, a dot and a bar. */
        val INFO: Shape = Path2D.Double(Path2D.WIND_EVEN_ODD).apply {
            append(Ellipse2D.Double(2.0, 2.0, 20.0, 20.0), false)
            append(Ellipse2D.Double(4.0, 4.0, 16.0, 16.0), false)
            append(Rectangle2D.Double(11.0, 7.0, 2.0, 2.0), false)
            append(Rectangle2D.Double(11.0, 11.0, 2.0, 6.0), false)
        }
    }
}

/**
 * Smooth edges, and text as the look and feel smooths it,
 * which [smoothTextAtEverySize] may have changed from the desktop's;
 * as the desktop smooths it where the look and feel does not say, and smoothed where neither says.
 */
internal fun antialiased(g: Graphics2D) {
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    val desktop = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
    if (desktop != null) {
        g.addRenderingHints(desktop)
    } else {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    val lookAndFeel = UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING)
    if (lookAndFeel != null) g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, lookAndFeel)
}

/** A line's [text], and where in it the link, or the part of it on this line, is drawn, if anywhere. */
private class WrappedLine(val text: String, val link: IntRange?)

/**
 * The lines of a [WrappedText] as its pieces fill them, in [width] as [measure] measures a text;
 * [link] is the word drawn as a link, if any.
 */
private class WrappedLines(private val width: Int, private val link: String?, private val measure: (String) -> Int) {
    private val space = measure(" ")
    private val lines = mutableListOf<WrappedLine>()
    private val line = StringBuilder()
    private var lineWidth = 0
    private var lineLink: IntRange? = null

    /** Adds [piece], whole where it fits on a line, and otherwise on lines of its own, broken at its spaces. */
    fun addPiece(piece: String) {
        val pieceWidth = measure(piece)
        if (pieceWidth <= width) return add(piece, pieceWidth)
        breakLine()
        piece.split(' ').forEach(::addWord)
        breakLine()
    }

    /** The lines, the last one ended. */
    fun finish(): List<WrappedLine> {
        breakLine()
        return lines
    }

    /** Adds [word], broken between its characters where it is wider than [width]. */
    private fun addWord(word: String) {
        val wordWidth = measure(word)
        if (wordWidth <= width) return add(word, wordWidth)
        breakLine()
        val parts = parts(word)
        parts.forEachIndexed { i, part ->
            add(part, measure(part), ofLink = word == link)
            if (i < parts.lastIndex) breakLine()
        }
    }

    private fun add(text: String, textWidth: Int, ofLink: Boolean = false) {
        if (line.isNotEmpty() && lineWidth + space + textWidth > width) breakLine()
        if (line.isNotEmpty()) {
            line.append(' ')
            lineWidth += space
        }
        val start = line.length
        line.append(text)
        lineWidth += textWidth
        val range = if (ofLink) text.indices else linkIn(text)
        if (lineLink == null && range != null) lineLink = start + range.first..start + range.last
    }

    private fun breakLine() {
        if (line.isEmpty()) return
        lines += WrappedLine(line.toString(), lineLink)
        line.clear()
        lineWidth = 0
        lineLink = null
    }

    /** Where in [text] [link] stands as a word of its own, or null where it is none of its words. */
    private fun linkIn(text: String): IntRange? {
        val link = link ?: return null
        val at = text.indexOf(link)
        val end = at + link.length
        val whole = at >= 0 && (at == 0 || text[at - 1] == ' ') && (end == text.length || text[end] == ' ')
        return if (whole) at until end else null
    }

    /** [word] in parts each at most [width] wide, broken between its characters; a character wider stays whole. */
    private fun parts(word: String): List<String> {
        val characters = BreakIterator.getCharacterInstance().apply { setText(word) }
        val parts = mutableListOf<String>()
        var start = 0
        var fitting = 0
        var end = characters.next()
        while (end != BreakIterator.DONE) {
            if (fitting > start && measure(word.substring(start, end)) > width) {
                parts += word.substring(start, fitting)
                start = fitting
            }
            fitting = end
            end = characters.next()
        }
        parts += word.substring(start)
        return parts
    }
}
