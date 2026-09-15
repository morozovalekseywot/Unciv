package com.unciv.logic.simulation

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType

/** Opt-in diagnostics, owned by one simulation. Never serialized or shared between games. */
class WarSimulationLog {
    data class Declaration(val turn: Int, val attacker: String, val defender: String, val type: WarType)

    val declarations = ArrayList<Declaration>()
    private val teamStarts = HashSet<List<String>>()

    /** One joint attack is one start, although it creates two bilateral declarations. */
    var warsStarted = 0
        private set
    var firstWarTurn: Int? = null
        private set

    fun record(attacker: Civilization, defender: Civilization, reason: DeclareWarReason) {
        // Minor-civ wars and barbarian hostility must not inflate major-civ aggression metrics.
        if (!attacker.isMajorCiv() || !defender.isMajorCiv()) return
        val turn = attacker.gameInfo.turns
        declarations.add(Declaration(turn, attacker.civID, defender.civID, reason.warType))
        if (reason.warType != WarType.DirectWar && reason.warType != WarType.TeamWar) return
        if (reason.warType == WarType.TeamWar) {
            val partner = reason.allyCiv!!
            val participants = listOf(attacker.civID, partner.civID).sorted()
            if (!teamStarts.add(listOf(turn.toString(), defender.civID) + participants)) return
        }
        warsStarted++
        if (firstWarTurn == null) firstWarTurn = turn
    }
}
