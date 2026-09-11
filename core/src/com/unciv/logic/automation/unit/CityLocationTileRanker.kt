package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Automation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Mutated
import yairm210.purity.annotations.Readonly
import kotlin.math.roundToInt

object CityLocationTileRanker {

    private const val DISTANCE_PENALTY_PER_TILE = 3f
    private const val EXPANSION_WAR_SEARCH_RANGE = 8
    private const val COMPARABLE_PEACEFUL_SITE_RATIO = 0.85f

    class BestTilesToFoundCity {
        var tileRankMap: HashMap<Tile, Float> = HashMap()
        var bestTile: Tile? = null
        var bestTileRank: Float = 0f
    }

    /**
     * Returns a hashmap of tiles to their rankings, plus the highest-value tile and its value
     */
    fun getBestTilesToFoundCity(unit: MapUnit, distanceToSearch: Int? = null, minimumValue: Float): BestTilesToFoundCity {
        val range = if (distanceToSearch != null) distanceToSearch else {
            val distanceFromHome = if (unit.civ.cities.isEmpty()) 0
            else unit.civ.cities.minOf { it.getCenterTile().aerialDistanceTo(unit.getTile()) }
            (8 - distanceFromHome).coerceIn(1, 5) // Restrict vision when far from home to avoid death marches
        }
        val nearbyCities = unit.civ.gameInfo.getCities()
            .filter { it.getCenterTile().aerialDistanceTo(unit.getTile()) <= 7 + range }
            .toList()

        val bestTilesToFoundCity = BestTilesToFoundCity()
        @LocalState val baseTileMap = HashMap<Tile, Float>()
        @LocalState val possibleTileLocationsWithRank = ArrayList<Pair<Tile, Float>>()
        unit.getTile().forEachTileInDistance(range, {
            canFoundCityOn(unit, it)
                && canSettleTile(it, unit.civ, nearbyCities)
                && (unit.getTile() == it || unit.movement.canMoveTo(it))
        }) {
            var tileValue = rankTileToSettle(it, unit.civ, nearbyCities, baseTileMap)
            val distanceScore = (unit.currentTile.aerialDistanceTo(it) * DISTANCE_PENALTY_PER_TILE).coerceIn(0f, 99f)
            tileValue *= (100 - distanceScore) / 100
            if (tileValue < minimumValue) return@forEachTileInDistance

            bestTilesToFoundCity.tileRankMap[it] = tileValue
            possibleTileLocationsWithRank.add(Pair(it, tileValue))
        }
        possibleTileLocationsWithRank.sortByDescending { it.second }

        val bestReachableTile = possibleTileLocationsWithRank.firstOrNull { unit.movement.canReach(it.first) }
        if (bestReachableTile != null){
            bestTilesToFoundCity.bestTile = bestReachableTile.first
            bestTilesToFoundCity.bestTileRank = bestReachableTile.second
        }

        return bestTilesToFoundCity
    }

    @Readonly
    private fun canFoundCityOn(unit: MapUnit, tile: Tile): Boolean {
        val gameContext = GameContext(unit = unit, tile = tile)
        var canFoundCity = false
        unit.forEachMatchingUnique(UniqueType.FoundCity, gameContext) { canFoundCity = true }
        if (canFoundCity) return true
        unit.forEachMatchingUnique(UniqueType.FoundPuppetCity, gameContext) { canFoundCity = true }
        return canFoundCity
    }

