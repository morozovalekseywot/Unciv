package com.unciv.logic.map.mapgenerator.resourceplacement

import com.unciv.logic.map.TileMap
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.mapgenerator.mapregions.MapRegions.ImpactType
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.RegionCitySiteValidator
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.utils.Log

/** Regional top-ups, separate from the large capital deposits supplied by StrategicBalanceResource.
 *  Run after normal strategic resources, luxuries and city-states, but before bonus resources.
 *  Existing accessible deposits count first, and bonus resources cannot fill the last free tile.
 *  Does not overwrite resources, terraform, or ignore a mod's natural-generation restrictions.
 */
object RegionalStrategicBalancePlacement {
    internal fun isEnabled(parameters: MapParameters, ruleset: Ruleset): Boolean =
        parameters.getStrategicBalance() && getResources(ruleset).isNotEmpty()

    private fun getResources(ruleset: Ruleset): List<TileResource> =
        ruleset.modOptions.constants.additionalRegionalStrategicBalanceResources.distinct()
            .mapNotNull { ruleset.tileResources[it] }.filter { it.resourceType == ResourceType.Strategic }

    /** Recheck after final tile normalization. Missing resources trigger retries but do not change
     *  the city-site score used to choose a fallback map after the retry limit. */
    internal fun allRegionsHaveResources(tileMap: TileMap, regions: List<Region>, tileData: TileDataMap): Boolean {
        if (!tileMap.mapParameters.getStrategicBalance()) return true
        val resources = getResources(tileMap.ruleset!!)
        if (resources.isEmpty()) return true
        val allStarts = tileMap.startingLocationsByNation.values.flatten().toSet()
        return regions.all { region ->
            val accessible = getAccessibleTiles(region, tileData, allStarts.filter { it.position != region.startPosition })
            resources.all { resource -> accessible.any { it.resource == resource.name && it.resourceAmount > 0 } }
        }
    }

    fun placeResources(tileMap: TileMap, regions: List<Region>, tileData: TileDataMap) {
        if (!tileMap.mapParameters.getStrategicBalance()) return
        val ruleset = tileMap.ruleset!!
        val resources = getResources(ruleset)
        if (resources.isEmpty()) return
        val allStarts = tileMap.startingLocationsByNation.values.flatten().toSet()

        for (region in regions) {
            val startPosition = region.startPosition
            if (startPosition == null) continue
            val start = tileMap[startPosition]
            val accessibleTiles = getAccessibleTiles(region, tileData, allStarts.filter { it != start })
            val rng = GameContext(tile = start).stateBasedRandom("RegionalStrategicBalancePlacement")
            for (resource in resources) {
                if (accessibleTiles.any { it.resource == resource.name && it.resourceAmount > 0 }) continue
                // Prefer an unoccupied natural tile with little strategic clustering. Unlike normal
                // distribution, an impact cannot veto this last-chance small regional deposit.
                val candidates = accessibleTiles.asSequence()
                    .filter { it.resource == null && !it.isNaturalWonder() && resource.generatesNaturallyOn(it) }
                    .shuffled(rng).sortedBy { tileData[it]?.impacts?.get(ImpactType.Strategic) ?: 0 }
                val deposit = candidates.firstOrNull()
                if (deposit == null) {
                    Log.debug("Regional strategic balance: no suitable free tile for %s near %s " +
                        "(accessible=%d, natural=%d, free=%d)", resource.name, startPosition,
                        accessibleTiles.size, accessibleTiles.count { resource.generatesNaturallyOn(it) },
                        accessibleTiles.count { it.resource == null })
                    continue
                }
                deposit.setTileResource(resource, majorDeposit = false)
                region.additionalStrategicPlacements[resource.name] = deposit.position
                tileData.placeImpact(ImpactType.Strategic, deposit, 1)
            }
        }
    }

    /** A conservative settlement area: connected land within this region, excluding foreign starts'
     *  work ranges. Deposits must be reachable from the capital without crossing another region and
     *  within work range of either the capital or a viable expansion site that can coexist with it.
     *  We do not promise that every possible future choice of cities will claim these tiles.
     */
    internal fun getAccessibleTiles(
        region: Region,
        tileData: TileDataMap,
        foreignStarts: Collection<Tile>,
        eligibleCitySites: MutableCollection<Tile>? = null
    ): Set<Tile> {
        eligibleCitySites?.clear()
        val startPosition = region.startPosition
        if (startPosition == null) return emptySet()
        val tileMap = region.tileMap
        val start = tileMap[startPosition]
        val constants = tileMap.ruleset!!.modOptions.constants
        val workRange = constants.cityWorkRange
        val passable = region.tiles.filterTo(HashSet()) { tile ->
            tile.isLand && !tile.isImpassible() && !tile.isNaturalWonder() &&
                foreignStarts.none { tile.aerialDistanceTo(it) <= workRange }
        }
        if (start !in passable) return emptySet()
        val connected = HashSet<Tile>()
        val pending = ArrayDeque<Tile>()
        connected.add(start)
        pending.add(start)
        while (pending.isNotEmpty()) {
            val tile = pending.removeFirst()
            for (neighbor in tile.neighbors)
                if (neighbor in passable && connected.add(neighbor)) pending.add(neighbor)
        }

        val minDistance = maxOf(constants.citySiteMinAerialDistance, constants.minimalCityDistance + 1)
        // Peace-time capital protection includes the tile exactly at the configured radius.
        val foreignDistance = maxOf(minDistance, constants.foreignCapitalSettlementProtectionRadius + 1)
        val sites = RegionCitySiteValidator.findCandidates(region, tileData,
            constants.minWorkableTilesPerCitySite, foreignDistance, workRange, foreignStarts)
            .asSequence().map { it.tile }
            .filter { it in connected && it.aerialDistanceTo(start) >= minDistance }
        val accessible = HashSet<Tile>()
        for (site in sequenceOf(start) + sites) {
            eligibleCitySites?.add(site)
            site.forEachTileInDistance(workRange) { if (it in connected) accessible.add(it) }
        }
        return accessible
    }
}
