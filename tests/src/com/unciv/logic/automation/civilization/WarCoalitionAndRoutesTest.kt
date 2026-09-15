package com.unciv.logic.automation.civilization

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.screens.victoryscreen.RankingType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class WarCoalitionAndRoutesTest {
    private lateinit var game: TestGame
    private lateinit var attacker: Civilization
    private lateinit var defender: Civilization
    private lateinit var ally: Civilization

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(12)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Mountain")
        for (x in -7..7) game.setTileTerrain(HexCoord(x, 0), "Plains")
        attacker = game.addCiv()
        defender = game.addCiv()
        ally = game.addCiv()
        game.addCity(attacker, game.getTile(-5, 0))
        game.addCity(defender, game.getTile(0, 0))
        game.addCity(ally, game.getTile(6, 0))
        game.addUnit("Modern Armor", attacker, game.getTile(-4, 0))
        game.addUnit("Modern Armor", ally, game.getTile(5, 0))
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        defender.diplomacyFunctions.makeCivilizationsMeet(ally)
        attacker.diplomacyFunctions.makeCivilizationsMeet(ally)
        defender.getDiplomacyManager(ally)!!.signDefensivePact(30)
        ally.getDiplomacyManager(defender)!!.hasOpenBorders = true
        defender.cities.first().getCenterTile().setExplored(attacker, true)
    }

    private fun support() = MotivationToAttackAutomation.getDefensivePactSupport(attacker, defender)
    private fun allyForce() = ally.getStatForRanking(RankingType.Force).toFloat()

    @Test
    fun `pact ally can help through defender open borders without a shared attacker border`() {
        assertFalse(ally.cities.any { city -> city.getTiles().any { tile ->
            tile.neighbors.any { it.getOwner() == attacker }
        } })
        assertEquals(allyForce(), support(), 0f)
    }

    @Test
    fun `closed defender borders and an impassable corridor prevent support`() {
        ally.getDiplomacyManager(defender)!!.hasOpenBorders = false
        assertEquals(0f, support(), 0f)
        ally.getDiplomacyManager(defender)!!.hasOpenBorders = true
        game.setTileTerrain(HexCoord(3, 0), "Mountain")
        assertEquals(0f, support(), 0f)
    }

    @Test
    fun `open borders are checked in the helping civilization direction`() {
        ally.getDiplomacyManager(defender)!!.hasOpenBorders = false
        defender.getDiplomacyManager(ally)!!.hasOpenBorders = true
        assertEquals(0f, support(), 0f)
    }

    @Test
    fun `amphibious help requires embarkation and contributes half the army`() {
        game.setTileTerrain(HexCoord(3, 0), "Coast")
        assertEquals(0f, support(), 0f)
        ally.tech.unitsCanEmbark = true
        assertEquals(allyForce() * 0.5f, support(), 0f)
        game.setTileTerrain(HexCoord(3, 0), "Ocean")
        assertEquals(0f, support(), 0f)
        ally.tech.embarkedUnitsCanEnterOcean = true
        assertEquals(allyForce() * 0.5f, support(), 0f)
    }

    @Test
    fun `existing enemy is not counted again as pact support`() {
        attacker.getDiplomacyManager(ally)!!.diplomaticStatus = DiplomaticStatus.War
        ally.getDiplomacyManager(attacker)!!.diplomaticStatus = DiplomaticStatus.War
        assertEquals(0f, support(), 0f)
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertNull(report.rejectionReason)
        assertEquals(0.8f * allyForce(), report.inputs.toMap()["Other enemies force adjustment"]!!, 0f)
    }

    @Test
    fun `other wars reduce the army available for pact support`() {
        val otherEnemy = game.addCiv()
        game.setTileTerrain(HexCoord(8, 2), "Plains")
        game.setTileTerrain(HexCoord(8, 3), "Plains")
        game.addCity(otherEnemy, game.getTile(8, 2))
        game.addUnit("Warrior", otherEnemy, game.getTile(8, 3))
        ally.diplomacyFunctions.makeCivilizationsMeet(otherEnemy)
        ally.getDiplomacyManager(otherEnemy)!!.diplomaticStatus = DiplomaticStatus.War
        otherEnemy.getDiplomacyManager(ally)!!.diplomaticStatus = DiplomaticStatus.War
        val expected = (allyForce() - 0.8f * otherEnemy.getStatForRanking(RankingType.Force)).coerceAtLeast(0f)
        assertTrue(expected < allyForce())
        assertEquals(expected, support(), 0f)
    }

    @Test
    fun `attacker pact and indirect pact partners do not count`() {
        defender.getDiplomacyManager(ally)!!.diplomaticStatus = DiplomaticStatus.Peace
        attacker.getDiplomacyManager(defender)!!.signDefensivePact(30)
        attacker.getDiplomacyManager(ally)!!.signDefensivePact(30)
        assertEquals(0f, support(), 0f)
    }

    @Test
    fun `a partner in the proposed attack is not also counted as a defender`() {
        assertTrue(support() > 0f)
        assertEquals(0f, MotivationToAttackAutomation.getDefensivePactSupport(attacker, defender, ally), 0f)
    }

    @Test
    fun `pact support affects all joint war force comparisons`() {
        game.addUnit("Modern Armor", defender, game.getTile(-1, 0))
        game.addUnit("Modern Armor", defender, game.getTile(1, 0))
        val partner = game.addCiv()
        game.setTileTerrain(HexCoord(-7, 3), "Plains")
        game.setTileTerrain(HexCoord(-7, 2), "Plains")
        game.addCity(partner, game.getTile(-7, 3))
        game.addUnit("Warrior", partner, game.getTile(-7, 2))
        attacker.diplomacyFunctions.makeCivilizationsMeet(partner)
        partner.diplomacyFunctions.makeCivilizationsMeet(defender)
        attacker.getDiplomacyManager(partner)!!.signDeclarationOfFriendship()
        attacker.getDiplomacyManager(partner)!!.signDefensivePact(30)

        val supportedTeam = DeclareWarPlanEvaluator.evaluateTeamWarPlan(attacker, defender, partner, 20f)
        val supportedJoin = DeclareWarPlanEvaluator.evaluateJoinWarPlan(attacker, defender, partner, 20f)
        val supportedHelp = DeclareWarPlanEvaluator.evaluateJoinOurWarPlan(attacker, defender, partner, 0f)
        defender.getDiplomacyManager(ally)!!.diplomaticStatus = DiplomaticStatus.Peace
        assertTrue(supportedTeam < DeclareWarPlanEvaluator.evaluateTeamWarPlan(attacker, defender, partner, 20f))
        assertTrue(supportedJoin < DeclareWarPlanEvaluator.evaluateJoinWarPlan(attacker, defender, partner, 20f))
        assertTrue(supportedHelp > DeclareWarPlanEvaluator.evaluateJoinOurWarPlan(attacker, defender, partner, 0f))
    }

    @Test
    fun `a distant connected ally cannot supply immediate reinforcements`() {
        game = TestGame()
        game.makeHexagonalMap(24)
        attacker = game.addCiv()
        defender = game.addCiv()
        ally = game.addCiv()
        game.addCity(attacker, game.getTile(-20, 0))
        game.addCity(defender, game.getTile(0, 0))
        game.addCity(ally, game.getTile(20, 0))
        game.addUnit("Modern Armor", ally, game.getTile(21, 0))
        defender.diplomacyFunctions.makeCivilizationsMeet(ally)
        defender.getDiplomacyManager(ally)!!.signDefensivePact(30)
        ally.getDiplomacyManager(defender)!!.hasOpenBorders = true
        assertEquals(0f, support(), 0f)
    }

    @Test
    fun `support enters combat comparison and report without the old duplicate penalty`() {
        val supported = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        defender.getDiplomacyManager(ally)!!.diplomaticStatus = DiplomaticStatus.Peace
        val alone = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertNull(supported.rejectionReason)
        assertEquals(allyForce(), supported.inputs.toMap()["Defensive pact ally force"]!!, 0f)
        assertEquals(0f, supported.inputs.toMap()["City-state protector force"]!!, 0.001f)
        assertEquals(allyForce(), supported.inputs.toMap()["Defender total combat strength"]!! -
            alone.inputs.toMap()["Defender total combat strength"]!!, 0.001f)
        assertTrue(supported.components.toMap()["Relative combat strength"]!! < alone.components.toMap()["Relative combat strength"]!!)
        assertFalse(supported.components.any { it.first == "Their allies" })
    }

    @Test
    fun `more source cities through one corridor do not increase attack path motivation`() {
        val before = pathModifier()
        game.setTileTerrain(HexCoord(-7, -3), "Plains")
        game.setTileTerrain(HexCoord(-7, -2), "Plains")
        game.setTileTerrain(HexCoord(-7, -1), "Plains")
        game.addCity(attacker, game.getTile(-7, -3))
        val pairs = attacker.cities.map { it to defender.cities.first() }
        assertEquals(2, MotivationToAttackAutomation.getAccessibleAttackTargets(attacker, defender, pairs).size)
        assertEquals(0f, before, 0f)
        assertEquals(before, pathModifier(), 0f)
    }

    @Test
    fun `independent land approaches give at most three motivation`() {
        for (y in listOf(-6, 6)) {
            for (x in -5..5) game.setTileTerrain(HexCoord(x, y), "Plains")
            game.addCity(attacker, game.getTile(-5, y))
            game.addCity(defender, game.getTile(5, y)).getCenterTile().setExplored(attacker, true)
        }
        assertEquals(3f, pathModifier(), 0f)
    }

    @Test
    fun `routes to different cities through one choke point are only one approach`() {
        game = TestGame()
        game.makeHexagonalMap(8)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Mountain")
        for (y in listOf(-3, 3)) {
            for (x in -4..-1) game.setTileTerrain(HexCoord(x, y), "Plains")
            for (x in 1..4) game.setTileTerrain(HexCoord(x, y), "Plains")
        }
        for (y in -3..3) {
            game.setTileTerrain(HexCoord(-1, y), "Plains")
            game.setTileTerrain(HexCoord(1, y), "Plains")
        }
        game.setTileTerrain(HexCoord(0, 0), "Plains")
        attacker = game.addCiv()
        defender = game.addCiv()
        for (y in listOf(-3, 3)) {
            game.addCity(attacker, game.getTile(-4, y))
            game.addCity(defender, game.getTile(4, y)).getCenterTile().setExplored(attacker, true)
        }
        game.addUnit("Modern Armor", attacker, game.getTile(-3, -3))
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        val pairs = attacker.cities.flatMap { own -> defender.cities.map { own to it } }
        val accessible = MotivationToAttackAutomation.getAccessibleAttackTargets(attacker, defender, pairs)
        assertEquals(2, accessible.map { it.second }.distinct().size)
        assertEquals(0f, pathModifier(), 0f)
    }

    @Test
    fun `one amphibious attack approach remains less attractive than land`() {
        assertEquals(0f, pathModifier(), 0f)
        game.setTileTerrain(HexCoord(-2, 0), "Coast")
        assertEquals(-2f, pathModifier(), 0f)
    }

    private fun pathModifier(): Float {
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertNull(report.rejectionReason)
        return report.components.single { it.first == "Attack paths" }.second
    }
}
