package com.unciv.logic

import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*

@RunWith(BaseTestRunner::class)
class ShoshoneStartTest {
    private fun startGame(era: String = "Ancient era", shoshoneIsHuman: Boolean = true): GameInfo {
        if (RulesetCache.isEmpty()) RulesetCache.loadRulesets(noMods = true)
        UncivGame.Current = UncivGame()
        val settings = spy(GameSettings())
        doNothing().`when`(settings).save()
        UncivGame.Current.settings = settings
        val parameters = GameParameters().apply {
            startingEra = era
            numberOfCityStates = 0
            noBarbarians = true
            players.clear()
            players.add(Player("Shoshone", if (shoshoneIsHuman) PlayerType.Human else PlayerType.AI))
            players.add(Player("Rome", if (shoshoneIsHuman) PlayerType.AI else PlayerType.Human))
        }
        val map = MapParameters().apply {
            mapSize = MapSize.Tiny
            seed = 42L
        }
        return GameStarter.startNewGame(GameSetupInfo(parameters, map))
    }

    @Test
    fun `ancient Shoshone start with a full strength Pathfinder while Rome keeps its Warrior`() {
        val game = startGame()
        val shoshone = game.getCivilization("Shoshone")
        val units = shoshone.units.getCivUnits().toList()
        val pathfinder = units.single { it.name == "Pathfinder" }
        assertFalse(units.any { it.name == "Warrior" })
        assertTrue(units.any { it.name == "Settler" })
        assertEquals(8, pathfinder.baseUnit.strength)
        assertEquals(45, pathfinder.baseUnit.cost)
        assertEquals(2, pathfinder.getMaxMovement())
        assertEquals(2, pathfinder.getVisibilityRange())
        assertTrue(pathfinder.hasUnique(UniqueType.ChooseRuinsReward))
        assertTrue("Ignore terrain cost" in pathfinder.promotions.promotions)
        assertEquals("Warrior", shoshone.getEquivalentUnit("Warrior").name)
        assertTrue(game.getCivilization("Rome").units.getCivUnits().any { it.name == "Warrior" })
    }

    @Test
    fun `AI Shoshone also receive Pathfinders at the ancient start`() {
        val units = startGame(shoshoneIsHuman = false).getCivilization("Shoshone").units.getCivUnits().toList()
        assertTrue(units.any { it.name == "Pathfinder" })
        assertFalse(units.any { it.name == "Warrior" })
    }

    @Test
    fun `later era start keeps its era appropriate military units`() {
        val units = startGame("Classical era").getCivilization("Shoshone").units.getCivUnits().toList()
        assertTrue(units.any { it.name == "Spearman" })
        assertFalse(units.any { it.name == "Pathfinder" })
    }
}
