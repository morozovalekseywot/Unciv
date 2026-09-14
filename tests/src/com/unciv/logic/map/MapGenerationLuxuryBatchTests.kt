package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.MapGenerationDiagnostics
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Integration checks plus a balance baseline, not a statistical pass-rate requirement.
 *  Seeds identify the initial terrain, but generation also uses other random sources and time-based retries.
 *  Reports therefore record every attempted seed; exact replay is not currently guaranteed.
 */
@RunWith(BaseTestRunner::class)
class MapGenerationLuxuryBatchTests {
    @Test
    fun mediumPangaea() = runBatch("medium", MapSize.Predefined.Medium, 4, 8)

    @Test
    fun crowdedMediumPangaea() = runBatch("crowded-medium", MapSize.Predefined.Medium, 8, 12)

    @Test
    fun largePangaea() = runBatch("large", MapSize.Predefined.Large, 8, 16)

    private fun runBatch(scenario: String, size: MapSize.Predefined, majors: Int, minors: Int) {
        val rows = arrayListOf(
            "scenario,initial_seed,game_id,attempts,selected_attempt,selected_seed,fallback," +
                "satisfied_regions,total_regions,selected_sites,luxury_deposits,luxury_types,milliseconds,attempt_seeds"
        )
        var totalAttempts = 0
        var fallbacks = 0
        var satisfiedRegions = 0
        var totalMillis = 0L
        var totalDeposits = 0
        var totalTypes = 0
        // Gradle runs tests from android/assets, as do the project's other ruleset-backed tests.
        val report = File("../../tests/build/reports/map-generation-luxuries/$scenario.csv")
        report.parentFile.mkdirs()

        for (initialSeed in 1L..10L) {
            val testGame = TestGame()
            val constants = testGame.ruleset.modOptions.constants
            constants.pangaeaCitySiteGuarantee = true
            val gameInfo = testGame.gameInfo
            gameInfo.gameId = "luxury-batch-$scenario-$initialSeed"
            repeat(majors) { testGame.addCiv() }
            val cityStates = testGame.ruleset.nations.values.filter { it.isCityState }.take(minors)
            assertEquals("Enough city-state nations in the test ruleset", minors, cityStates.size)
            for (nation in cityStates) testGame.addCiv(nation)

            val parameters = MapParameters().apply {
                type = MapType.pangaea
                shape = MapShape.hexagonal
                mapSize = MapSize(size)
                mapResources = MapResourceSetting.default.label
                dynamicMapSizeForCivCount = false
                noNaturalWonders = true
                seed = initialSeed
            }
            val diagnostics = MapGenerationDiagnostics()
            val start = System.nanoTime()
            val map = MapGenerator(testGame.ruleset).generateMap(
                parameters, gameInfo.gameParameters, gameInfo, diagnostics
            )
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000
            val context = "$scenario initialSeed=$initialSeed gameId=${gameInfo.gameId}"
            assertTrue(context, diagnostics.attempts.isNotEmpty())
            assertTrue(context, diagnostics.selectedAttempt in 1..diagnostics.attempts.size)
            val selected = diagnostics.attempts[diagnostics.selectedAttempt - 1]
            val fallback = selected.satisfiedRegions < majors
            val luxuryTiles = map.values.filter { it.tileResource?.resourceType == ResourceType.Luxury }
            val luxuryTypes = luxuryTiles.map { it.resource }.toSet().size
            val row = listOf(
                scenario, initialSeed, gameInfo.gameId, diagnostics.attempts.size, diagnostics.selectedAttempt,
                selected.seed, fallback, selected.satisfiedRegions, majors,
                selected.regions.sumOf { it.cityLuxuries.size }, luxuryTiles.size, luxuryTypes, elapsedMillis,
                diagnostics.attempts.joinToString("|") { it.seed.toString() }
            ).joinToString(",")
            rows.add(row)
            report.writeText(rows.joinToString("\n", postfix = "\n"))
            println("MAP_SAMPLE $row")

            assertTrue(context, diagnostics.attempts.size <= constants.maxPangaeaCitySiteRetries)
            for (attempt in diagnostics.attempts) {
                assertEquals(context, majors, attempt.regions.size)
                assertEquals(context, attempt.regions.count { it.passed }, attempt.satisfiedRegions)
            }
            val bestScore = diagnostics.attempts.maxOf { it.satisfiedRegions }
            assertEquals("$context: return the first best attempt", diagnostics.attempts.indexOfFirst {
                it.satisfiedRegions == bestScore
            } + 1, diagnostics.selectedAttempt)
            if (fallback)
                assertEquals("$context: exhaust retries before fallback", constants.maxPangaeaCitySiteRetries,
                    diagnostics.attempts.size)
            else
                assertEquals("$context: stop on success", diagnostics.attempts.size, diagnostics.selectedAttempt)
            assertEquals("$context: do not resize this scenario", size.radius, map.mapParameters.mapSize.radius)
            verifySelectedSites(map, testGame.ruleset, selected, size.minCitySitesPerCiv,
                "$context selectedSeed=${selected.seed}")

            totalAttempts += diagnostics.attempts.size
            if (fallback) fallbacks++
            satisfiedRegions += selected.satisfiedRegions
            totalMillis += elapsedMillis
            totalDeposits += luxuryTiles.size
            totalTypes += luxuryTypes
        }
        println("MAP_SUMMARY $scenario: maps=10, fallback=$fallbacks/10, " +
            "satisfiedRegions=$satisfiedRegions/${10 * majors}, meanAttempts=${totalAttempts / 10.0}, " +
            "meanDeposits=${totalDeposits / 10.0}, meanTypes=${totalTypes / 10.0}, " +
            "meanMillis=${totalMillis / 10.0}, report=${report.canonicalPath}")
    }

