package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.ImpactType
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.RegionalStrategicBalancePlacement
import com.unciv.logic.map.tile.TileNormalizer
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class RegionalStrategicBalanceTests {
    private val game = TestGame()
    private lateinit var region: Region

    @Before
    fun setUp() {
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) {
            tile.baseTerrain = "Grassland"
            tile.setTerrainTransients()
        }
        game.tileMap.mapParameters.strategicBalance = true
        region = Region(game.tileMap, Rectangle())
        region.startPosition = game.getTile(0, 0).position
        region.tiles.addAll(game.tileMap.values)
        game.tileMap.addStartingLocation(game.addCiv().civID, game.getTile(0, 0))
        game.setTileFeatures(HexCoord.of(1, 0), "Forest")
    }

    private fun place(regions: List<Region> = listOf(region), impacted: Boolean = false) {
        val tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) {
            tileData[tile] = MapGenTileData(tile, regions.firstOrNull { tile in it.tiles }, game.ruleset)
            if (impacted) tileData[tile]!!.impacts[ImpactType.Strategic] = 10
        }
        RegionalStrategicBalancePlacement.placeResources(game.tileMap, regions, tileData)
    }

    @Test
    fun addsOneSmallDepositAndDoesNotDuplicateItOnSecondPass() {
        place()
        val uranium = game.tileMap.values.filter { it.resource == "Uranium" }
        assertEquals(1, uranium.size)
        assertEquals(game.getTile(1, 0), uranium.single())
        assertEquals(2, uranium.single().resourceAmount)
        place()
        assertEquals(1, game.tileMap.values.count { it.resource == "Uranium" })
        TileNormalizer.normalizeToRuleset(uranium.single(), game.ruleset)
        assertEquals("Uranium", uranium.single().resource)
    }

    @Test
    fun leavesAnExistingAccessibleMajorDepositUnchanged() {
        val tile = game.getTile(1, 0)
        tile.setTileResource(game.ruleset.tileResources["Uranium"]!!, majorDeposit = true)
        place()
        assertEquals(1, game.tileMap.values.count { it.resource == "Uranium" })
        assertEquals(4, tile.resourceAmount)
    }

    @Test
    fun disabledStrategicBalanceDoesNothing() {
        game.tileMap.mapParameters.strategicBalance = false
        place()
        assertTrue(game.tileMap.values.none { it.resource == "Uranium" })
    }

    @Test
    fun strategicBalanceResourcePresetAlsoEnablesRegionalBalance() {
        game.tileMap.mapParameters.strategicBalance = false
        game.tileMap.mapParameters.mapResources = MapResourceSetting.strategicBalance.label
        place()
        assertEquals("Uranium", game.getTile(1, 0).resource)
    }

    @Test
    fun respectsSmallDepositAmountsForSparseAndAbundantResources() {
        for ((setting, amount) in listOf(MapResourceSetting.sparse to 1, MapResourceSetting.abundant to 3)) {
            game.getTile(1, 0).tileResource = null
            game.tileMap.mapParameters.mapResources = setting.label
            place()
            assertEquals(amount, game.getTile(1, 0).resourceAmount)
        }
    }

    @Test
    fun configurableListSupportsCoalAndAluminumWithoutChangingTheAlgorithm() {
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources =
            arrayListOf("Uranium", "Aluminum", "Coal", "Uranium", "Missing resource", "Salt")
        game.setTileFeatures(HexCoord.of(2, 0), "Hill")
        place()
        for (name in listOf("Uranium", "Aluminum", "Coal"))
            assertEquals(name, 1, game.tileMap.values.count { it.resource == name })
        assertTrue(game.tileMap.values.none { it.resource == "Salt" })
    }

    @Test
    fun emptyListDisablesRegionalBalance() {
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf()
        place()
        assertTrue(game.tileMap.values.none { it.resource != null })
    }

    @Test
    fun resourceMissingFromRulesetIsIgnored() {
        game.ruleset.tileResources.remove("Uranium")
        place()
        assertTrue(game.tileMap.values.none { it.resource != null })
    }

    @Test
    fun neverOverwritesOtherResourcesOrForcesAnInappropriateTerrain() {
        game.getTile(1, 0).setTileResource("Furs")
        val before = game.tileMap.values.associate { it.position to it.resource }
        place()
        assertEquals(before, game.tileMap.values.associate { it.position to it.resource })
    }

    @Test
    fun naturalGenerationRestrictionsAreRespected() {
        game.createResource("Doesn't generate naturally").apply {
            // TestGame has already inserted this object under its generated name.
            game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf(name)
            resourceType = ResourceType.Strategic
            terrainsCanBeFoundOn = listOf("Forest")
        }
        place()
        assertTrue(game.tileMap.values.none { it.resource != null })
    }

    @Test
    fun strategicImpactsCannotPreventTheRegionalTopUp() {
        place(impacted = true)
        assertEquals("Uranium", game.getTile(1, 0).resource)
    }

    @Test
    fun countsAnExistingDepositAtAViableExpansionSiteFarFromCapital() {
        game.setTileFeatures(HexCoord.of(6, 0), "Forest")
        game.getTile(5, 1).setTileResource("Salt")
        game.getTile(6, 0).setTileResource(game.ruleset.tileResources["Uranium"]!!, majorDeposit = false)
        place()
        assertEquals(1, game.tileMap.values.count { it.resource == "Uranium" })
        assertEquals(null, game.getTile(1, 0).resource)
    }

    @Test
    fun addsDepositNearExpansionWhenThereIsNoSuitableTileNearCapital() {
        game.setTileFeatures(HexCoord.of(1, 0))
        game.setTileFeatures(HexCoord.of(6, 0), "Forest")
        game.getTile(5, 1).setTileResource("Salt")
        place()
        assertEquals("Uranium", game.getTile(6, 0).resource)
    }

    @Test
    fun aDistantDepositWithoutAViableCitySiteDoesNotCount() {
        game.setTileFeatures(HexCoord.of(8, 0), "Forest")
        game.getTile(8, 0).setTileResource(game.ruleset.tileResources["Uranium"]!!, majorDeposit = false)
        place()
        assertEquals("Uranium", game.getTile(1, 0).resource)
        assertEquals(2, game.tileMap.values.count { it.resource == "Uranium" })
    }

    @Test
    fun depositsCutOffByMountainsDoNotCountEvenNearAViableSite() {
        for (tile in game.tileMap.values.filter { it.position.x == 3 })
            game.setTileTerrainAndFeatures(tile.position, "Mountain")
        game.setTileFeatures(HexCoord.of(6, 0), "Forest")
        game.getTile(5, 1).setTileResource("Salt")
        game.getTile(6, 0).setTileResource(game.ruleset.tileResources["Uranium"]!!, majorDeposit = false)
        place()
        assertEquals("Uranium", game.getTile(1, 0).resource)
    }

    @Test
    fun depositsInAForeignStartCatchmentDoNotCount() {
        game.tileMap.addStartingLocation(game.addCiv().civID, game.getTile(6, 0))
        game.setTileFeatures(HexCoord.of(4, 0), "Forest")
        game.getTile(4, 0).setTileResource(game.ruleset.tileResources["Uranium"]!!, majorDeposit = false)
        place()
        assertEquals("Uranium", game.getTile(1, 0).resource)
    }

    @Test
    fun oneRegionsDepositCannotSatisfyAnotherRegion() {
        val other = Region(game.tileMap, Rectangle())
        other.startPosition = game.getTile(7, 0).position
        game.tileMap.addStartingLocation(game.addCiv().civID, game.getTile(7, 0))
        other.tiles.addAll(region.tiles.filter { it.position.x >= 4 })
        region.tiles.removeAll(other.tiles)
        game.setTileFeatures(HexCoord.of(8, 0), "Forest")
        place(listOf(region, other))
        assertEquals("Uranium", game.getTile(1, 0).resource)
        assertEquals("Uranium", game.getTile(8, 0).resource)
    }
}
