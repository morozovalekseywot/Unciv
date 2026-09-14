package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapgenerator.mapregions.Region
import com.unciv.logic.map.mapgenerator.mapregions.TileDataMap
import com.unciv.logic.map.mapgenerator.resourceplacement.RegionalStrategicBalancePlacement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType

/** Read-only explanation of the selected map. No RNG calls or recomputation of the chosen city layout. */
object MapGenerationReport {
    private val coordinateOrder = compareBy<HexCoord> { it.x }.thenBy { it.y }
    private val resourceOrder = compareBy<Tile> { it.resource }.thenBy { it.position.x }.thenBy { it.position.y }

    fun describe(
        map: TileMap,
        regions: List<Region>,
        tileData: TileDataMap,
        citySiteReports: List<MapGenerationDiagnostics.RegionResult>
    ): String {
        val output = StringBuilder()
        output.appendLine("Coordinates: axial (x, y). City sites are candidates, not future orders or ownership.")
        output.appendLine("Resource scope: in region or in selected cities' work ranges; work-range overlap alone does not guarantee access.")
        val allStarts = map.startingLocationsByNation.values.flatten().toSet()
        val workRange = map.ruleset!!.modOptions.constants.cityWorkRange
        for (region in regions) {
            val capital = region.startPosition
            val civID = map.startingLocationsByNation.entries.firstOrNull { entry ->
                entry.value.any { it.position == capital }
            }?.key
            val civ = map.gameInfo.civilizations.firstOrNull { it.civID == civID }
            val name = if (civ != null) civ.civName else "Unassigned region"
            output.appendLine("Civilization: $name (id=$civID, region=${region.type})")
            output.appendLine("  Capital: ${position(capital)}")
            val siteReport = citySiteReports.firstOrNull { it.capital == capital }
            val cityLabels = linkedMapOf<HexCoord, String>()
            if (capital != null) cityLabels[capital] = "Capital"
            if (siteReport == null) {
                output.appendLine("  City sites: not evaluated on this map")
            } else {
                val status = if (siteReport.passed) "passed" else "failed; only partial selection shown"
                output.appendLine("  City sites: $status")
                val expansionSites = siteReport.cityLuxuries.keys.filter { it != capital }.sortedWith(coordinateOrder)
                for ((index, center) in expansionSites.withIndex()) cityLabels[center] = "City ${index + 2}"
                if (expansionSites.isEmpty()) output.appendLine("    No expansion sites selected")
                for ((center, label) in cityLabels) {
                    val luxury = siteReport.cityLuxuries[center]
                    val assignedLuxury = when {
                        luxury == null -> "none assigned by validator"
                        map[luxury].tileResource?.resourceType != ResourceType.Luxury ->
                            "assigned deposit missing at ${position(luxury)}"
                        else -> resource(map[luxury])
                    }
                    output.appendLine("    $label ${position(center)} -> luxury: $assignedLuxury")
                }
            }

            val selectedCenters = cityLabels.keys.map { map[it] }
            val nearby = HashSet<Tile>()
            for (center in selectedCenters) center.forEachTileInDistance(workRange) { nearby.add(it) }
            val scopedResources = (region.tiles + nearby).filter { it.resource != null }.sortedWith(resourceOrder)
            fun scope(tile: Tile): String {
                val territory = if (tile in region.tiles) "in region" else "outside region"
                val cities = cityLabels.entries.filter { map[it.key].aerialDistanceTo(tile) <= workRange }.map { it.value }
                val inRange = if (cities.isEmpty()) "none" else cities.joinToString(", ")
                return "$territory; in work range: $inRange"
            }
            fun appendResources(heading: String, tiles: List<Tile>) {
                output.appendLine("  $heading:")
                if (tiles.isEmpty()) output.appendLine("    none")
                for (tile in tiles) output.appendLine("    ${resource(tile)} [${scope(tile)}]")
            }

            appendResources("Strategic resources", scopedResources.filter { it.tileResource!!.resourceType == ResourceType.Strategic })
            appendAdditionalResources(output, map, region, tileData, allStarts, cityLabels)
            val regionalLuxury = if (region.luxury == null) "none assigned" else region.luxury
            val luxuryTiles = scopedResources.filter { it.tileResource!!.resourceType == ResourceType.Luxury }
            appendResources("Regional luxury: $regionalLuxury", luxuryTiles.filter { it.resource == region.luxury })
            appendResources("Additional luxuries (other types)", luxuryTiles.filter { it.resource != region.luxury })
        }
        return output.toString().trimEnd()
    }

    private fun appendAdditionalResources(
        output: StringBuilder,
        map: TileMap,
        region: Region,
        tileData: TileDataMap,
        allStarts: Set<Tile>,
        cityLabels: Map<HexCoord, String>
    ) {
        val constants = map.ruleset!!.modOptions.constants
        val configuredNames = constants.additionalRegionalStrategicBalanceResources.distinct()
        output.appendLine("  additionalRegionalStrategicBalanceResources: ${configuredNames.joinToString(", ")}")
        if (!map.mapParameters.getStrategicBalance()) {
            output.appendLine("    disabled (Strategic Balance is off)")
            return
        }
        if (configuredNames.isEmpty()) {
            output.appendLine("    disabled (empty list)")
            return
        }
        val eligibleSites = ArrayList<Tile>()
        val accessible = RegionalStrategicBalancePlacement.getAccessibleTiles(
            region, tileData, allStarts.filter { it.position != region.startPosition }, eligibleSites
        )
        for (name in configuredNames) {
            val definition = map.ruleset!!.tileResources[name]
            if (definition == null || definition.resourceType != ResourceType.Strategic) {
                output.appendLine("    $name: ignored (unknown or non-strategic resource)")
                continue
            }
            val deposits = accessible.filter { it.resource == name && it.resourceAmount > 0 }.sortedWith(resourceOrder)
            if (deposits.isEmpty()) output.appendLine("    $name: NOT FOUND in accessible settlement area")
            for (deposit in deposits) {
                val origin = if (region.additionalStrategicPlacements[name] == deposit.position) "added by regional balance" else "already present"
                val possibleSites = eligibleSites.filter { it.aerialDistanceTo(deposit) <= constants.cityWorkRange }
                val selected = possibleSites.filter { it.position in cityLabels }.map { cityLabels[it.position]!! }.sorted()
                val access = if (selected.isNotEmpty()) "via ${selected.joinToString(", ")}" else {
                    val alternative = possibleSites.map { it.position }.sortedWith(coordinateOrder).first()
                    "via alternative viable site ${position(alternative)} (not in selected city set)"
                }
                output.appendLine("    ${resource(deposit)} [$origin; $access]")
            }
        }
    }

    private fun position(coordinate: HexCoord?): String =
        if (coordinate == null) "not assigned" else "(${coordinate.x}, ${coordinate.y})"

    private fun resource(tile: Tile): String =
        if (tile.tileResource?.resourceType == ResourceType.Strategic)
            "${tile.resource} x${tile.resourceAmount} at ${position(tile.position)}"
        else "${tile.resource} at ${position(tile.position)}"
}
