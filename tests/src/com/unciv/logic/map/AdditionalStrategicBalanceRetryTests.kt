package com.unciv.logic.map

import com.unciv.logic.map.mapgenerator.MapGenerationDiagnostics
import com.unciv.logic.map.mapgenerator.MapGenerator
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AdditionalStrategicBalanceRetryTests {
    private val game = TestGame()
    private val diagnostics = MapGenerationDiagnostics()

    init {
        game.addCiv()
        // A valid modded resource whose placement is impossible, to force a predictable retry failure.
        val ore = game.createResource("Doesn't generate naturally")
        ore.resourceType = ResourceType.Strategic
        ore.terrainsCanBeFoundOn = listOf("Grassland", "Plains", "Forest")
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf(ore.name)
        game.ruleset.modOptions.constants.maxPangaeaCitySiteRetries = 3
    }

    private fun generate(type: String = MapType.pangaea, balanced: Boolean = true): TileMap {
        val parameters = MapParameters().apply {
            this.type = type
            mapSize = MapSize.Medium
            strategicBalance = balanced
            noNaturalWonders = true
            seed = 1
        }
        return MapGenerator(game.ruleset).generateMap(parameters, game.gameInfo.gameParameters,
            game.gameInfo, diagnostics)
    }

    @Test
    fun missingResourceRetriesEvenWhenAllCitySitesPassAndKeepsOriginalFallbackScore() {
        // Remove geometric/quality bottlenecks to isolate the additional-resource check.
        game.ruleset.modOptions.constants.citySiteMinAerialDistance = 0
        game.ruleset.modOptions.constants.minWorkableTilesPerCitySite = 0
        val map = generate()
        assertEquals(3, diagnostics.attempts.size)
        assertTrue("The resource check must reject an otherwise successful map",
            diagnostics.attempts.any { it.satisfiedRegions == 1 })
        assertTrue(diagnostics.attempts.all { !it.additionalStrategicResourcesSatisfied })
        val bestScore = diagnostics.attempts.maxOf { it.satisfiedRegions }
        assertEquals(diagnostics.attempts.indexOfFirst { it.satisfiedRegions == bestScore } + 1,
            diagnostics.selectedAttempt)
        val selected = diagnostics.attempts[diagnostics.selectedAttempt - 1]
        val starts = map.startingLocationsByNation.values.flatten().map { it.position }.toSet()
        assertEquals(selected.regions.map { it.capital }.toSet(), starts)
    }

    @Test
    fun missingResourceOnOtherMapTypesUsesSameRetryLimitAndKeepsFirstMap() {
        generate(MapType.fractal)
        assertEquals(3, diagnostics.attempts.size)
        assertTrue(diagnostics.attempts.all { it.satisfiedRegions == -1 && !it.additionalStrategicResourcesSatisfied })
        assertEquals(1, diagnostics.selectedAttempt)
    }

    @Test
    fun strategicBalanceOffDoesNotTriggerAdditionalRetries() {
        generate(MapType.fractal, balanced = false)
        assertEquals(1, diagnostics.attempts.size)
        assertTrue(diagnostics.attempts.single().additionalStrategicResourcesSatisfied)
    }

    @Test
    fun emptyListDisablesAdditionalRetries() {
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf()
        generate(MapType.fractal)
        assertEquals(1, diagnostics.attempts.size)
        assertTrue(diagnostics.attempts.single().additionalStrategicResourcesSatisfied)
    }

    @Test
    fun nonexistentAndNonStrategicResourcesDoNotTriggerRetries() {
        game.ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources = arrayListOf("Missing", "Salt")
        generate(MapType.fractal)
        assertEquals(1, diagnostics.attempts.size)
    }

    @Test
    fun configuredSingleAttemptMayFallBackWithMissingResources() {
        game.ruleset.modOptions.constants.maxPangaeaCitySiteRetries = 1
        generate()
        assertEquals(1, diagnostics.attempts.size)
        assertEquals(1, diagnostics.selectedAttempt)
        assertFalse(diagnostics.attempts.single().additionalStrategicResourcesSatisfied)
    }
}
