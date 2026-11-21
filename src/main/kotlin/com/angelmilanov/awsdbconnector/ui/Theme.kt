package com.angelmilanov.awsdbconnector.ui

import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.util.Platform
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Paint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.math.roundToInt

object Theme {
    private var mode = ThemeMode.SYSTEM
    private var dark: Boolean? = null

    /** Installs the look and feel. Safe to call repeatedly; open windows are restyled. Must run on the EDT. */
    fun apply(newMode: ThemeMode) {
        mode = newMode
        val wantDark = when (newMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> Platform.systemIsDark()
        }
        if (wantDark == dark) return
        val first = dark == null
        dark = wantDark
        FlatLaf.setGlobalExtraDefaults(mapOf("@accentColor" to "#3b82f6"))
        if (wantDark) FlatDarkLaf.setup() else FlatLightLaf.setup()
        UIManager.put("Button.arc", 10)
        UIManager.put("Component.arc", 10)
        UIManager.put("TextComponent.arc", 10)
        UIManager.put("CheckBox.arc", 4)
        UIManager.put("ScrollBar.showButtons", false)
        UIManager.put("ScrollBar.width", 12)
        UIManager.put("Table.showHorizontalLines", true)
        UIManager.put("Table.showVerticalLines", false)
        UIManager.put("Table.intercellSpacing", java.awt.Dimension(0, 1))
        UIManager.put("TabbedPane.tabHeight", 40)
        UIManager.put("TabbedPane.showTabSeparators", false)
        UIManager.put("TitlePane.unifiedBackground", true)
        UIManager.put("PopupMenu.borderCornerRadius", 8)
        if (!first) FlatLaf.updateUI()
    }

    /** Re-reads the OS setting when following the system theme (cheap enough to call on window activation). */
    fun refreshFromSystem() {
        if (mode != ThemeMode.SYSTEM) return
        Thread.ofVirtual().start {
            val systemDark = Platform.systemIsDark()
            if (systemDark != dark) SwingUtilities.invokeLater { apply(ThemeMode.SYSTEM) }
        }
    }

    val isDark: Boolean get() = dark == true

    fun stateColor(state: TunnelState): Color = when (state) {
        TunnelState.CONNECTED -> Color(0x2E, 0xA0, 0x43)
        TunnelState.CONNECTING, TunnelState.RECONNECTING -> Color(0xD2, 0x99, 0x22)
        TunnelState.AUTH_REQUIRED -> Color(0xA3, 0x71, 0xF7)
        TunnelState.ERROR -> Color(0xF8, 0x51, 0x49)
        TunnelState.IDLE -> Color(0x38, 0x8B, 0xFD)
        TunnelState.STOPPED -> Color(0x8B, 0x94, 0x9E)
    }
}

/** Small filled circle used to show a tunnel state. A hollow ring is used for "disconnected". */
class StatusDot(private val color: Color, private val hollow: Boolean = false, private val size: Int = 10) : Icon {
    override fun getIconWidth() = size + 4
    override fun getIconHeight() = size + 4

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.color = color
        if (hollow) {
            g2.stroke = BasicStroke(1.8f)
            g2.draw(Ellipse2D.Float(x + 3f, y + 3f, size - 2f, size - 2f))
        } else {
            g2.fill(Ellipse2D.Float(x + 2f, y + 2f, size.toFloat(), size.toFloat()))
        }
        g2.dispose()
    }

    companion object {
        fun of(state: TunnelState) = StatusDot(Theme.stateColor(state), hollow = state == TunnelState.STOPPED)
    }
}

/** The application and tray icons are drawn in code so they stay crisp at every size and DPI scale. */
object AppIcon {
    private val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
    private val tileTop = Color(0x4F, 0x8F, 0xF7)
    private val tileBottom = Color(0x1B, 0x44, 0xC4)

    fun windowIcons(): List<Image> = sizes.map { app(it) }

