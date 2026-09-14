package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.MapGenerationDiagnostics
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.ImpactType
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.RegionCitySiteValidator
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.tile.TileNormalizer
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy

@RunWith(BaseTestRunner::class)
class MapGenerationNormalizationTests {
    private fun anyTile(): Tile = any(Tile::class.java) ?: Tile()

    @Test
    fun removedFeatureNoLongerMakesSnowWorkableAndRefreshPreservesPlacementData() {
        val game = TestGame()
        game.makeHexagonalMap(3)
        val capital = game.getTile(0, 0)
        val region = Region(game.tileMap, Rectangle()).apply {
            startPosition = capital.position
            tiles.add(capital)
        }
        val data = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) {
            tile.baseTerrain = "Snow"
            tile.setTerrainTransients()
            tile.setTerrainFeatures(listOf("Forest")) // Incompatible: removed by the real normalizer.
            data[tile] = MapGenTileData(tile, region, game.ruleset)
        }
        capital.baseTerrain = "Grassland"
        capital.setTerrainTransients()
        capital.setTileResource("Salt")
        val neighborData = data[game.getTile(1, 0)]!!
        neighborData.impacts[ImpactType.Luxury] = 99
        neighborData.closeStartPenalty = 17
        neighborData.startScore = 23
        neighborData.isGoodStart = false
        fun validates() = RegionCitySiteValidator.regionHasEnoughCitySites(
            region, data, 1, 7, 5, 3, emptyList())
        assertTrue(validates())

        for (tile in game.tileMap.values) TileNormalizer.normalizeToRuleset(tile, game.ruleset)
        assertTrue("Without refreshing, removed forest still counts as workable", validates())
        for (tile in game.tileMap.values) data[tile]!!.refreshTerrainQualities(game.ruleset)
        assertFalse("Bare snow cannot supply the city's workable neighborhood", validates())
        assertTrue(neighborData.isJunk)
        assertFalse(neighborData.isProd)
        assertEquals(99, neighborData.impacts[ImpactType.Luxury])
        assertEquals(17, neighborData.closeStartPenalty)
        assertEquals(23, neighborData.startScore)
        assertFalse(neighborData.isGoodStart)
        assertEquals(region, neighborData.region)

        neighborData.tile.baseTerrain = "Grassland"
        neighborData.tile.setTerrainTransients()
        neighborData.refreshTerrainQualities(game.ruleset)
        assertFalse("Refresh must also clear an old junk classification", neighborData.isJunk)
    }

    @Test
    fun removedLuxuriesCannotSupplyCitiesOrInfluenceFallbackScore() {
        val game = TestGame()
        game.addCiv()
        val constants = game.ruleset.modOptions.constants
        constants.maxPangaeaCitySiteRetries = 3
        constants.minWorkableTilesPerCitySite = 0
        var acceptedPlacements = 0
        // Deliberately emulate a placement/terrain mismatch. Keep the real normalizer, validator,
        // retry loop and report: every luxury placed on passable land must be removed at the end.
        for (resource in game.ruleset.tileResources.values.toList()) {
            if (resource.resourceType != ResourceType.Luxury) continue
            val misplaced = spy(resource)
            misplaced.terrainsCanBeFoundOn = listOf("Mountain")
            doAnswer { invocation ->
                val tile = invocation.getArgument<Tile>(0)
                val allowed = tile.isLand && !tile.isImpassible()
                if (allowed) acceptedPlacements++
                allowed
            }.`when`(misplaced).generatesNaturallyOn(anyTile())
            game.ruleset.tileResources[resource.name] = misplaced
        }
        val parameters = MapParameters().apply {
            type = MapType.pangaea
            mapSize = MapSize.Medium
            noNaturalWonders = true
            seed = 1
        }
        val diagnostics = MapGenerationDiagnostics()
        val map = MapGenerator(game.ruleset).generateMap(parameters, game.gameInfo.gameParameters,
            game.gameInfo, diagnostics)

        assertTrue("Exercise placement before normalization", acceptedPlacements > 0)
        assertTrue("All incompatible luxuries must be removed",
            map.values.none { it.tileResource?.resourceType == ResourceType.Luxury })
        assertEquals("Removed resources cannot produce early success", 3, diagnostics.attempts.size)
        for (attempt in diagnostics.attempts) {
            assertEquals("Score must describe normalized terrain", 0, attempt.satisfiedRegions)
            assertTrue(attempt.regions.all { !it.passed && it.cityLuxuries.isEmpty() })
        }
        assertEquals("Keep first map on equal fallback scores", 1, diagnostics.selectedAttempt)
        val report = diagnostics.selectedMapReport!!
        assertTrue(report.contains("fallback=true"))
        assertTrue(report.contains("none assigned by validator"))
        assertFalse(report.contains("assigned deposit missing"))
    }
}
