package com.unciv.logic.automation.civilization

import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.victoryscreen.RankingType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class MilitaryProductionEstimateTest {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(6)
    }

    @Test
    fun `base ruleset training buildings add one percent per XP only to their city's production`() {
        val civ = game.addCiv()
        val trainingCity = game.addCity(civ, game.getTile(HexCoord.Zero))
        val otherCity = game.addCity(civ, game.getTile(4, 0))
        assertEquals(civ.getStatForRanking(RankingType.Production).toFloat(),
            MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0f)
        var startingXP = 0
        for (building in listOf("Barracks", "Armory", "Military Academy", "Brandenburg Gate")) {
            trainingCity.cityConstructions.addBuilding(building)
            startingXP += 15
            // Fixed production isolates training quality from building maintenance and tile reassignment.
            trainingCity.cityStats.currentCityStats.production = 20f
            otherCity.cityStats.currentCityStats.production = 80f
            civ.stats.statsForNextTurn.production = 100f

            assertEquals(building, 100f + 20f * startingXP / 100f,
                MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0.001f)
        }
    }

    @Test
    fun `modded XP amounts and coefficient scale the estimate and zero disables it`() {
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        city.cityConstructions.addBuilding(game.createBuilding(
            "New [All] units start with [7] XP [in this city]",
            "New [Military] units start with [18] XP [in this city]",
        ))
        city.cityStats.currentCityStats.production = 40f
        civ.stats.statsForNextTurn.production = 40f

        assertEquals(50f, MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0.001f)
        game.ruleset.modOptions.constants.aiMilitaryProductionPercentPerStartingXP = 0.5f
        assertEquals(45f, MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0.001f)
        game.ruleset.modOptions.constants.aiMilitaryProductionPercentPerStartingXP = 0f
        assertEquals(40f, MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0f)
    }

    @Test
    fun `specialized XP and production buildings do not receive a general training bonus`() {
        val civ = game.addCiv()
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        for (filter in listOf("Ranged", "Mounted", "Water", "Land", "Warrior", "{Military} {Land}")) {
            city.cityConstructions.addBuilding(game.createBuilding(
                "New [$filter] units start with [100] XP [in this city]",
                "[+50]% Production when constructing [$filter] units [in this city]",
            ))
        }
        city.cityConstructions.addBuilding("Kremlin")
        val production = civ.getStatForRanking(RankingType.Production).toFloat()

        assertEquals(production, MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0f)
    }

    @Test
    fun `non-building XP and inactive building bonuses leave the previous estimate unchanged`() {
        val civ = game.addCiv("New [Military] units start with [100] XP [in all cities]")
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        val baseline = civ.getStatForRanking(RankingType.Production).toFloat()
        assertEquals(baseline, MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0f)

        city.cityConstructions.addBuilding(game.createBuilding(
            "New [Military] units start with [50] XP [in this city] <after discovering [Future Tech]>",
            "New [Military] units start with [50] XP [in coastal cities]",
        ))
        val production = civ.getStatForRanking(RankingType.Production).toFloat()
        assertEquals(production, MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0f)
    }

    @Test
    fun `empire-wide building XP applies once to each affected city`() {
        val civ = game.addCiv()
        val firstCity = game.addCity(civ, game.getTile(HexCoord.Zero))
        val secondCity = game.addCity(civ, game.getTile(4, 0))
        firstCity.cityConstructions.addBuilding(game.createBuilding(
            "New [all] units start with [10] XP [in all cities]",
        ))
        firstCity.cityStats.currentCityStats.production = 20f
        secondCity.cityStats.currentCityStats.production = 80f
        civ.stats.statsForNextTurn.production = 100f

        assertEquals(110f, MotivationToAttackAutomation.getMilitaryProductionEstimate(civ), 0.001f)
    }

    @Test
    fun `training quality affects production comparison for both sides`() {
        val attacker = game.addCiv()
        val defender = game.addCiv()
        val attackerCity = game.addCity(attacker, game.getTile(-4, 0))
        val defenderCity = game.addCity(defender, game.getTile(4, 0))
        for (city in listOf(attackerCity, defenderCity)) {
            city.cityConstructions.addBuilding("Barracks")
            city.cityConstructions.addBuilding("Armory")
            city.cityStats.currentCityStats.production = 100f
            city.civ.stats.statsForNextTurn.production = 100f
        }

        assertEquals(0f, MotivationToAttackAutomation.getProductionRatioModifier(attacker, defender), 0f)
        defenderCity.cityConstructions.removeBuilding("Armory")
        defenderCity.cityConstructions.removeBuilding("Barracks")
        defenderCity.cityStats.currentCityStats.production = 100f
        defender.stats.statsForNextTurn.production = 100f

        assertEquals(3f, MotivationToAttackAutomation.getProductionRatioModifier(attacker, defender), 0f)
        assertEquals(-5f, MotivationToAttackAutomation.getProductionRatioModifier(defender, attacker), 0f)
    }
}
