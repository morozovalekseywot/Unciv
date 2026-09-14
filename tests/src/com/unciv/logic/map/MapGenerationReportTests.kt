package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.MapGenerationDiagnostics
import com.unciv.logic.map.mapgenerator.MapGenerationReport
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.RegionalStrategicBalancePlacement
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@RunWith(BaseTestRunner::class)
class MapGenerationReportTests {
    private val game = TestGame()
    private lateinit var region: Region
    private lateinit var tileData: TileDataMap
    private lateinit var sites: MapGenerationDiagnostics.RegionResult

    @Before
    fun setUp() {
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) {
            tile.baseTerrain = "Grassland"
            tile.setTerrainTransients()
        }
        val civ = game.addCiv(game.ruleset.nations["Rome"]!!)
        game.tileMap.addStartingLocation(civ.civID, game.getTile(0, 0))
        game.tileMap.mapParameters.strategicBalance = true
        region = Region(game.tileMap, Rectangle()).apply {
            startPosition = HexCoord.of(0, 0)
            type = "Grassland"
            luxury = "Salt"
            tiles.addAll(game.tileMap.values.filter { it.position.x <= 5 })
        }
        game.getTile(0, 1).setTileResource("Salt")
        game.getTile(5, 1).setTileResource("Silk")
        game.getTile(6, 0).setTileResource("Gems") // Outside the region but in the selected second city's range.
        game.getTile(1, 0).setTileResource(game.ruleset.tileResources["Iron"]!!, majorDeposit = true)
        game.getTile(-9, -9).setTileResource(game.ruleset.tileResources["Coal"]!!, majorDeposit = false)
        game.setTileFeatures(HexCoord.of(4, 0), "Forest")
        game.getTile(4, 0).setTileResource(game.ruleset.tileResources["Uranium"]!!, majorDeposit = false)
        tileData = TileDataMap(game.tileMap.values.size)
        for (tile in game.tileMap.values) tileData[tile] = MapGenTileData(tile, region, game.ruleset)
        sites = MapGenerationDiagnostics.RegionResult(region.startPosition, region.tiles.map { it.position }.toSet(),
            region.type, true, linkedMapOf(HexCoord.of(0, 0) to HexCoord.of(0, 1), HexCoord.of(5, 0) to HexCoord.of(5, 1)))
    }

    private fun report(citySites: List<MapGenerationDiagnostics.RegionResult> = listOf(sites)): String =
        MapGenerationReport.describe(game.tileMap, listOf(region), tileData, citySites)

    @Test
    fun listsCivilizationCitiesResourcesAndLuxuryAssignmentsWithoutMutations() {
        val before = game.tileMap.values.map { listOf(it.resource, it.resourceAmount, it.baseTerrain, it.terrainFeatures) }
        val output = report()
        assertTrue(output.contains("Civilization: Rome (id=Rome, region=Grassland)"))
        assertTrue(output.contains("Capital (0, 0) -> luxury: Salt at (0, 1)"))
        assertTrue(output.contains("City 2 (5, 0) -> luxury: Silk at (5, 1)"))
        assertTrue(output.contains("Iron x6 at (1, 0) [in region; in work range: Capital]"))
        assertTrue(output.contains("Coal x3 at (-9, -9) [in region; in work range: none]"))
        assertTrue(output.contains("Regional luxury: Salt"))
        val extraLuxuries = output.substringAfter("Additional luxuries (other types):")
        assertTrue(extraLuxuries.contains("Gems at (6, 0) [outside region; in work range: City 2]"))
        assertTrue(extraLuxuries.contains("Silk at (5, 1)"))
        assertFalse(extraLuxuries.contains("Salt"))
        assertEquals(before, game.tileMap.values.map { listOf(it.resource, it.resourceAmount, it.baseTerrain, it.terrainFeatures) })
        assertEquals(output, report())
    }

    @Test
    fun reportsAnExistingDepositAndTheSelectedCityThatCanUseIt() {
        assertTrue(report().contains("Uranium x2 at (4, 0) [already present; via City 2]"))
    }

    @Test
    fun recordsAddedDepositProvenanceWithoutRelabellingItOnRepeatedReports() {
        game.getTile(4, 0).tileResource = null
        RegionalStrategicBalancePlacement.placeResources(game.tileMap, listOf(region), tileData)
        assertEquals(HexCoord.of(4, 0), region.additionalStrategicPlacements["Uranium"])
        val output = report()
        assertTrue(output.contains("Uranium x2 at (4, 0) [added by regional balance; via City 2]"))
        assertEquals(output, report())
    }

    @Test
    fun distinguishesAnAlternativeSiteFromTheActualSelectedCitySet() {
        val output = report(listOf(sites.copy(cityLuxuries = mapOf(HexCoord.of(0, 0) to HexCoord.of(0, 1)))))
        assertTrue(output.contains("via alternative viable site"))
        assertTrue(output.contains("(not in selected city set)"))
        assertFalse(output.contains("via City 2"))
    }

    @Test
    fun resourceNearCityStateIsListedButNotCreditedToRegionalBalance() {
        val minor = game.addCiv(game.ruleset.nations.values.first { it.isCityState })
        game.tileMap.addStartingLocation(minor.civID, game.getTile(6, 0))
        val output = report()
        assertTrue(output.contains("Uranium x2 at (4, 0) [in region; in work range: City 2]"))
        assertTrue(output.contains("Uranium: NOT FOUND in accessible settlement area"))
        assertFalse(output.contains("already present"))
    }

    @Test
    fun reportsMissingResourceInsteadOfAStalePlacementRecord() {
        region.additionalStrategicPlacements["Uranium"] = HexCoord.of(4, 0)
        game.getTile(4, 0).tileResource = null
        val output = report()
        assertTrue(output.contains("Uranium: NOT FOUND in accessible settlement area"))
        assertFalse(output.contains("Uranium x"))
    }

    @Test
    fun failedAndUnevaluatedCitySitesAreNotPresentedAsACompleteLayout() {
        assertTrue(report(listOf(sites.copy(passed = false))).contains("City sites: failed; only partial selection shown"))
        val output = report(emptyList())
        assertTrue(output.contains("City sites: not evaluated on this map"))
        assertFalse(output.contains("City 2"))
    }

    @Test
    fun additionalResourceSectionExplainsDisabledAndInvalidConfiguration() {
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf("Missing", "Salt")
        assertTrue(report().contains("Missing: ignored (unknown or non-strategic resource)"))
        assertTrue(report().contains("Salt: ignored (unknown or non-strategic resource)"))
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf()
        assertTrue(report().contains("disabled (empty list)"))
        game.tileMap.mapParameters.strategicBalance = false
        assertTrue(report().contains("disabled (Strategic Balance is off)"))
    }

    @Test
    fun successfulGenerationPrintsOneReportWithoutAnExplicitDiagnosticsCollector() {
        val parameters = MapParameters().apply {
            type = MapType.pangaea
            mapSize = MapSize.Medium
            seed = 1
            noNaturalWonders = true
        }
        game.ruleset.modOptions.constants.citySiteMinAerialDistance = 0
        game.ruleset.modOptions.constants.minWorkableTilesPerCitySite = 0
        val output = captureOutput {
            val map = MapGenerator(game.ruleset).generateMap(parameters, game.gameInfo.gameParameters, game.gameInfo)
            val capital = map.startingLocationsByNation.values.flatten().single().position
            println("Expected capital: (${capital.x}, ${capital.y})")
        }
        assertEquals(1, output.lineSequence().count { it.startsWith("Map generation report:") })
        assertTrue(output.contains("succeeded"))
        assertTrue(output.contains("fallback=false"))
        assertTrue(output.contains("City sites: passed"))
        val expectedCapital = output.substringAfter("Expected capital: ").trim()
        assertTrue(output.contains("  Capital: $expectedCapital"))
    }

    @Test
    fun fallbackPrintsReturnedAttemptOnlyAndKeepsItsOriginalSeed() {
        val ore = game.createResource("Doesn't generate naturally")
        ore.resourceType = ResourceType.Strategic
        ore.terrainsCanBeFoundOn = listOf("Grassland")
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf(ore.name)
        game.ruleset.modOptions.constants.maxPangaeaCitySiteRetries = 3
        val parameters = MapParameters().apply {
            type = MapType.fractal // No city-site ranking: first attempt wins fallback ties.
            mapSize = MapSize.Medium
            strategicBalance = true
            noNaturalWonders = true
            seed = 1
        }
        val diagnostics = MapGenerationDiagnostics()
        val output = captureOutput {
            val map = MapGenerator(game.ruleset).generateMap(parameters, game.gameInfo.gameParameters, game.gameInfo, diagnostics)
            val capital = map.startingLocationsByNation.values.flatten().single().position
            println("Expected capital: (${capital.x}, ${capital.y})")
        }
        assertEquals(3, diagnostics.attempts.size)
        assertEquals(1, diagnostics.selectedAttempt)
        assertEquals(1, output.lineSequence().count { it.startsWith("Map generation report:") })
        val actualReport = diagnostics.selectedMapReport!!
        assertTrue(actualReport.startsWith("Map generation report: attempt=1, seed=1, fallback=true"))
        assertTrue(output.contains(actualReport))
        assertTrue(actualReport.contains("City sites: not evaluated on this map"))
        val expectedCapital = output.substringAfter("Expected capital: ").trim()
        assertTrue(actualReport.contains("  Capital: $expectedCapital"))
        assertTrue(actualReport.contains("${ore.name}: NOT FOUND in accessible settlement area"))
    }

    @Test
    fun reusingDiagnosticsForEditorMapClearsOldReportAndPrintsNoCivAssignments() {
        val diagnostics = MapGenerationDiagnostics()
        val parameters = MapParameters().apply {
            type = MapType.fractal
            seed = 1
        }
        captureOutput {
            MapGenerator(game.ruleset).generateMap(parameters, game.gameInfo.gameParameters, game.gameInfo, diagnostics)
        }
        assertTrue(diagnostics.selectedMapReport!!.contains("City sites: not evaluated on this map"))
        val editorOutput = captureOutput {
            MapGenerator(game.ruleset).generateMap(parameters, diagnostics = diagnostics)
        }
        assertEquals(null, diagnostics.selectedMapReport)
        assertFalse(editorOutput.contains("Map generation report:"))
    }

    private fun captureOutput(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val previous = System.out
        val stream = PrintStream(buffer)
        try {
            System.setOut(stream)
            block()
        } finally {
            System.setOut(previous)
            stream.close()
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}
