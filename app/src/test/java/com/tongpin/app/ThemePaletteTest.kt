package com.tongpin.app

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {
    @Test fun everyThemeKeepsTextReadableInBothModes() {
        eachPalette { name, p ->
            listOf(
                "body on paper" to (p.ink to p.paper),
                "body on cards" to (p.ink to p.surface),
                "body on soft panels" to (p.ink to p.soft),
                "secondary text on paper" to (p.muted to p.paper),
                "secondary text on cards" to (p.muted to p.surface),
                "secondary text on soft panels" to (p.muted to p.soft),
                "secondary text on summary panels" to (p.muted to p.hero),
                "action text on paper" to (p.accent to p.paper),
                "action text on cards" to (p.accent to p.surface),
                "action text on selected panels" to (p.accent to p.mint),
                "primary buttons" to (p.onAccent to p.accent),
                "hero text" to (p.onHero to p.hero),
                "hero highlighted text" to (p.gold to p.hero),
                "error panel" to (p.errorInk to p.errorWash),
                "error button" to (p.onError to p.error),
            ).forEach { (role, colors) -> assertContrast("$name: $role", colors.first, colors.second, 4.5) }
        }
    }

    @Test fun categoryAndSelectedPanelsKeepTheirLabelsReadable() {
        eachPalette { name, p ->
            listOf("study" to p.mint, "fitness" to p.apricot, "daily life" to p.blue).forEach { (role, background) ->
                assertContrast("$name: $role label", p.ink, background, 4.5)
                assertContrast("$name: $role supporting text", p.muted, background, 4.5)
            }
        }
    }

    @Test fun chartBarsRemainVisibleAgainstTheHeroBackground() {
        eachPalette { name, p ->
            assertContrast("$name: regular chart bar", p.chart, p.hero, 3.0)
            assertContrast("$name: current day chart bar", p.gold, p.hero, 3.0)
            assertContrast("$name: exported chart bar", p.chart, p.surface, 3.0)
        }
    }

    @Test fun accentChoiceLeavesNeutralReadingSurfacesUnchanged() {
        ThemeStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val palettes = ThemeColor.entries.map { planPalette(it, dark, style) }
                assertEquals("distinct accents, $style dark=$dark", ThemeColor.entries.size, palettes.map { it.accent }.toSet().size)
                assertEquals("neutral page backgrounds, $style dark=$dark", 1, palettes.map { it.paper }.toSet().size)
                assertEquals("neutral card backgrounds, $style dark=$dark", 1, palettes.map { it.surface }.toSet().size)
                assertEquals("neutral summary backgrounds, $style dark=$dark", 1, palettes.map { it.hero }.toSet().size)
                assertEquals("neutral reading text, $style dark=$dark", 1, palettes.map { it.ink }.toSet().size)
            }
        }
    }

    @Test fun styleChoiceChangesSurfacesWithoutReplacingTheChosenAccent() {
        ThemeColor.entries.forEach { color ->
            listOf(false, true).forEach { dark ->
                val palettes = ThemeStyle.entries.map { planPalette(color, dark, it) }
                assertEquals("distinct style backgrounds", ThemeStyle.entries.size, palettes.map { it.paper }.toSet().size)
                assertEquals("distinct style summary backgrounds", ThemeStyle.entries.size, palettes.map { it.hero }.toSet().size)
                assertEquals("chosen color is stable", 1, palettes.map { it.accent }.toSet().size)
                assertEquals(ThemeStyle.entries.toList(), palettes.map { it.style })
            }
        }
    }

    @Test fun existingTwoArgumentPaletteCallsUseSoftStyle() {
        ThemeColor.entries.forEach { color ->
            listOf(false, true).forEach { dark ->
                assertEquals(planPalette(color, dark, ThemeStyle.SOFT), planPalette(color, dark))
            }
        }
    }

    @Test fun lightAndDarkSurfacesKeepTheirExpectedReadingPolarity() {
        ThemeStyle.entries.forEach { style ->
            ThemeColor.entries.forEach { color ->
                val light = planPalette(color, false, style)
                val dark = planPalette(color, true, style)
                assertTrue(luminance(light.paper) > .75)
                assertTrue(luminance(light.hero) > .65)
                assertTrue(luminance(dark.paper) < .025)
                assertTrue(luminance(dark.surface) < .06)
                assertTrue(luminance(light.ink) < luminance(light.paper))
                assertTrue(luminance(dark.ink) > luminance(dark.paper))
            }
        }
    }

    @Test fun storedNamesRoundTripAndOlderBackupsUseTheDefaultColor() {
        assertEquals(listOf("GREEN", "BLUE", "PURPLE", "ROSE", "ORANGE", "GRAPHITE"), ThemeColor.entries.map { it.name })
        ThemeColor.entries.forEach { color -> assertEquals(color, ThemeColor.parse(color.name)) }
        listOf(null, "", "blue", "FUTURE_THEME").forEach { value ->
            assertEquals(ThemeColor.GREEN, ThemeColor.parse(value))
        }
    }

    @Test fun missingOrUnknownStyleUsesSoftWithoutChangingStoredColorCompatibility() {
        ThemeStyle.entries.forEach { style -> assertEquals(style, ThemeStyle.parse(style.name)) }
        listOf(null, "", "paper", "FUTURE_STYLE").forEach { value ->
            assertEquals(ThemeStyle.SOFT, ThemeStyle.parse(value))
        }
        assertTrue(ThemeStyle.PAPER.cardRadius < ThemeStyle.CLEAN.cardRadius)
        assertTrue(ThemeStyle.CLEAN.cardRadius < ThemeStyle.SOFT.cardRadius)
        assertTrue(ThemeStyle.CLEAN.pageGap < ThemeStyle.SOFT.pageGap)
    }

    private fun eachPalette(assertions: (String, PlanPalette) -> Unit) {
        ThemeStyle.entries.forEach { style ->
            ThemeColor.entries.forEach { color ->
                listOf(false, true).forEach { dark -> assertions("${style.name}/${color.name}, dark=$dark", planPalette(color, dark, style)) }
            }
        }
    }

    private fun assertContrast(role: String, foreground: Color, background: Color, minimum: Double) {
        assertEquals("$role foreground is opaque", 1f, foreground.alpha, 0f)
        assertEquals("$role background is opaque", 1f, background.alpha, 0f)
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        val ratio = (max(foregroundLuminance, backgroundLuminance) + .05) /
            (min(foregroundLuminance, backgroundLuminance) + .05)
        assertTrue("$role contrast $ratio must be at least $minimum", ratio >= minimum)
    }

    /** Relative luminance of opaque sRGB colors; independent of Android framework methods. */
    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }
}
