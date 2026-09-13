package com.unciv.logic.civilization.managers

import com.unciv.json.json
import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.RuinReward
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class RuinsManagerTest {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var unit: MapUnit
    private val ruinPosition = HexCoord(1, 0)

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        civ = game.addCiv(game.ruleset.nations["Shoshone"]!!, isPlayer = true)
        game.gameInfo.currentPlayerCiv = civ
        game.gameInfo.currentPlayer = civ.civID
        unit = civ.units.placeUnitNearTile(HexCoord.Zero, "Pathfinder")!!
    }

    private fun reward(name: String, vararg uniques: String): RuinReward {
        val reward = RuinReward()
        reward.name = name
        reward.uniques.addAll(uniques)
        game.ruleset.ruinRewards[name] = reward
        return reward
    }

    private fun enterRuins() {
        game.getTile(ruinPosition).setImprovement("Ancient ruins")
        unit.movement.moveToTile(game.getTile(ruinPosition))
    }

    @Test
    fun `human Pathfinder waits at ruins and receives exactly the selected reward`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        reward("Culture", "Gain [20] [Culture]")
        val oldGold = civ.gold
        game.getTile(ruinPosition).setImprovement("Ancient ruins")

        unit.movement.moveToTile(game.getTile(2, 0))

        assertEquals(ruinPosition, unit.getTile().position)
        assertNull(game.getTile(ruinPosition).improvement)
        assertEquals(1f, unit.currentMovement)
        assertEquals(oldGold, civ.gold)
        val choice = civ.ruinsManager.getPendingChoice()!!
        unit.movement.moveToTile(game.getTile(2, 0))
        assertEquals(ruinPosition, unit.getTile().position)

        assertTrue(civ.ruinsManager.chooseReward(choice, "Gold"))
        assertFalse(civ.ruinsManager.chooseReward(choice, "Gold"))
        assertEquals(oldGold + 75, civ.gold)
        assertFalse(civ.ruinsManager.hasPendingChoice())
        unit.movement.moveToTile(game.getTile(2, 0))
        assertEquals(HexCoord(2, 0), unit.getTile().position)
    }

    @Test
    fun `AI and ordinary explorers still get an immediate random reward`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        val ai = game.addCiv()
        val aiUnit = ai.units.placeUnitNearTile(HexCoord(-6, 0), "Pathfinder")!!
        val scout = game.addUnit("Scout", civ, game.getTile(3, 0))
        val humanGold = civ.gold
        val aiGold = ai.gold
        civ.ruinsManager.selectNextRuinsReward(scout)
        ai.ruinsManager.selectNextRuinsReward(aiUnit)
        assertFalse(civ.ruinsManager.hasPendingChoice())
        assertFalse(ai.ruinsManager.hasPendingChoice())
        assertEquals(humanGold + 75, civ.gold)
        assertEquals(aiGold + 75, ai.gold)
    }

    @Test
    fun `unavailable rewards are filtered without applying their effects`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        reward("Population without a city", "[+1] population in a random city", "Play [wagon] sound")
        reward("Too early", "Gain [20] [Culture]", "Only available <after turn number [20]>")
        reward("Wrong difficulty", "Gain [20] [Culture]", "Unavailable <on [Prince] difficulty or higher>")
        reward("Wrong unit", "Gain [20] [Gold] <for [Water] units>")
        enterRuins()
        val choice = civ.ruinsManager.getPendingChoice()!!
        val oldGold = civ.gold
        assertEquals(listOf("Gold"), civ.ruinsManager.getAvailableRewards(choice).map { it.name })
        assertEquals(oldGold, civ.gold)
        assertFalse(civ.ruinsManager.chooseReward(choice, "Too early"))
        assertFalse(civ.ruinsManager.chooseReward(choice, "Unknown reward"))
        assertTrue(civ.ruinsManager.hasPendingChoice())
    }

    @Test
    fun `the two most recent rewards are excluded across all explorers`() {
        game.ruleset.ruinRewards.clear()
        for (name in listOf("A", "B", "C")) reward(name, "Gain [10] [Gold]")
        for (name in listOf("A", "B", "C", "A")) {
            civ.ruinsManager.selectNextRuinsReward(unit)
            val choice = civ.ruinsManager.getPendingChoice()!!
            assertTrue(civ.ruinsManager.chooseReward(choice, name))
        }
        val scout = game.addUnit("Scout", civ, game.getTile(-2, 0))
        civ.ruinsManager.selectNextRuinsReward(scout) // only B is available
        civ.ruinsManager.selectNextRuinsReward(unit)
        val choice = civ.ruinsManager.getPendingChoice()!!
        assertEquals(listOf("C"), civ.ruinsManager.getAvailableRewards(choice).map { it.name })
    }

    @Test
    fun `pending choice survives saving and clone remains independent`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        enterRuins()
        val original = civ.ruinsManager
        val clone = original.clone()
        clone.setTransients(civ)
        val restored = json().fromJson(RuinsManager::class.java, json().toJson(original))
        restored.setTransients(civ)
        civ.ruinsManager = restored
        val choice = restored.getPendingChoice()!!
        assertEquals(unit.id, choice.unitId)
        assertEquals(ruinPosition, choice.position)
        assertTrue(restored.chooseReward(choice, "Gold"))
        assertTrue(original.hasPendingChoice())
        assertTrue(clone.hasPendingChoice())
        assertFalse(restored.hasPendingChoice())
    }

    @Test
    fun `old saves without pending choices load normally`() {
        val restored = json().fromJson(RuinsManager::class.java, "{\"lastChosenRewards\":[\"\",\"\"]}")
        restored.setTransients(civ)
        assertFalse(restored.hasPendingChoice())
    }

    @Test
    fun `upgrade reward works with no movement remaining`() {
        game.ruleset.ruinRewards.clear()
        reward("Upgrade", "[This Unit] upgrades for free including special upgrades")
        unit.currentMovement = 1f
        enterRuins()
        assertEquals(0f, unit.currentMovement)
        val choice = civ.ruinsManager.getPendingChoice()!!
        assertTrue(civ.ruinsManager.chooseReward(choice, "Upgrade"))
        val upgraded = game.getTile(ruinPosition).militaryUnit!!
        assertEquals("Composite Bowman", upgraded.name)
        assertTrue(upgraded.hasUnique(UniqueType.ChooseRuinsReward))
        assertTrue("Ignore terrain cost" in upgraded.promotions.promotions)
        assertTrue(upgraded.hasUpgradedFromRuins)
        assertTrue(UnitActionsUpgrade.getAncientRuinsUpgradeAction(upgraded).none())
        assertTrue(upgraded.clone().hasUpgradedFromRuins)
        val restored = json().fromJson(MapUnit::class.java, json().toJson(upgraded))
        assertTrue(restored.hasUpgradedFromRuins)

        // Ordinary upgrades are still allowed and preserve the one-ruins-upgrade limit.
        val upgrade = UnitActionsUpgrade.getFreeUpgradeAction(upgraded).first()
        assertNotNull(upgrade.action)
        upgrade.action!!.invoke()
        val crossbowman = game.getTile(ruinPosition).militaryUnit!!
        assertEquals("Crossbowman", crossbowman.name)
        assertTrue(UnitActionsUpgrade.getAncientRuinsUpgradeAction(crossbowman).none())
        assertTrue(crossbowman.hasUnique(UniqueType.ChooseRuinsReward))
        assertFalse(civ.ruinsManager.hasPendingChoice())
    }

    @Test
    fun `failed placement keeps choice open and does not consume a reward`() {
        game.ruleset.ruinRewards.clear()
        reward("Impossible ship", "Free [Trireme] found in the ruins", "Play [wagon] sound")
        reward("Gold", "Gain [75] [Gold]")
        enterRuins()
        val choice = civ.ruinsManager.getPendingChoice()!!
        assertFalse(civ.ruinsManager.chooseReward(choice, "Impossible ship"))
        assertTrue(civ.ruinsManager.hasPendingChoice())
        assertEquals(listOf("Gold"), civ.ruinsManager.getAvailableRewards(choice).map { it.name })
        assertTrue(civ.ruinsManager.chooseReward(choice, "Gold"))
    }

    @Test
    fun `empty choices can be dismissed but available rewards cannot be discarded`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        enterRuins()
        val choice = civ.ruinsManager.getPendingChoice()!!
        civ.ruinsManager.dismissEmptyChoice(choice)
        assertTrue(civ.ruinsManager.hasPendingChoice())
        game.ruleset.ruinRewards.clear()
        civ.ruinsManager.dismissEmptyChoice(choice)
        assertFalse(civ.ruinsManager.hasPendingChoice())
    }

    @Test
    fun `automated exploration stops for a human decision without spending remaining movement`() {
        game.getTile(ruinPosition).setImprovement("Ancient ruins")
        unit.action = "Explore"
        UnitAutomation.automatedExplore(unit)
        assertEquals(ruinPosition, unit.getTile().position)
        assertTrue(civ.ruinsManager.hasPendingChoice())
        assertEquals(1f, unit.currentMovement)
        unit.doAction()
        assertEquals(ruinPosition, unit.getTile().position)
    }

    @Test
    fun `pending choice prevents advancing the turn and can be resolved automatically`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        enterRuins()
        val turn = game.gameInfo.turns
        game.gameInfo.nextTurn()
        assertEquals(turn, game.gameInfo.turns)
        assertSame(civ, game.gameInfo.currentPlayerCiv)
        val oldGold = civ.gold
        civ.ruinsManager.resolvePendingChoicesAutomatically()
        assertFalse(civ.ruinsManager.hasPendingChoice())
        assertEquals(oldGold + 75, civ.gold)
    }

    @Test
    fun `losing the exploring unit still allows civilization rewards`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        reward("Upgrade", "[This Unit] upgrades for free including special upgrades")
        enterRuins()
        val choice = civ.ruinsManager.getPendingChoice()!!
        unit.destroy()
        assertEquals(listOf("Gold"), civ.ruinsManager.getAvailableRewards(choice).map { it.name })
        assertTrue(civ.ruinsManager.chooseReward(choice, "Gold"))
    }

    @Test
    fun `several explorers resolve their choices in order and recheck reward history`() {
        game.ruleset.ruinRewards.clear()
        reward("Gold", "Gain [75] [Gold]")
        reward("Culture", "Gain [20] [Culture]")
        val otherUnit = civ.units.placeUnitNearTile(HexCoord(-3, 0), "Pathfinder")!!
        enterRuins()
        val first = civ.ruinsManager.getPendingChoice()!!
        civ.ruinsManager.selectNextRuinsReward(otherUnit)
        assertTrue(civ.ruinsManager.hasPendingChoice(unit))
        assertTrue(civ.ruinsManager.hasPendingChoice(otherUnit))
        assertTrue(civ.ruinsManager.chooseReward(first, "Gold"))
        val second = civ.ruinsManager.getPendingChoice()!!
        assertEquals(otherUnit.id, second.unitId)
        assertFalse(civ.ruinsManager.chooseReward(first, "Culture"))
        assertEquals(listOf("Culture"), civ.ruinsManager.getAvailableRewards(second).map { it.name })
        assertTrue(civ.ruinsManager.chooseReward(second, "Culture"))
        assertFalse(civ.ruinsManager.hasPendingChoice())
    }
}