    /** The application icon for use inside the UI; multi resolution so it is sharp at every screen scale. */
    fun logo(size: Int): Image =
        BaseMultiResolutionImage(*listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).map { app((size * it).roundToInt()) }.toTypedArray())

    /** Multi resolution image so the tray picks the variant matching the screen scale. */
    fun tray(badge: Color?): Image = BaseMultiResolutionImage(*sizes.take(7).map { render(it, badge) }.toTypedArray())

    /**
     * The application icon: a database on a blue tile, with a "connection" mark where there is room for it.
     * It is also what the native packages use for the launcher, shortcuts and installers (see the packageIcons task).
     * [margin] is the empty border as a fraction of [size]: macOS expects one around its icons, Windows and Linux do not.
     */
    fun app(size: Int, margin: Float = 0f): BufferedImage = draw(size) { g, s ->
        val m = s * margin
        val t = s - 2 * m // tile size
        val tile = GradientPaint(0f, m, tileTop, 0f, m + t, tileBottom)
        g.paint = tile
        g.fill(RoundRectangle2D.Float(m, m, t, t, t * 0.45f, t * 0.45f))

        val detailed = t >= 32 // small sizes only have room for the database
        val w = t * if (detailed) 0.50f else 0.58f
        val h = t * if (detailed) 0.60f else 0.68f
        val x = m + (t - w) / 2f - if (detailed) t * 0.05f else 0f
        val top = m + (t - h) / 2f - if (detailed) t * 0.04f else 0f
        cylinder(
            g, x, top, w, h, eh = w * 0.36f,
            body = GradientPaint(x, 0f, Color.WHITE, x + w, 0f, Color(0xD6, 0xE4, 0xFF)),
            bands = Color(0x2A, 0x62, 0xE0), bandWidth = maxOf(1f, t / 24f), lid = Color(0xBF, 0xD7, 0xFF),
        )

        if (detailed) {
            // Two opposite arrows on a disc: traffic flowing to and from the database.
            val r = t * 0.185f
            val cx = m + t * 0.715f
            val cy = m + t * 0.715f
            val gap = t * 0.04f
            g.paint = tile
            g.fill(Ellipse2D.Float(cx - r - gap, cy - r - gap, 2 * (r + gap), 2 * (r + gap)))
            g.paint = GradientPaint(0f, cy - r, Color(0xFF, 0xB0, 0x2E), 0f, cy + r, Color(0xF5, 0x7C, 0x00))
            g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
            g.color = Color.WHITE
            g.stroke = BasicStroke(maxOf(1.2f, t * 0.036f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val len = r * 0.50f // half length of an arrow
            val head = r * 0.30f
            for (dir in listOf(1f, -1f)) {
                val ay = cy - dir * r * 0.30f
                val arrow = Path2D.Float()
                arrow.moveTo(cx - dir * len, ay)
                arrow.lineTo(cx + dir * len, ay)
                arrow.moveTo(cx + dir * (len - head), ay - head)
                arrow.lineTo(cx + dir * len, ay)
                arrow.lineTo(cx + dir * (len - head), ay + head)
                g.draw(arrow)
            }
        }
    }

    /** The tray icon: the bare database, with a status [badge] when there is something to report. */
    fun render(size: Int, badge: Color?): BufferedImage = draw(size) { g, s ->
        val w = s * 0.78f
        val x = (s - w) / 2f
        cylinder(
            g, x, s * 0.06f, w, s * 0.88f, eh = s * 0.30f,
            body = GradientPaint(x, 0f, Color(0x25, 0x63, 0xEB), x + w, 0f, Color(0x1D, 0x4E, 0xD8)),
            bands = Color(255, 255, 255, 150), bandWidth = maxOf(1f, s / 18f), lid = Color(0x93, 0xC5, 0xFD),
        )
        if (badge != null) {
            val d = s * 0.50f
            val bx = s - d
            val by = s - d
            val ring = maxOf(1f, s / 16f)
            g.composite = AlphaComposite.Clear
            g.fill(Ellipse2D.Float(bx - ring, by - ring, d + 2 * ring, d + 2 * ring))
            g.composite = AlphaComposite.SrcOver
            g.color = badge
            g.fill(Ellipse2D.Float(bx, by, d, d))
        }
    }

    private fun draw(size: Int, paint: (Graphics2D, Float) -> Unit): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        paint(g, size.toFloat())
        g.dispose()
        return img
    }

    /** A database cylinder with two bands; [eh] is the height of its ellipses. */
    private fun cylinder(g: Graphics2D, x: Float, top: Float, w: Float, h: Float, eh: Float, body: Paint, bands: Color, bandWidth: Float, lid: Color) {
        val shape = Path2D.Float()
        shape.moveTo(x, top + eh / 2)
        shape.lineTo(x, top + h - eh / 2)
        shape.curveTo(x, top + h + eh * 0.16f, x + w, top + h + eh * 0.16f, x + w, top + h - eh / 2)
        shape.lineTo(x + w, top + eh / 2)
        shape.closePath()
        g.paint = body
        g.fill(shape)

        g.color = bands
        g.stroke = BasicStroke(bandWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        for (f in listOf(0.36f, 0.64f)) {
            val by = top + h * f
            val band = Path2D.Float()
            val inset = bandWidth / 2 // keeps the ends of the stroke inside the body
            band.moveTo(x + inset, by)
            band.curveTo(x + inset, by + eh * 0.66f, x + w - inset, by + eh * 0.66f, x + w - inset, by)
            g.draw(band)
        }

        g.color = lid
        g.fill(Ellipse2D.Float(x, top, w, eh))
    }
}
