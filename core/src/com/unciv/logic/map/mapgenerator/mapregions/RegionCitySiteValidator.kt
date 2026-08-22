package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.logic.map.tile.Tile

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
 *  Two chosen city sites must be at least [minAerialDistance] aerial tiles apart, mirroring the in-game minimal
 *  city distance so the sites represent cities that could actually coexist. Candidates too close to any
 *  [foreignStartTiles] (other civs'/city-states' starting positions) are rejected outright.
 *
 *  IMPORTANT: This must run **after** resources have been placed (so [Tile.resource] is populated) and after
 *  minor civs have been placed (so [foreignStartTiles] is complete).
 */
object RegionCitySiteValidator {

    /** @return true if [region] can host at least [requiredSites] city sites satisfying the spacing and quality rules. */
    fun regionHasEnoughCitySites(
        region: Region,
        tileData: TileDataMap,
        requiredSites: Int,
        minWorkableTiles: Int,
        minAerialDistance: Int,
        workRange: Int,
        foreignStartTiles: Collection<Tile>
    ): Boolean {
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
            for (workTile in tile.getTilesInDistance(workRange))
                if (workTile != tile && isUsableTile(workTile, tileData)) workableCount++
            if (workableCount >= minWorkableTiles)
                candidates.add(CitySiteCandidate(tile, workableCount))
        }

        if (candidates.size < requiredSites) return false

        // Greedy selection: richest neighborhoods first, keeping every pair at least minAerialDistance apart.
        // Prefer to anchor on the region's actual capital start if we have one, so we count sites reachable
        // from where the civ really spawns.
        candidates.sortByDescending { it.workableCount }
        val startTile = region.startPosition?.let { region.tileMap[it] }

        val chosen = ArrayList<Tile>()
        if (startTile != null && startTile in regionCandidateTiles
            && foreignStartTiles.none { startTile.aerialDistanceTo(it) < minAerialDistance })
            chosen.add(startTile)

        for (candidate in candidates) {
            if (chosen.size >= requiredSites) break
            if (candidate.tile in chosen) continue
            if (chosen.any { it.aerialDistanceTo(candidate.tile) < minAerialDistance }) continue
            chosen.add(candidate.tile)
        }

        return chosen.size >= requiredSites
    }

    private fun isUsableTile(tile: Tile, tileData: TileDataMap): Boolean {
        if (!tile.isLand || tile.isImpassible()) return false
        // A resource redeems even a bare Desert/Ice tile
        if (tile.resource != null) return true
        val data = tileData[tile] ?: return false
        // isJunk is false for e.g. Desert+Hill/Forest/Oasis (feature-based), true only for strictly empty desert/ice/snow
        return !data.isJunk
    }

    private class CitySiteCandidate(val tile: Tile, val workableCount: Int)
}
