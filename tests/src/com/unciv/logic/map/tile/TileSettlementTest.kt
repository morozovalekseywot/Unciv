package com.unciv.logic.map.tile

import com.unciv.logic.map.MapSize
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TileSettlementTest {

    private lateinit var testGame: TestGame

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(12)
        testGame.tileMap.mapParameters.mapSize = MapSize.Medium
    }

    @Test
    fun `additional city is blocked inside foreign capital protection radius`() {
        val settlingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        testGame.addCity(settlingCiv, testGame.tileMap[0, 10])

        assertFalse(testGame.tileMap[4, 0].canBeSettled(settlingCiv))
        assertFalse(testGame.tileMap[5, 0].canBeSettled(settlingCiv))
        assertTrue(testGame.tileMap[6, 0].canBeSettled(settlingCiv))
    }

    @Test
    fun `first city is exempt from foreign capital protection`() {
        val settlingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])

        assertTrue(testGame.tileMap[4, 0].canBeSettled(settlingCiv))
    }

    @Test
    fun `war removes foreign capital protection`() {
        val settlingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        testGame.addCity(settlingCiv, testGame.tileMap[0, 10])
        settlingCiv.diplomacyFunctions.makeCivilizationsMeet(capitalOwner)
        settlingCiv.getDiplomacyManager(capitalOwner)!!.declareWar()

        assertTrue(testGame.tileMap[5, 0].canBeSettled(settlingCiv))
    }

    @Test
    fun `ruleset can disable foreign capital protection`() {
        val settlingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        testGame.addCity(settlingCiv, testGame.tileMap[0, 10])
        testGame.ruleset.modOptions.constants.foreignCapitalSettlementProtectionRadius = 0

        assertTrue(testGame.tileMap[5, 0].canBeSettled(settlingCiv))
    }

    @Test
    fun `foreign capital protection is disabled below medium map size`() {
        val settlingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        testGame.addCity(settlingCiv, testGame.tileMap[0, 10])

        val smallMapSizes = listOf(MapSize.Tiny, MapSize.Small, MapSize(19))
        for (mapSize in smallMapSizes) {
            testGame.tileMap.mapParameters.mapSize = mapSize
            assertTrue(testGame.tileMap[5, 0].canBeSettled(settlingCiv))
        }
    }
}
