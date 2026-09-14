package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType

/**
 *  Verifies that a [Region] has room for a required number of well-spaced city sites.
 *
 *  This is the check behind the "Pangaea city-site guarantee" (see [MapGenerator][com.unciv.logic.map.mapgenerator.MapGenerator]
 *  and [ModConstants][com.unciv.models.ModConstants]). It exists because the region-splitting in [MapRegions] only
 *  balances fertility, never geometric compactness - so on tight maps a region can be a narrow strip that cannot
 *  actually host more than one city, causing civs to spawn uncomfortably close to each other.
 *
 *  A tile counts as **usable** (worth working / a valid city center) if it is land, not impassible, and either:
 *   - carries a resource (a resource redeems even an otherwise bare Desert/Ice/Snow tile), or
 *   - is not "junk" per [MapGenTileData.isJunk]. Note that [MapGenTileData] already classifies e.g. a Desert **with
 *     a Hill/Forest/Oasis** as non-junk (it inspects the top terrain feature, not the bare base terrain), so only a
 *     *strictly empty* Desert/Ice/Snow tile - no feature, no resource - is treated as useless.
 *
 *  Usability/quality is evaluated **map-wide** (via the global [TileDataMap]), NOT scoped to the region - a city's
 *  work range routinely reaches past its own region's border, so scoping the quality check to `region.tiles` alone
 *  would under-count perfectly good neighboring land and cause spurious failures near region borders (this was a
 *  real bug in an earlier version of this validator).
 *
 *  A **city site** is a usable tile *inside the region* (a civ is expected to found its own cities within its own
 *  region) that also has at least [minWorkableTiles] usable tiles (from the whole map, any region) within [workRange].
 *  Each chosen site, including the capital, must also have its own luxury deposit within [workRange], including
 *  the city center. Deposits of the same resource type are allowed, but one tile cannot supply two chosen sites,
 *  even in different regions. Luxury tiles may be outside the region or on water, but not within a foreign
 *  start's work range. All regions share one selection so their proposed cities can coexist.
 *  Two chosen city sites must be at least [minAerialDistance] aerial tiles apart, mirroring the in-game minimal
 *  city distance so the sites represent cities that could actually coexist. Candidates too close to any
 *  [foreignStartTiles] (other civs'/city-states' starting positions) are rejected outright.
 *
 *  IMPORTANT: This must run **after** resources have been placed (so [Tile.resource] is populated) and after
 *  minor civs have been placed (so [foreignStartTiles] is complete).
 */
object RegionCitySiteValidator {

    /** @return true if [region] can host at least [requiredSites] city sites satisfying the spacing and quality rules.
     *  [selectedCityLuxuries], when provided, receives the selected centers and their distinct deposits.
     *  It is cleared on entry and may contain a partial selection when the check fails. */
    fun regionHasEnoughCitySites(
        region: Region,
        tileData: TileDataMap,
        requiredSites: Int,
        minWorkableTiles: Int,
        minAerialDistance: Int,
        workRange: Int,
        foreignStartTiles: Collection<Tile>,
        selectedCityLuxuries: MutableMap<Tile, Tile>? = null
    ): Boolean {
        selectedCityLuxuries?.clear()
        if (requiredSites <= 0) return true

        val allStarts = foreignStartTiles.toMutableSet()
        val startPosition = region.startPosition
        if (startPosition != null) allStarts.add(region.tileMap[startPosition])
        val selection = selectCitySites(listOf(region), tileData, requiredSites, minWorkableTiles,
            minAerialDistance, workRange, allStarts).single()
        selectedCityLuxuries?.putAll(selection)
        return selection.size >= requiredSites
    }

