package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.MapSize
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class CityLocationTileRankerTest {

    private lateinit var testGame: TestGame

    @Before
    fun setUp() {
        testGame = TestGame()
        testGame.makeHexagonalMap(12, Constants.snow)
        testGame.tileMap.mapParameters.mapSize = MapSize.Medium
    }

    @Test
    fun `fresh non-capital city is not an expansion anchor`() {
        val civ = testGame.addCiv()
        testGame.gameInfo.turns = 100
        val capital = testGame.addCity(civ, testGame.tileMap[0, 0])
        val freshCity = testGame.addCity(civ, testGame.tileMap[0, 6])

        assertTrue(CityLocationTileRanker.isEstablishedExpansionAnchor(capital))
        assertFalse(CityLocationTileRanker.isEstablishedExpansionAnchor(freshCity))

        testGame.gameInfo.turns = 1000
        assertTrue(CityLocationTileRanker.isEstablishedExpansionAnchor(freshCity))
    }

    @Test
    fun `valuable site blocked by known capital motivates expansion war`() {
        val expandingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        val targetCapital = testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        testGame.addCity(expandingCiv, testGame.tileMap[0, 10])
        expandingCiv.diplomacyFunctions.makeCivilizationsMeet(capitalOwner)
        targetCapital.getCenterTile().setExplored(expandingCiv, true)
        val settler = testGame.addUnit("Settler", expandingCiv, testGame.tileMap[7, 0])
        testGame.ruleset.modOptions.constants.minimumCityLocationTileValue = 0f

        val blockedSite = testGame.tileMap[4, 0]
        blockedSite.forEachTileInDistance(2) { tile ->
            testGame.setTileTerrain(tile.position, Constants.grassland)
        }
        testGame.setTileTerrainAndFeatures(blockedSite.position, Constants.grassland, Constants.hill)
        blockedSite.hasBottomRiver = true

        assertTrue(CityLocationTileRanker.getExpansionWarMotivation(expandingCiv, capitalOwner) > 0f)
        expandingCiv.getDiplomacyManager(capitalOwner)!!.setFlag(DiplomacyFlags.WaryOf, -1)
        assertTrue(CityLocationTileRanker.shouldWaitForExpansionWar(settler))
    }

    @Test
    fun `friendship prevents expansion war motivation`() {
        val expandingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        testGame.addCity(expandingCiv, testGame.tileMap[0, 10])
        expandingCiv.diplomacyFunctions.makeCivilizationsMeet(capitalOwner)
        testGame.addUnit("Settler", expandingCiv, testGame.tileMap[7, 0])
        expandingCiv.getDiplomacyManager(capitalOwner)!!
            .setFlag(DiplomacyFlags.DeclarationOfFriendship, 30)

        assertEquals(0f, CityLocationTileRanker.getExpansionWarMotivation(expandingCiv, capitalOwner))
    }

    @Test
    fun `expansion war requires an existing settler`() {
        val expandingCiv = testGame.addCiv()
        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        testGame.addCity(expandingCiv, testGame.tileMap[0, 10])
        expandingCiv.diplomacyFunctions.makeCivilizationsMeet(capitalOwner)

        assertEquals(0f, CityLocationTileRanker.getExpansionWarMotivation(expandingCiv, capitalOwner))
    }

    @Test
    fun `foreign capital at distance seven does not reduce location rank`() {
        val expandingCiv = testGame.addCiv()
        val settler = testGame.addUnit("Settler", expandingCiv, testGame.tileMap[7, 0])
        val rankWithoutCapital = CityLocationTileRanker.getBestTilesToFoundCity(
            settler,
            distanceToSearch = 0,
            minimumValue = -1000f
        ).bestTileRank

        val capitalOwner = testGame.addCiv()
        testGame.addCity(capitalOwner, testGame.tileMap[0, 0])
        val rankWithCapital = CityLocationTileRanker.getBestTilesToFoundCity(
            settler,
            distanceToSearch = 0,
            minimumValue = -1000f
        ).bestTileRank

        assertEquals(rankWithoutCapital, rankWithCapital, 0f)
    }
}
