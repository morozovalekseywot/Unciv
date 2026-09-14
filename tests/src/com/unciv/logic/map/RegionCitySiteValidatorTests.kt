package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.RegionCitySiteValidator
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class RegionCitySiteValidatorTests {
    private val testGame = TestGame()
    private lateinit var region: Region
    private lateinit var tileData: TileDataMap

    @Before
    fun setUp() {
        testGame.makeHexagonalMap(10)
        for (tile in testGame.tileMap.values) {
            tile.baseTerrain = "Grassland"
            tile.setTerrainTransients()
        }
        region = Region(testGame.tileMap, Rectangle())
        region.startPosition = testGame.getTile(0, 0).position
        region.tiles.add(testGame.getTile(0, 0))
        tileData = TileDataMap(testGame.tileMap.values.size)
        for (tile in testGame.tileMap.values)
            tileData[tile] = MapGenTileData(tile, region, testGame.ruleset)
    }

    private fun validates(
        requiredSites: Int = 1,
        workRange: Int = 3,
        minWorkableTiles: Int = 10,
        foreignStarts: Collection<Tile> = emptyList()
    ) = RegionCitySiteValidator.regionHasEnoughCitySites(
        region, tileData, requiredSites, minWorkableTiles, 5, workRange, foreignStarts
    )

    private fun addSite(x: Int) {
        region.tiles.add(testGame.getTile(x, 0))
    }

    private fun addLuxury(x: Int, y: Int = 0) {
        testGame.getTile(x, y).setTileResource("Salt")
    }

    @Test
    fun fertileSiteWithoutLuxuryIsRejected() {
        assertFalse(validates())
    }

    @Test
    fun bonusAndStrategicResourcesDoNotCountAsLuxury() {
        testGame.getTile(1, 0).setTileResource("Wheat")
        testGame.getTile(2, 0).setTileResource("Iron")
        assertFalse(validates())
    }

    @Test
    fun luxuryOnCityCenterCounts() {
        addLuxury(0)
        assertTrue(validates())
    }

    @Test
    fun luxuryAtWorkRangeBoundaryOutsideRegionCounts() {
        addLuxury(3)
        assertTrue(validates())
    }

    @Test
    fun luxuryOutsideWorkRangeDoesNotCount() {
        addLuxury(4)
        assertFalse(validates())
    }

    @Test
    fun moddedWorkRangeIsRespected() {
        addLuxury(3)
        assertFalse(validates(workRange = 2))
        assertTrue(validates(workRange = 4))
    }

    @Test
    fun coastalLuxuryCountsForLandCity() {
        val coast = testGame.getTile(1, 0)
        coast.baseTerrain = "Coast"
        coast.setTerrainTransients()
        coast.setTileResource("Pearls")
        tileData[coast] = MapGenTileData(coast, region, testGame.ruleset)
        assertTrue(validates())
    }

    @Test
    fun separateDepositsOfSameLuxurySupplyTwoCities() {
        addSite(5)
        addLuxury(0)
        addLuxury(5)
        assertTrue(validates(requiredSites = 2))
    }

    @Test
    fun sharedDepositCannotSupplyTwoCities() {
        addSite(5)
        addLuxury(2)
        assertFalse(validates(requiredSites = 2))
    }

    @Test
    fun sharedDepositCanBeReassignedWhenCapitalHasAlternative() {
        addSite(5)
        // The capital encounters its center first. Both cities can reach that deposit,
        // but only the capital can reach the second one. A fixed reservation would fail.
        addLuxury(0)
        addLuxury(-1)
        assertTrue(validates(requiredSites = 2, workRange = 5))
    }

    @Test
    fun capitalWithoutLuxuryCannotBeReplacedByAnotherSite() {
        addSite(5)
        addLuxury(5)
        assertFalse(validates())
    }

    @Test
    fun expansionSiteWithoutLuxuryIsNotCounted() {
        addSite(5)
        addLuxury(-1)
        assertFalse(validates(requiredSites = 2))
    }

    @Test
    fun luxuryDoesNotBypassCapitalWorkableTileRequirement() {
        addLuxury(0)
        assertFalse(validates(minWorkableTiles = 100))
    }

    @Test
    fun luxuryDoesNotBypassSpacingBetweenCities() {
        addSite(4)
        addLuxury(0)
        addLuxury(4)
        assertFalse(validates(requiredSites = 2))
    }

    @Test
    fun luxuryDoesNotBypassSpacingToForeignStart() {
        addLuxury(0)
        assertFalse(validates(foreignStarts = listOf(testGame.getTile(4, 0))))
    }

    @Test
    fun regionWithoutAssignedCapitalStillRequiresDistinctDeposits() {
        region.startPosition = null
        addSite(5)
        addLuxury(2)
        assertFalse(validates(requiredSites = 2))
        addLuxury(5)
        assertTrue(validates(requiredSites = 2))
    }

    @Test
    fun noSitesRequestedDoesNotRequireLuxury() {
        assertTrue(validates(requiredSites = 0))
    }
}