    /** Bounded greedy selection, not an exhaustive search. Reserve all capitals before expanding in
     *  rounds, rather than filling one region before considering the next region's expansion sites.
     *  Luxury matching is shared across regions and can move earlier assignments to alternatives.
     *  Results (including partial failed regions) are mutually compatible and in input region order.
     *  Materialize them only after matching finishes: later sites may change earlier assignments. */
    fun selectCitySites(
        regions: List<Region>,
        tileData: TileDataMap,
        requiredSites: Int,
        minWorkableTiles: Int,
        minAerialDistance: Int,
        workRange: Int,
        allStartTiles: Collection<Tile>,
        foreignCapitalProtectionRadius: Int = 0
    ): List<Map<Tile, Tile>> {
        if (requiredSites <= 0) return regions.map { emptyMap() }
        val candidates = regions.map { region ->
            val foreignCapitals = regions.asSequence().filter { it != region }
                .mapNotNull { it.startPosition }.map { region.tileMap[it] }.toList()
            findCandidates(region, tileData, minWorkableTiles, minAerialDistance, workRange,
                allStartTiles.filter { it.position != region.startPosition })
                .filter { candidate -> candidate.tile.position == region.startPosition ||
                    foreignCapitals.none { candidate.tile.aerialDistanceTo(it) <= foreignCapitalProtectionRadius } }
                .sortedWith(compareByDescending<CitySiteCandidate> { it.workableCount }
                    .thenBy { it.tile.position.x }.thenBy { it.tile.position.y })
        }
        val chosenByRegion = regions.map { ArrayList<CitySiteCandidate>() }
        val chosenCenters = HashMap<Tile, Region>()
        val assignments = HashMap<Tile, CitySiteCandidate>()
        val canExpand = BooleanArray(regions.size)
        for ((index, region) in regions.withIndex()) {
            val startPosition = region.startPosition
            if (startPosition == null) {
                canExpand[index] = true
                continue
            }
            val capital = candidates[index].firstOrNull { it.tile.position == startPosition }
            if (capital == null) continue
            chosenCenters[capital.tile] = region
            if (!assignLuxury(capital, assignments, HashSet(), chosenCenters)) {
                chosenCenters.remove(capital.tile)
                continue
            }
            chosenByRegion[index].add(capital)
            canExpand[index] = true
        }
        // Each successful iteration adds a city; at most regions.size * requiredSites are selected.
        do {
            var addedCity = false
            for (index in regions.indices) {
                if (!canExpand[index] || chosenByRegion[index].size >= requiredSites) continue
                for (candidate in candidates[index]) {
                    if (candidate.tile in chosenCenters) continue
                    if (chosenCenters.keys.any { it.aerialDistanceTo(candidate.tile) < minAerialDistance }) continue
                    // Founding a city must not consume a deposit already promised to another civilization.
                    val previous = assignments[candidate.tile]
                    if (previous != null && previous.region != candidate.region) continue
                    chosenCenters[candidate.tile] = candidate.region
                    if (!assignLuxury(candidate, assignments, HashSet(), chosenCenters)) {
                        chosenCenters.remove(candidate.tile)
                        continue
                    }
                    chosenByRegion[index].add(candidate)
                    addedCity = true
                    break
                }
            }
        } while (addedCity)
        val cityLuxuries = assignments.entries.associate { it.value.tile to it.key }
        return chosenByRegion.map { chosen -> chosen.associate { it.tile to cityLuxuries.getValue(it.tile) } }
    }

    /** Shared quality checks for city-site validation and regional strategic-resource access.
     *  Centers must be inside the region; their workable neighborhoods may cross its border. */
    internal fun findCandidates(
        region: Region,
        tileData: TileDataMap,
        minWorkableTiles: Int,
        minAerialDistance: Int,
        workRange: Int,
        foreignStartTiles: Collection<Tile>
    ): ArrayList<CitySiteCandidate> {
        val regionCandidateTiles = HashSet<Tile>()
        for (tile in region.tiles)
            if (isUsableTile(tile, tileData)) regionCandidateTiles.add(tile)

        val foreignCatchment = HashSet<Tile>()
        for (start in foreignStartTiles)
            start.forEachTileInDistance(workRange) { foreignCatchment.add(it) }

        val candidates = ArrayList<CitySiteCandidate>()
        for (tile in regionCandidateTiles) {
            if (foreignStartTiles.any { tile.aerialDistanceTo(it) < minAerialDistance }) continue
            var workableCount = 0
            val luxuryTiles = ArrayList<Tile>()
            tile.forEachTileInDistance(workRange) { workTile ->
                if (workTile != tile && isUsableTile(workTile, tileData)) workableCount++
                if (workTile.tileResource?.resourceType == ResourceType.Luxury && workTile !in foreignCatchment)
                    luxuryTiles.add(workTile)
            }
            if (workableCount >= minWorkableTiles && luxuryTiles.isNotEmpty())
                candidates.add(CitySiteCandidate(tile, workableCount, luxuryTiles, region))
        }
        return candidates
    }

    /** Find a distinct deposit for this site, moving earlier assignments if their sites have alternatives.
     *  Only successful paths change assignments, so a rejected candidate leaves the chosen sites supplied.
     *  The search is bounded by the small number of chosen city sites, not all candidate city locations. */
    private fun assignLuxury(
        candidate: CitySiteCandidate,
        assignments: MutableMap<Tile, CitySiteCandidate>,
        visited: MutableSet<Tile>,
        chosenCenters: Map<Tile, Region>
    ): Boolean {
        for (luxuryTile in candidate.luxuryTiles) {
            val centerRegion = chosenCenters[luxuryTile]
            if (centerRegion != null && centerRegion != candidate.region) continue
            if (!visited.add(luxuryTile)) continue
            val previous = assignments[luxuryTile]
            if (previous != null && !assignLuxury(previous, assignments, visited, chosenCenters)) continue
            assignments[luxuryTile] = candidate
            return true
        }
        return false
    }

    private fun isUsableTile(tile: Tile, tileData: TileDataMap): Boolean {
        if (!tile.isLand || tile.isImpassible()) return false
        // A resource redeems even a bare Desert/Ice tile
        if (tile.resource != null) return true
        val data = tileData[tile]
        if (data == null) return false
        // isJunk is false for e.g. Desert+Hill/Forest/Oasis (feature-based), true only for strictly empty desert/ice/snow
        return !data.isJunk
    }

    internal class CitySiteCandidate(val tile: Tile, val workableCount: Int, val luxuryTiles: List<Tile>, val region: Region)
}