    @Readonly
    private fun canSettleTile(
        tile: Tile,
        civ: Civilization,
        nearbyCities: Iterable<City>,
        assumeWarWith: Civilization? = null
    ): Boolean {
        val modConstants = civ.gameInfo.ruleset.modOptions.constants
        if (!tile.isLand || tile.isImpassible()) return false
        if (tile.getOwner() != null && tile.getOwner() != civ) return false
        if (tile.getForeignCapitalsBlockingSettlement(civ).any { it.civ != assumeWarWith }) return false
        for (city in nearbyCities) {
            val distance = city.getCenterTile().aerialDistanceTo(tile)
            // todo: AgreedToNotSettleNearUs is hardcoded for now but it may be better to softcode it below in getDistanceToCityModifier
            if (distance <= 6 && civ.knows(city.civ)
                && !civ.isAtWarWith(city.civ)
                && city.civ != assumeWarWith
                // If the CITY OWNER knows that the UNIT OWNER agreed not to settle near them
                && city.civ.getDiplomacyManager(civ)!!
                    .hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
                return false
            if (tile.getContinent() == city.getCenterTile().getContinent()) {
                if (distance <= modConstants.minimalCityDistance) return false
            } else {
                if (distance <= modConstants.minimalCityDistanceOnDifferentContinents) return false
            }
        }
        return true
    }

    /**
     * Extra motivation to fight [targetCiv] when its capital protection is the only thing keeping
     * an existing settler away from a substantially better city site.
     *
     * This deliberately tops out below the value needed for an expansion-only surprise war. The
     * normal war evaluation must still find acceptable military strength, attack paths, happiness,
     * and population before this can lead to preparation or a declaration.
    */
    @Readonly
    @VisibleForTesting
    fun getExpansionWarMotivation(civ: Civilization, targetCiv: Civilization): Float {
        val settlers = civ.units.getCivUnits()
            .filter { it.hasUnique(UniqueType.FoundCity, GameContext.IgnoreConditionals) }
            .toList()
        return getExpansionWarMotivation(civ, targetCiv, settlers)
    }

    @Readonly
    private fun getExpansionWarMotivation(
        civ: Civilization,
        targetCiv: Civilization,
        settlers: List<MapUnit>
    ): Float {
        val modConstants = civ.gameInfo.ruleset.modOptions.constants
        val protectionRadius = civ.gameInfo.tileMap.getForeignCapitalSettlementProtectionRadius()
        if (protectionRadius <= 0) return 0f
        if (!targetCiv.isMajorCiv() || civ.isAtWarWith(targetCiv)) return 0f
        if (settlers.isEmpty()) return 0f

        val diplomacyManager = civ.getDiplomacyManager(targetCiv) ?: return 0f
        if (diplomacyManager.isRelationshipLevelGE(RelationshipLevel.Friend)) return 0f
        if (diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) return 0f
        if (diplomacyManager.hasFlag(DiplomacyFlags.DefensivePact)) return 0f

        val targetCapital = targetCiv.cities.firstOrNull {
            it.isOriginalCapital && it.foundingCivObject == targetCiv
        } ?: return 0f
        if (!civ.hasExplored(targetCapital.getCenterTile())) return 0f
        val maximumRelevantDistance = EXPANSION_WAR_SEARCH_RANGE + protectionRadius
        if (settlers.none {
                it.getTile().aerialDistanceTo(targetCapital.getCenterTile()) <= maximumRelevantDistance
            }) return 0f

        val nearbyCities = civ.gameInfo.getCities().toList()
        @LocalState val baseTileMap = HashMap<Tile, Float>()
        val minimumValue = modConstants.minimumCityLocationTileValue
        var bestMotivation = 0f

        for (settler in settlers) {
            if (settler.getTile().aerialDistanceTo(targetCapital.getCenterTile()) > maximumRelevantDistance)
                continue

            var bestPeacefulSite = Float.NEGATIVE_INFINITY
            var bestBlockedSite = Float.NEGATIVE_INFINITY
            settler.getTile().forEachTileInDistance(EXPANSION_WAR_SEARCH_RANGE, {
                canFoundCityOn(settler, it)
            }) { tile ->
                val canSettlePeacefully = canSettleTile(tile, civ, nearbyCities)
                val blockingCapitals = tile.getForeignCapitalsBlockingSettlement(civ).toList()
                val blockedOnlyByTarget = blockingCapitals.isNotEmpty()
                    && blockingCapitals.all { it.civ == targetCiv }
                    && canSettleTile(tile, civ, nearbyCities, assumeWarWith = targetCiv)
                if (!canSettlePeacefully && !blockedOnlyByTarget) return@forEachTileInDistance

                var tileValue = rankTileToSettle(tile, civ, nearbyCities, baseTileMap)
                val distanceScore = (settler.currentTile.aerialDistanceTo(tile) * DISTANCE_PENALTY_PER_TILE).coerceIn(0f, 99f)
                tileValue *= (100 - distanceScore) / 100
                if (canSettlePeacefully) bestPeacefulSite = maxOf(bestPeacefulSite, tileValue)
                if (blockedOnlyByTarget) bestBlockedSite = maxOf(bestBlockedSite, tileValue)
            }

            if (bestBlockedSite < minimumValue) continue
            val peacefulComparison = maxOf(bestPeacefulSite, minimumValue, 1f)
            if (peacefulComparison >= bestBlockedSite * COMPARABLE_PEACEFUL_SITE_RATIO) continue

            val relativeAdvantage = (bestBlockedSite - peacefulComparison) / peacefulComparison
            val scarcityBonus = if (bestPeacefulSite < minimumValue) 8f
                else (relativeAdvantage * 20f).coerceAtMost(8f)
            val qualityBonus = ((bestBlockedSite - minimumValue) / 10f).coerceIn(0f, 5f)
            val expansionFocus = civ.getPersonality().scaledFocus(PersonalityValue.Expansion)
            val motivation = ((8f + scarcityBonus + qualityBonus) * expansionFocus).coerceAtMost(25f)
            bestMotivation = maxOf(bestMotivation, motivation)
        }
        return bestMotivation
    }

    @Readonly
    @VisibleForTesting
    fun shouldWaitForExpansionWar(unit: MapUnit): Boolean {
        for (targetCiv in unit.civ.getKnownCivs()) {
            val diplomacyManager = unit.civ.getDiplomacyManager(targetCiv) ?: continue
            if (!diplomacyManager.hasFlag(DiplomacyFlags.WaryOf)) continue
            if (diplomacyManager.getFlag(DiplomacyFlags.WaryOf) >= 0) continue
            if (getExpansionWarMotivation(unit.civ, targetCiv, listOf(unit)) > 0f) return true
        }
        return false
    }

    @Readonly
    private fun rankTileToSettle(newCityTile: Tile, civ: Civilization, nearbyCities: Iterable<City>,
                                 @Mutated baseTileMap: HashMap<Tile, Float>): Float {
        var tileValue = 0f
        tileValue += getDistanceToCityModifier(newCityTile, nearbyCities, civ)

        val onCoast = newCityTile.isAdjacentToCoast()
        val onHill = newCityTile.isHill()
        val isNextToMountain = newCityTile.isAdjacentTo("Mountain")
        // Only count a luxury resource that we don't have yet as unique once
        @LocalState val newUniqueLuxuryResources = HashSet<TileResource>()

        if (onCoast) tileValue += 3
        // Hills are free production and defence
        if (onHill) tileValue += 14
        // Observatories are good, but current implementation not mod-friendly
        if (isNextToMountain) tileValue += 5
        // This bonus for settling on river is a bit outsized for the importance, but otherwise they have a habit of settling 1 tile away
        if (newCityTile.isAdjacentToRiver()) tileValue += 20
        // We want to found the city on an oasis because it can't be improved otherwise
        if (newCityTile.terrainHasUnique(UniqueType.Unbuildable)) tileValue += 3
        val resource = newCityTile.tileResource
        if (civ.canSeeResource(resource)) {
            tileValue -= 4
            // Settling on bonus resources tends to waste a food
            if (resource.resourceType == ResourceType.Bonus) tileValue -= 8
            // Build on jungle luxuries for tempo
            if (resource.resourceType == ResourceType.Luxury &&
                newCityTile.lastTerrain.hasUnique(UniqueType.Vegetation) &&
                !newCityTile.lastTerrain.hasUnique(UniqueType.ProductionBonusWhenRemoved)
                ) tileValue += 10
        }

        var tiles = 0
        for (i in 0..2) {
            //Ideally, we shouldn't really count the center tile, as it's converted into 1 production 2 food anyways with special cases treated above, but doing so can lead to AI moving settler back and forth until forever
            newCityTile.forEachTileAtDistance(i) { nearbyTile ->
                tiles++
                tileValue += rankTile(nearbyTile, civ, onCoast, newUniqueLuxuryResources, baseTileMap) * (3f / (i + 1))
                //Tiles close to the city can be worked more quickly, and thus should gain higher weight.
            }
        }

        // Placing cities on the edge of the map is bad, we can't even build improvements on them!
        tileValue -= (HexMath.getNumberOfTilesInHexagon(2) - tiles) * 2.4f
        return tileValue
    }

    @Readonly
    private fun getDistanceToCityModifier(newCityTile: Tile, nearbyCities: Iterable<City>, civ: Civilization): Float {
        var modifier = 0f
        for (city in nearbyCities) {
            val distanceToCity = newCityTile.aerialDistanceTo(city.getCenterTile())
            var distanceToCityModifier = when {
                // NOTE: the line it.getCenterTile().aerialDistanceTo(unit.getTile()) <= X + range
                // above MUST have the constant X that is added to the range be higher or equal to the highest distance here + 1
                // If it is not higher the settler may get stuck when it ranks the same tile differently
                // as it moves away from the city and doesn't include it in the calculation
                // and values it higher than when it moves closer to the city
                distanceToCity == 7 -> 2f
                distanceToCity == 6 -> 4f
                distanceToCity == 5 -> 8f // Settling further away sacrifices tempo
                distanceToCity == 4 -> 6f
                distanceToCity == 3 -> -25f
                distanceToCity < 3 -> -30f // Even if it is a mod that lets us settle closer, lets still not do it
                else -> 0f
            }
            // We want a defensive ring around our capital, but a newly planted forward city should
            // not immediately encourage another city even deeper in the same direction.
            if (city.civ == civ && isEstablishedExpansionAnchor(city)) {
                distanceToCityModifier *= if (city.isCapital()) 2 else 1
                modifier += distanceToCityModifier
                continue
            }

            if (city.civ == civ || !city.civ.isMajorCiv()) continue
            if (!city.isOriginalCapital || city.foundingCivObject != city.civ) continue
            if (civ.isAtWarWith(city.civ)) continue

            val foreignCapitalPenalty = when (distanceToCity) {
                6 -> -12f
                else -> 0f
            }
            modifier += foreignCapitalPenalty * civ.getPersonality().inverseScaledFocus(PersonalityValue.Expansion)
        }
        return modifier
    }

    @Readonly
    @VisibleForTesting
    fun isEstablishedExpansionAnchor(city: City): Boolean {
        if (city.isCapital()) return true
        val standardSpeedTurns = city.civ.gameInfo.ruleset.modOptions.constants.cityExpansionAnchorMaturityTurns
        val maturityTurns = (standardSpeedTurns * city.civ.gameInfo.speed.modifier).roundToInt()
        return city.civ.gameInfo.turns - city.turnAcquired >= maturityTurns
    }

    @Readonly
    private fun rankTile(
        rankTile: Tile,
        civ: Civilization,
        onCoast: Boolean,
        @Mutated newUniqueLuxuryResources: HashSet<TileResource>,
        @Mutated baseTileMap: HashMap<Tile, Float>
    ): Float {
        if (rankTile.getCity() != null) return -1f
        var locationSpecificTileValue = 0f
        // Don't settle near but not on the coast
        if (rankTile.isWater && !onCoast) locationSpecificTileValue -= 1
        // Check if there are any new unique luxury resources
        val resource = rankTile.tileResource
        if (civ.canSeeResource(resource) &&
            resource.resourceType == ResourceType.Luxury &&
            !civ.hasResource(resource) &&
            !newUniqueLuxuryResources.contains(resource)
        ) {
            locationSpecificTileValue += 10
            newUniqueLuxuryResources.add(resource)
        }

        // Check if everything else has been calculated, if so return it
        if (baseTileMap.containsKey(rankTile)) return locationSpecificTileValue + baseTileMap[rankTile]!!
        if (rankTile.getOwner() != null && rankTile.getOwner() != civ) return 0f

        var rankTileValue = Automation.rankStatsValue(rankTile.stats.getTileStats(null, civ), civ)

        if (civ.canSeeResource(resource)) {
            rankTileValue += when (resource.resourceType) {
                ResourceType.Bonus -> 1f
                ResourceType.Strategic -> 2f
                ResourceType.Luxury -> 10f //very important for humans who might want to conquer the AI
            }
        }
        if (rankTile.terrainHasUnique(UniqueType.FreshWater)) rankTileValue += 0.5f 
        //Taking into account freshwater farm food, maybe less important in baseruleset mods
        if (rankTile.terrainFeatures.isNotEmpty() && rankTile.lastTerrain.hasUnique(UniqueType.ProductionBonusWhenRemoved)) rankTileValue += 0.7f
        //Taking into account yields from forest chopping

        if (rankTile.isNaturalWonder()) rankTileValue += 4

        baseTileMap[rankTile] = rankTileValue

        return rankTileValue + locationSpecificTileValue
    }

}