    /** Check the returned map, without rerunning the validator's candidate selection or matching algorithm. */
    private fun verifySelectedSites(
        map: TileMap,
        ruleset: Ruleset,
        attempt: MapGenerationDiagnostics.Attempt,
        requiredSites: Int,
        context: String
    ) {
        val constants = ruleset.modOptions.constants
        val starts = map.startingLocationsByNation.values.flatten().toSet()
        // Keep terrain-quality semantics (including region-dependent mod uniques), but independently
        // count neighborhoods on the final map instead of trusting the validator's cached counts.
        val regions = attempt.regions.map { result ->
            Region(map, Rectangle()).apply {
                type = result.type
                tiles.addAll(result.tiles.map { map[it] })
            }
        }
        val usableTiles = map.values.filter { tile ->
            tile.isLand && !tile.isImpassible() && (tile.resource != null ||
                !MapGenTileData(tile, regions.firstOrNull { tile in it.tiles }, ruleset).isJunk)
        }.toSet()

        for ((index, region) in attempt.regions.withIndex()) {
            val message = "$context region=$index capital=${region.capital}"
            assertTrue("$message: capital is an actual start", starts.any { it.position == region.capital })
            if (region.passed)
                assertTrue("$message: enough sites", region.cityLuxuries.size >= requiredSites)
            if (region.cityLuxuries.isEmpty()) continue
            assertTrue("$message: include the capital", region.capital in region.cityLuxuries.keys)
            assertEquals("$message: separate deposits for each city", region.cityLuxuries.size,
                region.cityLuxuries.values.toSet().size)
            val sites = region.cityLuxuries.keys.map { map[it] }
            val foreignStarts = starts.filter { it.position != region.capital }
            for ((sitePosition, luxuryPosition) in region.cityLuxuries) {
                val site = map[sitePosition]
                val luxury = map[luxuryPosition]
                val siteMessage = "$message site=$sitePosition luxury=$luxuryPosition"
                assertTrue("$siteMessage: center belongs to region", sitePosition in region.tiles)
                assertTrue("$siteMessage: usable city center", site in usableTiles)
                assertEquals(siteMessage, ResourceType.Luxury, luxury.tileResource?.resourceType)
                assertTrue("$siteMessage: luxury in work range",
                    site.aerialDistanceTo(luxury) <= constants.cityWorkRange)
                val workableCount = usableTiles.count {
                    it != site && site.aerialDistanceTo(it) <= constants.cityWorkRange
                }
                assertTrue("$siteMessage: workable tiles=$workableCount",
                    workableCount >= constants.minWorkableTilesPerCitySite)
                for (other in sites + foreignStarts) {
                    if (other == site) continue
                    assertTrue("$siteMessage: too close to ${other.position}",
                        site.aerialDistanceTo(other) >= constants.citySiteMinAerialDistance)
                }
            }
        }
    }
}
