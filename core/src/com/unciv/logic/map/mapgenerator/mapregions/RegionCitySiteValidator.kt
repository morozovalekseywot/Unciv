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
 *  the city center. Deposits of the same resource type are allowed, but one tile cannot supply two chosen sites.
 *  Luxury tiles may be outside the region or on water, just like a city's actual resource catchment.
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

        // Candidate city centers must lie within the civ's own region (a civ shouldn't need to found
        // in someone else's territory), but their *workable neighborhood* is evaluated map-wide.
        val regionCandidateTiles = HashSet<Tile>()
        for (tile in region.tiles)
            if (isUsableTile(tile, tileData)) regionCandidateTiles.add(tile)

        if (regionCandidateTiles.size < requiredSites) return false

        val candidates = ArrayList<CitySiteCandidate>()
        for (tile in regionCandidateTiles) {
            if (foreignStartTiles.any { tile.aerialDistanceTo(it) < minAerialDistance }) continue

            var workableCount = 0
            val luxuryTiles = ArrayList<Tile>()
            tile.forEachTileInDistance(workRange) { workTile ->
                if (workTile != tile && isUsableTile(workTile, tileData)) workableCount++
                if (workTile.tileResource?.resourceType == ResourceType.Luxury)
                    luxuryTiles.add(workTile)
            }
            if (workableCount >= minWorkableTiles && luxuryTiles.isNotEmpty())
                candidates.add(CitySiteCandidate(tile, workableCount, luxuryTiles))
        }

        if (candidates.size < requiredSites) return false

        // Greedy selection: richest neighborhoods first, keeping every pair at least minAerialDistance apart.
        // Anchor on the actual capital, which must pass the same quality and luxury checks as other sites.
        candidates.sortByDescending { it.workableCount }
        val startPosition = region.startPosition
        val chosen = ArrayList<Tile>()
        val luxuryAssignments = HashMap<Tile, CitySiteCandidate>()
        if (startPosition != null) {
            val start = candidates.firstOrNull { it.tile.position == startPosition }
            if (start == null) return false
            assignLuxury(start, luxuryAssignments, HashSet())
            chosen.add(start.tile)
        }

        for (candidate in candidates) {
            if (chosen.size >= requiredSites) break
            if (candidate.tile in chosen) continue
            if (chosen.any { it.aerialDistanceTo(candidate.tile) < minAerialDistance }) continue
            if (!assignLuxury(candidate, luxuryAssignments, HashSet())) continue
            chosen.add(candidate.tile)
        }

        if (selectedCityLuxuries != null)
            for ((luxuryTile, candidate) in luxuryAssignments)
                selectedCityLuxuries[candidate.tile] = luxuryTile
        return chosen.size >= requiredSites
    }

    /** Find a distinct deposit for this site, moving earlier assignments if their sites have alternatives.
     *  Only successful paths change assignments, so a rejected candidate leaves the chosen sites supplied.
     *  The search is bounded by the small number of chosen city sites, not all candidate city locations. */
    private fun assignLuxury(
        candidate: CitySiteCandidate,
        assignments: MutableMap<Tile, CitySiteCandidate>,
        visited: MutableSet<Tile>
    ): Boolean {
        for (luxuryTile in candidate.luxuryTiles) {
            if (!visited.add(luxuryTile)) continue
            val previous = assignments[luxuryTile]
            if (previous != null && !assignLuxury(previous, assignments, visited)) continue
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

    private class CitySiteCandidate(val tile: Tile, val workableCount: Int, val luxuryTiles: List<Tile>)
}
