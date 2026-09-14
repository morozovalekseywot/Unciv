package com.unciv.logic.map

import com.badlogic.gdx.math.Rectangle
import com.unciv.logic.map.mapgenerator.MapGenerationDiagnostics
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.logic.map.mapgenerator.mapregions.MapGenTileData
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.tile.Tile
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

    @Test
    fun balancedMediumPangaea() = runBatch("balanced-medium", MapSize.Predefined.Medium, 4, 8, true)

    @Test
    fun balancedCrowdedMediumPangaea() = runBatch("balanced-crowded-medium", MapSize.Predefined.Medium, 8, 12, true)

    @Test
    fun balancedLargePangaea() = runBatch("balanced-large", MapSize.Predefined.Large, 8, 16, true)

    private fun runBatch(scenario: String, size: MapSize.Predefined, majors: Int, minors: Int, strategicBalance: Boolean = false) {
        val rows = arrayListOf(
            "scenario,initial_seed,game_id,attempts,selected_attempt,selected_seed,fallback," +
                "satisfied_regions,total_regions,selected_sites,luxury_deposits,luxury_types,milliseconds,attempt_seeds," +
                "regions_with_regional_strategics"
        )
        var totalAttempts = 0
        var fallbacks = 0
        var satisfiedRegions = 0
        var totalMillis = 0L
        var totalDeposits = 0
        var totalTypes = 0
        var totalRegionsWithStrategics = 0
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
                this.strategicBalance = strategicBalance
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
            val fallback = selected.satisfiedRegions < majors || !selected.additionalStrategicResourcesSatisfied
            val luxuryTiles = map.values.filter { it.tileResource?.resourceType == ResourceType.Luxury }
            val luxuryTypes = luxuryTiles.map { it.resource }.toSet().size
            val regionsWithStrategics = countRegionsWithStrategicAccess(map, testGame.ruleset, selected)
            val row = listOf(
                scenario, initialSeed, gameInfo.gameId, diagnostics.attempts.size, diagnostics.selectedAttempt,
                selected.seed, fallback, selected.satisfiedRegions, majors,
                selected.regions.sumOf { it.cityLuxuries.size }, luxuryTiles.size, luxuryTypes, elapsedMillis,
                diagnostics.attempts.joinToString("|") { it.seed.toString() }, regionsWithStrategics
            ).joinToString(",")
            rows.add(row)
            report.writeText(rows.joinToString("\n", postfix = "\n"))
            println("MAP_SAMPLE $row")

            val selectedReport = diagnostics.selectedMapReport!!
            assertTrue(context, selectedReport.startsWith("Map generation report: attempt=${diagnostics.selectedAttempt}, " +
                "seed=${selected.seed}, fallback=$fallback"))
            assertEquals(context, majors, selectedReport.lineSequence().count { it.startsWith("Civilization:") })
            for (region in selected.regions) {
                val capital = region.capital!!
                assertTrue(context, selectedReport.contains("  Capital: (${capital.x}, ${capital.y})"))
            }
            // Keep one real console report per scenario for manual inspection, alongside the CSV.
            File(report.parentFile, "$scenario-last-map.txt").writeText(selectedReport + "\n")

            assertTrue(context, diagnostics.attempts.size <= constants.maxPangaeaCitySiteRetries)
            for (attempt in diagnostics.attempts) {
                assertEquals(context, majors, attempt.regions.size)
                assertEquals(context, attempt.regions.count { it.passed }, attempt.satisfiedRegions)
            }
            // A fully successful map wins immediately, even if an earlier map had the same city-site
            // score but lacked uranium. Only fallback selection ignores strategic-resource coverage.
            if (fallback) {
                assertEquals("$context: exhaust retries before fallback", constants.maxPangaeaCitySiteRetries,
                    diagnostics.attempts.size)
                val bestScore = diagnostics.attempts.maxOf { it.satisfiedRegions }
                assertEquals("$context: fallback keeps the first best city-site score", diagnostics.attempts.indexOfFirst {
                    it.satisfiedRegions == bestScore
                } + 1, diagnostics.selectedAttempt)
            } else
                assertEquals("$context: stop on success", diagnostics.attempts.size, diagnostics.selectedAttempt)
            for (attempt in diagnostics.attempts.dropLast(if (fallback) 0 else 1))
                assertTrue("$context: must stop at the first complete success",
                    attempt.satisfiedRegions < majors || !attempt.additionalStrategicResourcesSatisfied)
            assertEquals("$context: do not resize this scenario", size.radius, map.mapParameters.mapSize.radius)
            verifySelectedSites(map, testGame.ruleset, selected, size.minCitySitesPerCiv,
                "$context selectedSeed=${selected.seed}")
            if (strategicBalance) {
                assertEquals("$context: independent resource check must agree with the generator",
                    regionsWithStrategics == majors, selected.additionalStrategicResourcesSatisfied)
                if (!fallback)
                    assertEquals("$context selectedSeed=${selected.seed}: success requires additional strategics for everyone",
                        majors, regionsWithStrategics)
            }

            totalAttempts += diagnostics.attempts.size
            if (fallback) fallbacks++
            satisfiedRegions += selected.satisfiedRegions
            totalMillis += elapsedMillis
            totalDeposits += luxuryTiles.size
            totalTypes += luxuryTypes
            totalRegionsWithStrategics += regionsWithStrategics
        }
        println("MAP_SUMMARY $scenario: maps=10, fallback=$fallbacks/10, " +
            "satisfiedRegions=$satisfiedRegions/${10 * majors}, meanAttempts=${totalAttempts / 10.0}, " +
            "meanDeposits=${totalDeposits / 10.0}, meanTypes=${totalTypes / 10.0}, " +
            "meanMillis=${totalMillis / 10.0}, regionsWithStrategics=$totalRegionsWithStrategics/${10 * majors}, " +
            "report=${report.canonicalPath}")
    }

    /** Independent check on the final returned map, including regions rejected by the luxury-site check.
     *  Does not call the placement helper or trust its candidate lists. */
    private fun countRegionsWithStrategicAccess(
        map: TileMap,
        ruleset: Ruleset,
        attempt: MapGenerationDiagnostics.Attempt
    ): Int {
        val constants = ruleset.modOptions.constants
        val resources = constants.additionalRegionalStrategicBalanceResources.distinct().filter {
            ruleset.tileResources[it]?.resourceType == ResourceType.Strategic
        }
        val starts = map.startingLocationsByNation.values.flatten().toSet()
        val regions = attempt.regions.map { result ->
            Region(map, Rectangle()).apply {
                type = result.type
                startPosition = result.capital
                tiles.addAll(result.tiles.map { map[it] })
            }
        }
        val usableTiles = map.values.filter { tile ->
            tile.isLand && !tile.isImpassible() && (tile.resource != null ||
                !MapGenTileData(tile, regions.firstOrNull { tile in it.tiles }, ruleset).isJunk)
        }.toSet()
        var satisfied = 0
        for (region in regions) {
            val start = map[region.startPosition!!]
            val foreign = starts.filter { it != start }
            val land = region.tiles.filter { tile ->
                tile.isLand && !tile.isImpassible() && !tile.isNaturalWonder() &&
                    foreign.none { tile.aerialDistanceTo(it) <= constants.cityWorkRange }
            }
            if (start !in land) continue
            // Deliberately use a simple fixed-point flood fill, independent of the production traversal.
            val connected = hashSetOf(start)
            do {
                val previousSize = connected.size
                connected.addAll(land.filter { tile -> tile.neighbors.any { it in connected } })
            } while (connected.size != previousSize)
            val sites = ArrayList<Tile>()
            sites.add(start)
            for (site in connected) {
                if (site == start || site !in usableTiles) continue
                if (site.aerialDistanceTo(start) < maxOf(constants.citySiteMinAerialDistance,
                        constants.minimalCityDistance + 1)) continue
                if (foreign.any { site.aerialDistanceTo(it) < maxOf(constants.citySiteMinAerialDistance,
                        constants.minimalCityDistance + 1, constants.foreignCapitalSettlementProtectionRadius + 1) }) continue
                val neighborhood = map.values.filter { site.aerialDistanceTo(it) <= constants.cityWorkRange }
                if (neighborhood.none { luxury -> luxury.tileResource?.resourceType == ResourceType.Luxury &&
                        foreign.none { luxury.aerialDistanceTo(it) <= constants.cityWorkRange } }) continue
                if (neighborhood.count { it != site && it in usableTiles } < constants.minWorkableTilesPerCitySite) continue
                sites.add(site)
            }
            if (resources.all { name ->
                    connected.any { tile -> tile.resource == name && tile.resourceAmount > 0 &&
                        sites.any { it.aerialDistanceTo(tile) <= constants.cityWorkRange } }
                }) satisfied++
        }
        return satisfied
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

        val allSites = attempt.regions.flatMap { it.cityLuxuries.keys }.map { map[it] }
        val allDeposits = attempt.regions.flatMap { it.cityLuxuries.values }
        assertEquals("$context: distinct city centers across civilizations", allSites.size, allSites.toSet().size)
        assertEquals("$context: distinct deposits across civilizations", allDeposits.size, allDeposits.toSet().size)
        for ((index, region) in attempt.regions.withIndex()) {
            val message = "$context region=$index capital=${region.capital}"
            assertTrue("$message: capital is an actual start", starts.any { it.position == region.capital })
            if (region.passed)
                assertTrue("$message: enough sites", region.cityLuxuries.size >= requiredSites)
            if (region.cityLuxuries.isEmpty()) continue
            assertTrue("$message: include the capital", region.capital in region.cityLuxuries.keys)
            assertEquals("$message: separate deposits for each city", region.cityLuxuries.size,
                region.cityLuxuries.values.toSet().size)
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
                assertTrue("$siteMessage: luxury outside foreign capitals' and city-states' work ranges",
                    foreignStarts.none { luxury.aerialDistanceTo(it) <= constants.cityWorkRange })
                for (otherRegion in attempt.regions) {
                    if (otherRegion == region) continue
                    assertTrue("$siteMessage: luxury is not another civilization's city center",
                        luxuryPosition !in otherRegion.cityLuxuries.keys)
                    if (sitePosition == region.capital) continue
                    val otherCapital = otherRegion.capital
                    if (otherCapital == null) continue
                    assertTrue("$siteMessage: outside foreign capital's peace-time settlement protection",
                        site.aerialDistanceTo(map[otherCapital]) > constants.foreignCapitalSettlementProtectionRadius)
                }
                val workableCount = usableTiles.count {
                    it != site && site.aerialDistanceTo(it) <= constants.cityWorkRange
                }
                assertTrue("$siteMessage: workable tiles=$workableCount",
                    workableCount >= constants.minWorkableTilesPerCitySite)
                for (other in allSites + foreignStarts) {
                    if (other == site) continue
                    assertTrue("$siteMessage: too close to ${other.position}",
                        site.aerialDistanceTo(other) >= maxOf(constants.citySiteMinAerialDistance,
                            constants.minimalCityDistance + 1))
                }
            }
        }
    }
}
