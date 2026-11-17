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
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.SwingUtilities
import javax.swing.UIManager

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

/** The application / tray icon is drawn in code so it stays crisp at every DPI scale. */
object AppIcon {
    private val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

    fun windowIcons(): List<Image> = sizes.map { render(it, null) }

    /** Multi resolution image so the tray picks the variant matching the screen scale. */
    fun tray(badge: Color?): Image = BaseMultiResolutionImage(*sizes.take(7).map { render(it, badge) }.toTypedArray())

    fun render(size: Int, badge: Color?): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val s = size.toFloat()
        val w = s * 0.78f
        val x = (s - w) / 2f
        val top = s * 0.06f
        val h = s * 0.88f
        val eh = s * 0.30f // height of the ellipses

        // Cylinder body
        val body = Path2D.Float()
        body.moveTo(x, top + eh / 2)
        body.lineTo(x, top + h - eh / 2)
        body.curveTo(x, top + h + eh * 0.16f, x + w, top + h + eh * 0.16f, x + w, top + h - eh / 2)
        body.lineTo(x + w, top + eh / 2)
        body.closePath()
        g.paint = GradientPaint(x, 0f, Color(0x25, 0x63, 0xEB), x + w, 0f, Color(0x1D, 0x4E, 0xD8))
        g.fill(body)

        // Bands
        g.color = Color(255, 255, 255, 150)
        g.stroke = BasicStroke(maxOf(1f, s / 18f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        for (f in listOf(0.36f, 0.64f)) {
            val by = top + h * f
            val band = Path2D.Float()
            band.moveTo(x, by)
            band.curveTo(x, by + eh * 0.66f, x + w, by + eh * 0.66f, x + w, by)
            g.draw(band)
        }

        // Top
        g.color = Color(0x93, 0xC5, 0xFD)
        g.fill(Ellipse2D.Float(x, top, w, eh))

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
        g.dispose()
        return img
    }
}
