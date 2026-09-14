package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.RegionCitySiteValidator
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class SharedCitySiteSelectionTests {
    private val game = TestGame()
    private lateinit var tileData: TileDataMap

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) {
            tile.baseTerrain = "Grassland"
            tile.setTerrainTransients()
            tileData[tile] = MapGenTileData(tile, null, game.ruleset)
        }
    }

    private fun region(capital: Tile, vararg sites: Tile): Region = Region(game.tileMap, Rectangle()).apply {
        startPosition = capital.position
        tiles.add(capital)
        tiles.addAll(sites)
        capital.setTileResource("Salt")
    }

    private fun select(
        regions: List<Region>,
        requiredSites: Int = 2,
        extraStarts: List<Tile> = emptyList(),
        protectionRadius: Int = 0
    ): List<Map<Tile, Tile>> = RegionCitySiteValidator.selectCitySites(
        regions, tileData, requiredSites, 7, 5, 3,
        regions.map { game.tileMap[it.startPosition!!] } + extraStarts, protectionRadius
    )

    @Test
    fun babylonCannotCountCottonInMongolianCapitalsSecondRing() {
        val city = game.getTile(-7, 2)
        val cotton = game.getTile(-4, 5)
        val mongolianCapital = game.getTile(-3, 7)
        val babylon = region(game.getTile(-13, -7), city)
        val mongolia = region(mongolianCapital)
        cotton.setTileResource("Cotton")
        assertEquals(3, city.aerialDistanceTo(cotton))
        assertEquals(2, mongolianCapital.aerialDistanceTo(cotton))

        val result = select(listOf(babylon, mongolia))
        assertEquals(1, result[0].size)
        assertFalse(result[0].containsKey(city))
        assertFalse(result[0].containsValue(cotton))
    }

    @Test
    fun cityStateWorkRangeBoundaryIsExcludedButNextRingIsAvailable() {
        val city = game.getTile(0, 0)
        val home = region(game.getTile(-8, 0), city)
        val cityState = game.getTile(5, 0)
        game.getTile(2, 0).setTileResource("Cotton") // Third ring of the city-state.
        assertEquals(1, select(listOf(home), extraStarts = listOf(cityState)).single().size)
        game.getTile(1, 0).setTileResource("Cotton") // Fourth ring: may be assigned.
        assertEquals(game.getTile(1, 0), select(listOf(home), extraStarts = listOf(cityState)).single()[city])
    }

    @Test
    fun oneDepositCannotSupplyExpansionCitiesOfTwoCivilizations() {
        val first = region(game.getTile(-8, 0), game.getTile(-2, 0))
        val second = region(game.getTile(8, 0), game.getTile(3, 0))
        val shared = game.getTile(0, 0)
        shared.setTileResource("Cotton")
        // Each region used to pass in isolation. Together only one can use the cotton.
        assertEquals(2, select(listOf(first)).single().size)
        assertEquals(2, select(listOf(second)).single().size)
        for (order in listOf(listOf(first, second), listOf(second, first))) {
            val result = select(order)
            assertEquals(1, result.count { it.size == 2 })
            assertEquals(1, result.count { shared in it.values })
        }
    }

    @Test
    fun laterRegionCanReassignEarlierRegionsLuxuryAndReportFinalAssignment() {
        val firstCity = game.getTile(-2, 0)
        val secondCity = game.getTile(3, 0)
        val first = region(game.getTile(-8, 0), firstCity)
        val second = region(game.getTile(8, 0), secondCity)
        val shared = game.getTile(0, 0)
        val alternative = game.getTile(-5, 0)
        shared.setTileResource("Cotton")
        alternative.setTileResource("Cotton")
        assertEquals(shared, select(listOf(first)).single()[firstCity])
        val result = select(listOf(first, second))
        assertEquals(listOf(2, 2), result.map { it.size })
        assertEquals(alternative, result[0][firstCity])
        assertEquals(shared, result[1][secondCity])
    }

    @Test
    fun neighboringFrenchAndMongolianCitySitesCannotBothBeSelected() {
        val frenchCity = game.getTile(-4, 2)
        val mongolianCity = game.getTile(-5, 2)
        val france = region(game.getTile(1, 3), frenchCity)
        val mongolia = region(game.getTile(-3, 7), mongolianCity)
        game.getTile(-6, 0).setTileResource("Cotton")
        assertEquals(1, frenchCity.aerialDistanceTo(mongolianCity))
        assertEquals(2, select(listOf(france)).single().size)
        assertEquals(2, select(listOf(mongolia)).single().size)
        val result = select(listOf(france, mongolia))
        assertFalse(frenchCity in result[0] && mongolianCity in result[1])
    }

    @Test
    fun conflictingSiteCanBeReplacedWithCompatibleAlternative() {
        val firstCity = game.getTile(-3, 0)
        val conflicting = game.getTile(-2, 0)
        val alternative = game.getTile(3, 0)
        val first = region(game.getTile(-8, 0), firstCity)
        val second = region(game.getTile(8, 0), conflicting, alternative)
        for (tile in listOf(firstCity, conflicting, alternative)) tile.setTileResource("Cotton")
        val result = select(listOf(first, second))
        assertEquals(listOf(2, 2), result.map { it.size })
        assertTrue(firstCity in result[0])
        assertTrue(alternative in result[1])
        assertFalse(conflicting in result[1])
    }

    @Test
    fun failedCapitalCannotBeReplacedByExpansionSitesOrCrowdedByAnotherRegion() {
        val failedCapital = game.getTile(2, 0)
        val first = region(game.getTile(-8, 0), game.getTile(-2, 0))
        val second = region(failedCapital, game.getTile(8, 0))
        failedCapital.tileResource = null
        game.getTile(-3, 0).setTileResource("Cotton")
        game.getTile(8, 0).setTileResource("Salt")
        val result = select(listOf(first, second))
        assertEquals(1, result[0].size)
        assertTrue(result[1].isEmpty())
    }

    @Test
    fun expansionRespectsInclusiveForeignCapitalProtectionButCapitalsAreExempt() {
        val first = region(game.getTile(-8, 0), game.getTile(3, 0))
        val second = region(game.getTile(8, 0))
        game.getTile(2, 0).setTileResource("Cotton")
        assertEquals(2, select(listOf(first, second), protectionRadius = 4)[0].size)
        assertEquals(1, select(listOf(first, second), protectionRadius = 5)[0].size)
        val closeCapital = region(game.getTile(-3, 0))
        assertEquals(listOf(1, 1), select(listOf(first, closeCapital), requiredSites = 1,
            protectionRadius = 5).map { it.size })
    }
}
