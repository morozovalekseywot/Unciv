package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.ImpactType
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.LuxuryResourcePlacementLogic
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class LuxuryResourcePlacementTests {
    @Test
    fun regionalLuxuryBlocksNeighborsButNotFourthRing() {
        val testGame = TestGame()
        testGame.makeHexagonalMap(10)
        val tileMap = testGame.tileMap
        for (tile in tileMap.values) {
            tile.baseTerrain = "Plains"
            tile.setTerrainTransients()
        }

        val start = tileMap[0, 0]
        val deposit = tileMap[5, 0]
        val region = Region(tileMap, Rectangle())
        region.startPosition = start.position
        region.luxury = "Salt"
        region.totalFertility = 1
        // Force the regional pass to use this tile, outside the capital's protected area.
        region.tiles.add(deposit)
        val tileData = TileDataMap(tileMap.values.size)
        for (tile in tileMap.values)
            tileData[tile] = MapGenTileData(tile, region, testGame.ruleset)
        tileData.placeImpact(ImpactType.Luxury, start, 3)
        // Isolate regional placement from the final special-luxury pass.
        testGame.ruleset.tileResources.remove("Marble")

        LuxuryResourcePlacementLogic.placeLuxuries(
            arrayListOf(region), tileMap, tileData, testGame.ruleset, emptyList(), emptyList()
        )

        assertEquals("Salt", deposit.resource)
        for (neighbor in deposit.neighbors)
            assertTrue(tileData[neighbor]!!.impacts.containsKey(ImpactType.Luxury))
        // This tile is four steps from the deposit and nine from the capital.
        assertFalse(tileData[tileMap[9, 0]]!!.impacts.containsKey(ImpactType.Luxury))
    }
}
