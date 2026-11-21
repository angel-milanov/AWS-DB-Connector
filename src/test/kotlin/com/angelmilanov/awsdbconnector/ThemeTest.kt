package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.tunnel.TunnelState
import com.angelmilanov.awsdbconnector.ui.AppIcon
import com.angelmilanov.awsdbconnector.ui.StatusDot
import com.angelmilanov.awsdbconnector.ui.Theme
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import javax.swing.Icon
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThemeTest {
    /** Stands in for the tool that knows the colour scheme of the system, whichever system that is. */
    private val programs = FakePrograms { FakeProcess(exitCode = 1) }

    private fun systemIsDark(dark: Boolean) {
        programs.respond = { if (dark) FakeProcess("AppsUseLightTheme REG_DWORD 0x0 / Dark / 'prefer-dark'") else FakeProcess(exitCode = 1) }
    }

    @AfterTest
    fun cleanup() {
        programs.close()
        onEdt { Theme.apply(ThemeMode.LIGHT) }
    }

    @Test
    fun `the look follows the chosen theme`() = onEdt {
        Theme.apply(ThemeMode.LIGHT)
        assertFalse(Theme.isDark)
        assertTrue(UIManager.getLookAndFeel() is FlatLightLaf)

        Theme.apply(ThemeMode.DARK)
        assertTrue(Theme.isDark)
        val dark = UIManager.getLookAndFeel()
        assertTrue(dark is FlatDarkLaf)
        assertEquals(10, UIManager.get("Button.arc"))

        Theme.apply(ThemeMode.DARK)
        assertSame(dark, UIManager.getLookAndFeel(), "nothing is installed again when nothing changes")
    }

    @Test
    fun `the system theme is followed, also when it changes while the application runs`() {
        systemIsDark(true)
        onEdt { Theme.apply(ThemeMode.SYSTEM) }
        assertTrue(Theme.isDark)

        systemIsDark(false)
        Theme.refreshFromSystem()
        await("the light theme") { !Theme.isDark }
        val asked = programs.commands.size
        Theme.refreshFromSystem()
        await("the system to be asked again") { programs.commands.size > asked }
        assertFalse(Theme.isDark)

        // With a fixed theme the system is not asked at all.
        onEdt { Theme.apply(ThemeMode.DARK) }
        programs.started.clear()
        Theme.refreshFromSystem()
        assertTrue(programs.commands.isEmpty())
        assertTrue(Theme.isDark)
    }

    @Test
    fun `every state has its colour, and reconnecting looks like connecting`() {
        assertEquals(Theme.stateColor(TunnelState.CONNECTING), Theme.stateColor(TunnelState.RECONNECTING))
        assertEquals(TunnelState.entries.size - 1, TunnelState.entries.map(Theme::stateColor).toSet().size)
    }

    private fun paint(icon: Icon): BufferedImage = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB).also {
        val g = it.createGraphics()
        icon.paintIcon(null, g, 0, 0)
        g.dispose()
    }

    private fun BufferedImage.alpha(x: Int, y: Int) = getRGB(x, y) ushr 24

    @Test
    fun `a status is a filled dot, and a ring for disconnected`() {
        val connected = paint(StatusDot.of(TunnelState.CONNECTED))
        assertEquals(14 to 14, connected.width to connected.height)
        assertEquals(Theme.stateColor(TunnelState.CONNECTED), Color(connected.getRGB(7, 7)))

        val disconnected = paint(StatusDot.of(TunnelState.STOPPED))
        assertEquals(0, disconnected.alpha(7, 7), "hollow in the middle")
        assertTrue(disconnected.alpha(7, 3) > 0, "with a ring around it")

        assertEquals(24, StatusDot(Color.RED, size = 20).iconWidth)
    }

    private fun isOrange(rgb: Int) = Color(rgb).let { it.red > 200 && it.green in 100..200 && it.blue < 100 }

    @Test
    fun `the application icon is drawn for every size, with the connection mark where there is room`() {
        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64, 128, 256), AppIcon.windowIcons().map { it.getWidth(null) })

        assertTrue(isOrange(AppIcon.app(64).getRGB(46, 46)), "the mark with the two arrows")
        assertFalse(isOrange(AppIcon.app(16).getRGB(11, 11)), "small icons only show the database")

        val edgeToEdge = AppIcon.app(256)
        val withMargin = AppIcon.app(256, margin = 0.1f)
        assertEquals(255, edgeToEdge.alpha(128, 5))
        assertEquals(0, withMargin.alpha(128, 5), "macOS wants an empty border around its icons")
        assertEquals(255, withMargin.alpha(128, 40))
        assertEquals(0, edgeToEdge.alpha(0, 0), "rounded corners")

        val logo = AppIcon.logo(48) as MultiResolutionImage
        assertEquals(listOf(48, 60, 72, 84, 96, 120, 144), logo.resolutionVariants.map { it.getWidth(null) })
    }

    @Test
    fun `the tray icon carries a badge in the colour of the state`() {
        val red = Theme.stateColor(TunnelState.ERROR)
        val badged = AppIcon.render(32, red)
        assertEquals(red, Color(badged.getRGB(24, 24)))
        assertEquals(0, badged.alpha(15, 24), "a gap separates the badge from the icon")

        val plain = AppIcon.render(32, null)
        assertTrue(plain.alpha(24, 24) > 0 && Color(plain.getRGB(24, 24)) != red)

        assertEquals(listOf(16, 20, 24, 32, 40, 48, 64), (AppIcon.tray(red) as MultiResolutionImage).resolutionVariants.map { it.getWidth(null) })
    }
}
