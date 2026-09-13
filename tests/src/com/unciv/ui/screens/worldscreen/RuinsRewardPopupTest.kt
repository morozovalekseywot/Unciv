package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.unciv.dev.FontDesktop
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.RuinReward
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.ActivationListener
import com.unciv.ui.images.ImageGetter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*

@RunWith(GdxTestRunner::class)
class RuinsRewardPopupTest {
    private fun labels(actor: Actor): List<String> {
        if (actor is Label) return listOf(actor.text.toString())
        if (actor !is Group) return emptyList()
        return actor.children.flatMap { labels(it) }
    }

    private fun withPopup(civ: Civilization, check: (RuinsRewardPopup) -> Unit) {
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(civ.gameInfo.ruleset)
        val stage = Stage(ExtendViewport(360f, 640f), mock(Batch::class.java))
        stage.viewport.update(360, 640, true)
        val screen = mock(WorldScreen::class.java)
        `when`(screen.stage).thenReturn(stage)
        `when`(screen.viewingCiv).thenReturn(civ)
        `when`(screen.canChangeState).thenReturn(true)
        try {
            val popup = RuinsRewardPopup(screen, civ.ruinsManager.getPendingChoice()!!)
            stage.addActor(popup)
            popup.isVisible = true
            popup.validate()
            check(popup)
        } finally {
            stage.dispose()
        }
    }

    @Test
    fun `displayed gold and culture match actual rewards on every game speed`() {
        for (speed in listOf("Quick", "Standard", "Epic", "Marathon")) {
            for (stat in listOf("Gold", "Culture")) {
                val game = TestGame()
                game.setSpeed(speed)
                game.makeHexagonalMap(4)
                val civ = game.addCiv(isPlayer = true)
                game.ruleset.ruinRewards.clear()
                val reward = RuinReward()
                reward.name = "Test reward"
                val gain = if (stat == "Gold") "Gain [50]-[100] [Gold]" else "Gain [20] [Culture]"
                reward.uniques.add("$gain <(modified by game speed)>")
                game.ruleset.ruinRewards[reward.name] = reward
                val unit = civ.units.placeUnitNearTile(HexCoord(1, 0), "Pathfinder")!!
                civ.ruinsManager.selectNextRuinsReward(unit)
                val goldBefore = civ.gold
                val cultureBefore = civ.policies.storedCulture
                var displayedAmount = -1
                repeat(2) {
                    withPopup(civ) { popup ->
                        val description = labels(popup).single { it.startsWith("$stat: ") }
                        val amount = description.substringAfter(": ").toInt()
                        if (displayedAmount >= 0) assertEquals(displayedAmount, amount)
                        displayedAmount = amount
                    }
                }
                assertEquals(goldBefore, civ.gold)
                assertEquals(cultureBefore, civ.policies.storedCulture)
                assertTrue(civ.ruinsManager.chooseReward(civ.ruinsManager.getPendingChoice()!!, reward.name))
                val received = if (stat == "Gold") civ.gold - goldBefore else civ.policies.storedCulture - cultureBefore
                assertEquals("$stat at $speed", displayedAmount, received)
                if (stat == "Culture") {
                    val expected = mapOf("Quick" to 13, "Standard" to 20, "Epic" to 30, "Marathon" to 60)
                    assertEquals(expected[speed], received)
                }
            }
        }
    }

    @Test
    fun `technology and map use readable descriptions instead of technical uniques`() {
        val game = TestGame()
        game.makeHexagonalMap(6)
        val civ = game.addCiv(isPlayer = true)
        val unit = civ.units.placeUnitNearTile(HexCoord.Zero, "Pathfinder")!!
        civ.ruinsManager.selectNextRuinsReward(unit)
        withPopup(civ) { popup ->
            val descriptions = labels(popup)
            assertTrue(descriptions.any { it.contains("Grants one random researchable technology for free.") })
            assertTrue(descriptions.any { it.contains("Some tiles remain unexplored.") })
            assertFalse(descriptions.any { it.contains("80%") || it.contains("Tech(s)") })
        }
    }

    @Test
    fun `Russian descriptions have natural grammar and no raw effect templates`() {
        val game = TestGame()
        UncivGame.Current.settings.language = "Russian"
        UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
        game.makeHexagonalMap(6)
        val civ = game.addCiv(isPlayer = true)
        val unit = civ.units.placeUnitNearTile(HexCoord.Zero, "Pathfinder")!!
        civ.ruinsManager.selectNextRuinsReward(unit)
        withPopup(civ) { popup ->
            val descriptions = labels(popup)
            assertTrue(descriptions.any { it.startsWith("Культура: ") })
            assertTrue(descriptions.any { it.startsWith("Золото: ") })
            assertTrue(descriptions.any { it.contains("одну случайную доступную технологию. Эпоха:") })
            assertTrue(descriptions.any { it.contains("Некоторые клетки остаются неисследованными.") })
            assertFalse(descriptions.any { it.contains("1 бесплатных") || it.contains("80%") })
        }
    }

