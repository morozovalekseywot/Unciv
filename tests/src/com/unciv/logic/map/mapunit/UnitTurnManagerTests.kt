package com.unciv.logic.map.mapunit

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class UnitTurnManagerTests {
    private lateinit var citadelCiv: Civilization
    private lateinit var enemyCiv: Civilization
    private lateinit var citadelTile: Tile
    private lateinit var enemyUnit: MapUnit

    private val testGame = TestGame()

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(3)
        citadelCiv = testGame.addCiv()
        enemyCiv = testGame.addCiv()
        citadelCiv.getDiplomacyManagerOrMeet(enemyCiv).declareWar()

        val citadelCity = testGame.addCity(citadelCiv, testGame.getTile(-2, 0))
        citadelTile = testGame.getTile(HexCoord.Zero)
        citadelTile.setOwningCity(citadelCity)
        citadelTile.setImprovement("Citadel")
        enemyUnit = testGame.addUnit("Warrior", enemyCiv, citadelTile)
    }

    @Test
    fun `unit ending turn on enemy citadel takes damage`() {
        UnitTurnManager(enemyUnit).endTurn()

        assertEquals(70, enemyUnit.health)
    }

    @Test
    fun `unit ending turn adjacent to enemy citadel takes damage`() {
        val adjacentTile = citadelTile.neighbors.first()
        enemyUnit.removeFromTile()
        enemyUnit.putInTile(adjacentTile)

        UnitTurnManager(enemyUnit).endTurn()

        assertEquals(70, enemyUnit.health)
    }

    @Test
    fun `unit ending turn on pillaged enemy citadel does not take damage`() {
        citadelTile.improvementIsPillaged = true

        UnitTurnManager(enemyUnit).endTurn()

        assertEquals(100, enemyUnit.health)
    }
}
