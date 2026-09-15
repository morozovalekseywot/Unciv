package com.unciv.logic.simulation

import com.unciv.json.json
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class WarSimulationLogTest {
    private val game = TestGame().apply { makeHexagonalMap(5) }
    private val attacker = game.addCiv().apply { game.addUnit("Warrior", this, game.getTile(-3, 0)) }
    private val defender = game.addCiv().apply { game.addUnit("Warrior", this, game.getTile(0, 0)) }
    private val ally = game.addCiv().apply { game.addUnit("Warrior", this, game.getTile(3, 0)) }
    private val log = WarSimulationLog()

    init {
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        attacker.diplomacyFunctions.makeCivilizationsMeet(ally)
        defender.diplomacyFunctions.makeCivilizationsMeet(ally)
        game.gameInfo.warSimulationLog = log
    }

    @Test
    fun `actual declaration counts once and defensive pact entry is separate`() {
        defender.getDiplomacyManager(ally)!!.signDefensivePact(30)
        game.gameInfo.turns = 42
        attacker.getDiplomacyManager(defender)!!.declareWar()
        assertEquals(1, log.warsStarted)
        assertEquals(42, log.firstWarTurn)
        assertEquals(2, log.declarations.size)
        assertEquals(1, log.declarations.count { it.type == WarType.DefensivePactWar })
        assertTrue(attacker.isAtWarWith(ally))
    }

    @Test
    fun `peace then another war is a new start`() {
        attacker.getDiplomacyManager(defender)!!.declareWar()
        attacker.getDiplomacyManager(defender)!!.makePeace()
        game.gameInfo.turns = 50
        attacker.getDiplomacyManager(defender)!!.declareWar()
        assertEquals(2, log.warsStarted)
    }

    @Test
    fun `team declarations count as one start while joining an existing war does not`() {
        attacker.getDiplomacyManager(defender)!!.declareWar(DeclareWarReason(WarType.TeamWar, ally))
        ally.getDiplomacyManager(defender)!!.declareWar(DeclareWarReason(WarType.TeamWar, attacker))
        log.record(ally, defender, DeclareWarReason(WarType.JoinWar, attacker))
        assertEquals(1, log.warsStarted)
        assertEquals(3, log.declarations.size)
    }

    @Test
    fun `city state conflicts do not inflate major civilization war frequency`() {
        val minor = game.addCiv(cityStateType = "Cultured")
        log.record(attacker, minor, DeclareWarReason(WarType.DirectWar))
        log.record(minor, defender, DeclareWarReason(WarType.CityStateAllianceWar, attacker))
        assertTrue(log.declarations.isEmpty())
        assertEquals(0, log.warsStarted)
        assertNull(log.firstWarTurn)
    }

    @Test
    fun `peaceful run has no fabricated first war and telemetry is transient`() {
        assertEquals(0, log.warsStarted)
        assertNull(log.firstWarTurn)
        assertFalse(json().toJson(game.gameInfo).contains("warSimulationLog"))
        assertNull(game.gameInfo.clone().warSimulationLog)
    }
}
