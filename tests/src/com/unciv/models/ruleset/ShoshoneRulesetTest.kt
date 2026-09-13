package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.logic.map.HexCoord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class ShoshoneRulesetTest {

    private lateinit var ruleset: Ruleset

    @Before
    fun loadRuleset() {
        if (RulesetCache.isEmpty())
            RulesetCache.loadRulesets(noMods = true)
        ruleset = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
    }

    @Test
    fun `Great Expanse has extra land and a friendly territory combat bonus`() {
        val shoshone = ruleset.nations["Shoshone"]!!

        assertTrue(shoshone.startBias.isEmpty())
        assertTrue("[8] additional tiles when founding a city" in shoshone.uniques)
        assertTrue(
            "[+15]% Strength <for [{Land} {Military} {non-[Helicopter]}] units> <when fighting in [Friendly Land] tiles>" in shoshone.uniques
        )
    }

    @Test
    fun `Comanche Riders are cheaper Cavalry with one additional movement`() {
        val comancheRiders = ruleset.units["Comanche Riders"]!!
        val ability = ruleset.unitPromotions["[Comanche Riders] ability"]!!

        assertEquals("Cavalry", comancheRiders.replaces)
        assertEquals(4, comancheRiders.movement)
        assertEquals(34, comancheRiders.strength)
        assertEquals(200, comancheRiders.cost)
        assertEquals("Horses", comancheRiders.requiredResource)
        assertTrue("[+1] Movement" in ability.uniques)
        assertFalse("Double movement in [Plains]" in ability.uniques)
    }

    @Test
    fun `Comanche movement bonus survives upgrading to a Landship`() {
        val game = TestGame()
        game.makeHexagonalMap(3)
        val civ = game.addCiv(isPlayer = true)
        val riders = civ.units.placeUnitNearTile(HexCoord.Zero, "Comanche Riders")!!
        assertEquals(5, riders.getMaxMovement())

        riders.upgrade.performUpgrade(game.ruleset.units["Landship"]!!, isFree = true)

        val landship = game.getTile(HexCoord.Zero).militaryUnit!!
        assertEquals("Landship", landship.name)
        assertEquals(landship.baseUnit.movement + 1, landship.getMaxMovement())
    }
}