    @Test
    fun `modded conditions and map parameters remain visible`() {
        val game = TestGame()
        game.makeHexagonalMap(6)
        val civ = game.addCiv(isPlayer = true)
        game.ruleset.ruinRewards.clear()
        val reward = RuinReward()
        reward.name = "Modded reward"
        reward.uniques.add("Gain [75] [Gold] <for [Land] units>")
        reward.uniques.add("From a randomly chosen tile [3] tiles away from the ruins, reveal tiles up to [2] tiles away with [100]% chance")
        game.ruleset.ruinRewards[reward.name] = reward
        val unit = civ.units.placeUnitNearTile(HexCoord.Zero, "Pathfinder")!!
        civ.ruinsManager.selectNextRuinsReward(unit)
        withPopup(civ) { popup ->
            val descriptions = labels(popup)
            assertTrue(descriptions.any { it.contains("Land") })
            assertTrue(descriptions.any { it.contains("100%") })
            assertFalse(descriptions.any { it.contains("Some tiles remain unexplored.") })
        }
    }

    private fun buttons(actor: Actor): List<TextButton> {
        if (actor is TextButton) return listOf(actor)
        if (actor !is Group) return emptyList()
        return actor.children.flatMap { buttons(it) }
    }

    @Test
    fun `reward buttons show the right icons independently of language and reward name`() {
        for (language in listOf("English", "Russian")) {
            val game = TestGame()
            UncivGame.Current.settings.language = language
            UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
            game.makeHexagonalMap(4)
            val civ = game.addCiv(isPlayer = true)
            game.addCity(civ, game.getTile(HexCoord.Zero))
            game.ruleset.ruinRewards.clear()
            val effects = linkedMapOf(
                "Culture" to "Gain [20] [Culture]",
                "Gold" to "Gain [50]-[100] [Gold]",
                "Science" to "[1] free random researchable Tech(s) from the [Ancient era]",
                "Population" to "[+1] population in a random city"
            )
            for ((index, effect) in effects.values.withIndex()) {
                val reward = RuinReward()
                reward.name = "Renamed reward $index"
                reward.uniques.add(effect)
                game.ruleset.ruinRewards[reward.name] = reward
            }
            val unit = civ.units.placeUnitNearTile(HexCoord(1, 0), "Pathfinder")!!
            civ.ruinsManager.selectNextRuinsReward(unit)
            withPopup(civ) { popup ->
                val rewardButtons = buttons(popup)
                assertEquals(4, rewardButtons.size)
                for ((button, iconName) in rewardButtons.zip(effects.keys)) {
                    val path = "StatIcons/$iconName"
                    assertTrue("Missing icon $path", ImageGetter.imageExists(path))
                    val icon = button.children.filterIsInstance<Image>().single()
                    assertSame(ImageGetter.getDrawable(path), icon.drawable)
                    assertTrue(icon.width > 0f && icon.height > 0f)
                    assertTrue(icon.x >= 0f && icon.x + icon.width <= button.label.x)
                    assertTrue(button.label.x + button.label.width <= button.width)
                }
            }
        }
    }

    @Test
    fun `popup wraps long rewards on a small screen and grants the clicked reward`() {
        val game = TestGame()
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        game.makeHexagonalMap(4)
        val civ = game.addCiv(isPlayer = true)
        game.ruleset.ruinRewards.clear()
        val reward = RuinReward()
        reward.name = "A very long reward name that must wrap instead of overflowing a narrow phone screen"
        reward.uniques.add("Gain [75] [Gold]")
        game.ruleset.ruinRewards[reward.name] = reward
        val unit = civ.units.placeUnitNearTile(HexCoord.Zero, "Pathfinder")!!
        civ.ruinsManager.selectNextRuinsReward(unit)
        val stage = Stage(ExtendViewport(360f, 640f), mock(Batch::class.java))
        stage.viewport.update(360, 640, true)
        val screen = mock(WorldScreen::class.java)
        `when`(screen.stage).thenReturn(stage)
        `when`(screen.viewingCiv).thenReturn(civ)
        `when`(screen.canChangeState).thenReturn(true)
        try {
            val popup = RuinsRewardPopup(screen, civ.ruinsManager.getPendingChoice()!!)
            stage.addActor(popup)
            popup.isVisible = true
            popup.validate()
            val button = buttons(popup).single()
            assertTrue(button.width > 0f && button.width < stage.width)
            assertTrue(button.height > button.label.style.font.lineHeight)
            val icon = button.children.filterIsInstance<Image>().single()
            assertSame(ImageGetter.getDrawable("StatIcons/Gold"), icon.drawable)
            assertTrue(icon.x + icon.width <= button.label.x)
            assertTrue(button.label.x + button.label.width <= button.width)
            val oldGold = civ.gold
            val tap = InputEvent()
            tap.listenerActor = button
            button.listeners.filterIsInstance<ActivationListener>().single().tap(tap, 0f, 0f, 1, 0)
            assertEquals(oldGold + 75, civ.gold)
            assertFalse(civ.ruinsManager.hasPendingChoice())
            assertNull(popup.parent)
        } finally {
            stage.dispose()
        }
    }
}
