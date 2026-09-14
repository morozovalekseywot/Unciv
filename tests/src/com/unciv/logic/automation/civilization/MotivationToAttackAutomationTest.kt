package com.unciv.logic.automation.civilization

import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.victoryscreen.RankingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class MotivationToAttackAutomationTest {

    private lateinit var testGame: TestGame

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(6)
    }

    @Test
    fun `friendly territory strength bonuses increase defensive military might at configured weight`() {
        val attacker = testGame.addCiv()
        val defender = testGame.addCiv(
            "[+20]% Strength <for [All] units> <when fighting in [Friendly Land] tiles>",
            "[+15]% Strength <for [All] units> <when fighting in [Friendly Land] tiles>",
        )
        testGame.addCity(defender, testGame.getTile(HexCoord.Zero))
        testGame.addDefaultMeleeUnitWithUniques(defender, testGame.getTile(1, 0))

        val militaryMight = defender.getStatForRanking(RankingType.Force).toFloat()
        val defensiveMight = MotivationToAttackAutomation.getDefensiveMilitaryMight(attacker, defender)

        assertEquals(militaryMight * 1.175f, defensiveMight, 0.001f)
    }

    @Test
    fun `base ruleset friendly territory bonuses from all sources stack`() {
        val attacker = testGame.addCiv()
        val defender = testGame.addCiv(testGame.ruleset.nations["Shoshone"]!!)
        val city = testGame.addCity(defender, testGame.getTile(HexCoord.Zero))
        city.cityConstructions.addBuilding("Himeji Castle")
        defender.policies.adopt(testGame.ruleset.policies["Nationalism"]!!, branchCompletion = true)
        testGame.addReligion(defender).addBelief("Defender of the Faith")
        testGame.addDefaultMeleeUnitWithUniques(defender, testGame.getTile(1, 0))

        val militaryMight = defender.getStatForRanking(RankingType.Force).toFloat()
        val defensiveMight = MotivationToAttackAutomation.getDefensiveMilitaryMight(attacker, defender)

        assertEquals(militaryMight * 1.325f, defensiveMight, 0.001f)
    }

    @Test
    fun `friendly territory strength bonus respects its unit filter`() {
        val attacker = testGame.addCiv()
        val defender = testGame.addCiv(
            "[+20]% Strength <for [Ranged] units> <when fighting in [Friendly Land] tiles>",
        )
        testGame.addCity(defender, testGame.getTile(HexCoord.Zero))
        val melee = testGame.addDefaultMeleeUnitWithUniques(defender, testGame.getTile(1, 0))
        val ranged = testGame.addDefaultRangedUnitWithUniques(defender, testGame.getTile(0, 1))

        val meleeForce = melee.getForceEvaluation().toFloat()
        val rangedForce = ranged.getForceEvaluation().toFloat()
        val applicableForceFraction = rangedForce / (meleeForce + rangedForce)
        val militaryMight = defender.getStatForRanking(RankingType.Force).toFloat()
        val defensiveMight = MotivationToAttackAutomation.getDefensiveMilitaryMight(attacker, defender)

        assertEquals(militaryMight * (1f + 0.1f * applicableForceFraction), defensiveMight, 0.001f)
    }

    @Test
    fun `mods can disable friendly territory strength bonus valuation`() {
        val attacker = testGame.addCiv()
        val defender = testGame.addCiv(
            "[+20]% Strength <for [All] units> <when fighting in [Friendly Land] tiles>",
        )
        testGame.addCity(defender, testGame.getTile(HexCoord.Zero))
        testGame.addDefaultMeleeUnitWithUniques(defender, testGame.getTile(1, 0))
        testGame.ruleset.modOptions.constants.aiFriendlyTerritoryStrengthBonusWeight = 0f

        val militaryMight = defender.getStatForRanking(RankingType.Force).toFloat()
        val defensiveMight = MotivationToAttackAutomation.getDefensiveMilitaryMight(attacker, defender)

        assertEquals(militaryMight, defensiveMight, 0.001f)
    }

    @Test
    fun `city defending strength bonuses increase estimated city strength`() {
        val defender = testGame.addCiv("[+33]% Strength for cities <when defending>")
        val city = testGame.addCity(defender, testGame.getTile(HexCoord.Zero))

        val baseStrength = CityCombatant(city).getCityStrength().toFloat()
        val defensiveStrength = MotivationToAttackAutomation.getDefendingCityStrength(city)

        assertEquals(baseStrength * 1.33f, defensiveStrength, 0.001f)
    }

    @Test
    fun `defensive military might is used by every war plan evaluation`() {
        val attacker = testGame.addCiv()
        val defender = testGame.addCiv(
            "[+200]% Strength <for [All] units> <when fighting in [Friendly Land] tiles>",
        )
        val ally = testGame.addCiv()
        prepareWarPlanCivilizations(attacker, defender, ally)

        testGame.ruleset.modOptions.constants.aiFriendlyTerritoryStrengthBonusWeight = 0f
        val teamWithoutBonus = DeclareWarPlanEvaluator.evaluateTeamWarPlan(attacker, defender, ally, 20f)
        val joinWithoutBonus = DeclareWarPlanEvaluator.evaluateJoinWarPlan(attacker, defender, ally, 20f)
        val requestHelpWithoutBonus = DeclareWarPlanEvaluator.evaluateJoinOurWarPlan(attacker, defender, ally, 0f)

        testGame.ruleset.modOptions.constants.aiFriendlyTerritoryStrengthBonusWeight = 1f
        val teamWithBonus = DeclareWarPlanEvaluator.evaluateTeamWarPlan(attacker, defender, ally, 20f)
        val joinWithBonus = DeclareWarPlanEvaluator.evaluateJoinWarPlan(attacker, defender, ally, 20f)
        val requestHelpWithBonus = DeclareWarPlanEvaluator.evaluateJoinOurWarPlan(attacker, defender, ally, 0f)

        assertTrue(teamWithBonus < teamWithoutBonus)
        assertTrue(joinWithBonus < joinWithoutBonus)
        assertTrue(requestHelpWithBonus > requestHelpWithoutBonus)
    }

    @Test
    fun `friendly territory bonuses reduce motivation to attack`() {
        val attacker = testGame.addCiv()
        val defender = testGame.addCiv(
            "[+200]% Strength <for [All] units> <when fighting in [Friendly Land] tiles>",
        )
        val attackerCity = testGame.addCity(attacker, testGame.getTile(-4, 0))
        val defenderCity = testGame.addCity(defender, testGame.getTile(HexCoord.Zero))
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        defenderCity.getCenterTile().setExplored(attacker, true)
        val attackerUnitTiles = listOf(
            HexCoord(-3, 0), HexCoord(-4, 1), HexCoord(-5, 1), HexCoord(-5, 0), HexCoord(-3, 1),
        )
        val defenderUnitTiles = listOf(HexCoord(1, 0), HexCoord(0, 1), HexCoord(-1, 1), HexCoord(0, -1))
        for (tile in attackerUnitTiles)
            testGame.addUnit("Warrior", attacker, testGame.getTile(tile))
        for (tile in defenderUnitTiles)
            testGame.addUnit("Warrior", defender, testGame.getTile(tile))

        testGame.ruleset.modOptions.constants.aiFriendlyTerritoryStrengthBonusWeight = 0f
        val motivationWithoutBonuses = MotivationToAttackAutomation.hasAtLeastMotivationToAttack(
            attacker, defender, -1000f,
        )

        testGame.ruleset.modOptions.constants.aiFriendlyTerritoryStrengthBonusWeight = 1f
        val motivationWithTerritoryBonus = MotivationToAttackAutomation.hasAtLeastMotivationToAttack(
            attacker, defender, -1000f,
        )

        assertTrue(attackerCity.neighboringCities.contains(defenderCity))
        assertTrue(motivationWithTerritoryBonus < motivationWithoutBonuses)
    }

    private fun prepareWarPlanCivilizations(
        attacker: Civilization,
        defender: Civilization,
        ally: Civilization,
    ) {
        testGame.addCity(attacker, testGame.getTile(-5, 0))
        testGame.addCity(defender, testGame.getTile(HexCoord.Zero))
        testGame.addCity(ally, testGame.getTile(5, 0))

        testGame.addUnit("Warrior", attacker, testGame.getTile(-4, 0))
        testGame.addUnit("Warrior", attacker, testGame.getTile(-4, 1))
        testGame.addUnit("Warrior", ally, testGame.getTile(4, 0))
        testGame.addUnit("Warrior", ally, testGame.getTile(4, 1))
        val defenderUnitTiles = listOf(
            HexCoord(1, 0), HexCoord(0, 1), HexCoord(-1, 1), HexCoord(-1, 0), HexCoord(0, -1),
            HexCoord(1, -1), HexCoord(2, 0), HexCoord(0, 2), HexCoord(-2, 2), HexCoord(-2, 0),
        )
        for (tile in defenderUnitTiles)
            testGame.addUnit("Warrior", defender, testGame.getTile(tile))

        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        attacker.diplomacyFunctions.makeCivilizationsMeet(ally)
        ally.diplomacyFunctions.makeCivilizationsMeet(defender)
        attacker.getDiplomacyManager(ally)!!.signDeclarationOfFriendship()
        attacker.getDiplomacyManager(ally)!!.signDefensivePact(30)
    }
}
