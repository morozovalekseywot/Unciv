package com.unciv.logic.automation.civilization

import com.unciv.logic.MultiFilter
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CombatAction
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.BFS
import com.unciv.logic.map.MapPathing
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.ui.screens.victoryscreen.RankingType
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly
import kotlin.math.pow

object MotivationToAttackAutomation {

    private const val MAX_SHORT_ATTACK_PATH_LENGTH = 16

    private data class AccessibleAttackTarget(
        val cityToAttackFrom: City,
        val cityToAttack: City,
        val path: List<Tile>,
        val isLandPath: Boolean,
    )

    /** Will return the motivation to attack, but might short circuit if the value is guaranteed to
     * be lower than `atLeast`. So any values below `atLeast` should not be used for comparison. */
    @Readonly
    fun hasAtLeastMotivationToAttack(
        civInfo: Civilization,
        targetCiv: Civilization,
        atLeast: Float,
        expansionMotivation: Float = 0f
    ): Float = evaluateMotivation(civInfo, targetCiv, atLeast, expansionMotivation, null)

    /** Full calculation for the debug UI. Declaration eligibility is separate from motivation. */
    fun getWarMotivationReport(civInfo: Civilization, targetCiv: Civilization): WarMotivationReport {
        val report = WarMotivationReport()
        val attackerBlocker = DiplomacyAutomation.getWarDeclarationBlocker(civInfo)
        val targetBlocker = DiplomacyAutomation.getWarTargetBlocker(civInfo, targetCiv)
        if (attackerBlocker != null) report.declarationBlockers.add(attackerBlocker)
        if (targetBlocker != null) report.declarationBlockers.add(targetBlocker)
        if (report.declarationBlockers.isNotEmpty())
            report.standaloneWarReadiness = WarMotivationReport.StandaloneWarReadiness.Blocked
        if (civInfo == targetCiv || civInfo.getDiplomacyManager(targetCiv) == null
            || civInfo.cities.isEmpty() || targetCiv.cities.isEmpty()
            || civInfo.isDefeated() || targetCiv.isDefeated()) return report

        val expansionMotivation = CityLocationTileRanker.getExpansionWarMotivation(civInfo, targetCiv)
        val motivation = evaluateMotivation(civInfo, targetCiv, Float.NEGATIVE_INFINITY, expansionMotivation, report)
        report.motivation = motivation
        report.standaloneWarReadiness = when {
            report.rejectionReason != null || report.declarationBlockers.isNotEmpty() -> WarMotivationReport.StandaloneWarReadiness.Blocked
            report.warPlanningSkippedBeforePaths -> WarMotivationReport.StandaloneWarReadiness.SkippedBeforePaths
            else -> DeclareWarPlanEvaluator.getStandaloneWarReadiness(civInfo, targetCiv, motivation)
        }
        return report
    }

    // The optional report belongs exclusively to this evaluation; normal AI calls pass null.
    @Readonly
    private fun evaluateMotivation(
        civInfo: Civilization,
        targetCiv: Civilization,
        atLeast: Float,
        expansionMotivation: Float,
        @LocalState report: WarMotivationReport?,
    ): Float {
        val diplomacyManager = civInfo.getDiplomacyManager(targetCiv)!!
        val personality = civInfo.getPersonality()

        var targetCitiesWithOurCity = civInfo.threatManager.getNeighboringCitiesOfOtherCivs()
            .filter { it.second.civ == targetCiv }
            .toList()
        var isExpansionTargetFallback = false
        if (targetCitiesWithOurCity.isEmpty() && expansionMotivation > 0f) {
            val targetCapital = targetCiv.cities.firstOrNull {
                it.isOriginalCapital && it.foundingCivObject == targetCiv
            } ?: return rejectOrZero(report, "No original capital for the expansion target")
            val closestOwnCity = civInfo.cities.minByOrNull {
                it.getCenterTile().aerialDistanceTo(targetCapital.getCenterTile())
            } ?: return rejectOrZero(report, "The attacker has no cities")
            targetCitiesWithOurCity = listOf(Pair(closestOwnCity, targetCapital))
            isExpansionTargetFallback = true
        }

        val accessibleAttackTargets = findAccessibleAttackTargets(civInfo, targetCiv, targetCitiesWithOurCity)
        if (report != null) {
            report.expansionFallback = isExpansionTargetFallback
            val accessibleCities = accessibleAttackTargets.map { it.cityToAttack }.toSet()
            if (!isExpansionTargetFallback)
                report.addExcludedCities(targetCitiesWithOurCity.map { it.second }.distinct()
                    .filter { it !in accessibleCities }.map { it.name })
        }
        if (!isExpansionTargetFallback) {
            targetCitiesWithOurCity = accessibleAttackTargets.map {
                Pair(it.cityToAttackFrom, it.cityToAttack)
            }
        }
        val targetCities = targetCitiesWithOurCity.map { it.second }.distinct()

        if (targetCitiesWithOurCity.isEmpty()) return rejectOrZero(report, "No accessible target cities")

        if (report != null) {
            report.addTargetCities(targetCities.map { Pair(it.name, getDefendingCityStrength(it)) })
        }

        if (targetCities.all { hasNoUnitsThatCanAttackCityWithoutDying(civInfo, it) })
            return rejectOrZero(report, "No military unit can survive an attack on any target city")

        val baseForce = 100f

        val ourCombatStrength = calculateOffensiveCombatStrength(civInfo, baseForce)
        val defensivePactForce = getDefensivePactSupport(civInfo, targetCiv)
        val theirOwnCombatStrength = calculateCombatStrengthWithProtectors(targetCiv, baseForce, civInfo, targetCities)
        val theirCombatStrength = theirOwnCombatStrength + defensivePactForce
        val otherEnemiesStrength = 0.8f * civInfo.threatManager.getCombinedForceOfWarringCivs()
        if (report != null) {
            val defenderForce = targetCiv.getStatForRanking(RankingType.Force).toFloat()
            val defensiveMight = getDefensiveMilitaryMight(civInfo, targetCiv)
            val weakestCity = targetCities.minBy { getDefendingCityStrength(it) }
            report.weakestCity = weakestCity.name
            val cityStrength = getDefendingCityStrength(weakestCity)
            val baseCityStrength = CityCombatant(weakestCity).getCityStrength().toFloat()
            report.addInputs(listOf(
                "Base force per side" to baseForce,
                "Attacker military force" to (ourCombatStrength - baseForce),
                "Defender military force" to defenderForce,
                "Friendly territory defense adjustment" to (defensiveMight - defenderForce),
                "Friendly territory bonus weight" to targetCiv.gameInfo.ruleset.modOptions.constants.aiFriendlyTerritoryStrengthBonusWeight,
                "Weakest city base strength" to baseCityStrength,
                "City defensive strength adjustment" to (cityStrength - baseCityStrength),
                "Weakest target city strength" to cityStrength,
                "City-state protector force" to (theirOwnCombatStrength - baseForce - defensiveMight - cityStrength),
                "Defensive pact ally force" to defensivePactForce,
                "Other enemies force adjustment" to otherEnemiesStrength,
                "Attacker total combat strength" to ourCombatStrength,
                "Defender total combat strength" to (theirCombatStrength + otherEnemiesStrength),
                "Attacker score" to civInfo.getStatForRanking(RankingType.Score).toFloat(),
                "Defender score" to targetCiv.getStatForRanking(RankingType.Score).toFloat(),
            ))
        }

        val modifiers: MutableList<Pair<String, Float>> = mutableListOf()

        // If our personality favors declaring war more, then we should have a higher base motivation (a negative number closer to 0)
        modifiers.add(Pair("Base motivation", -(15f * personality.inverseScaledFocus(PersonalityValue.DeclareWar))))

        modifiers.add(Pair("Expansion opportunity", expansionMotivation))

        modifiers.add(Pair("Relative combat strength", getCombatStrengthModifier(civInfo, targetCiv, ourCombatStrength, theirCombatStrength + otherEnemiesStrength, report)))
        // TODO: For now this will be a very high value because the AI can't handle multiple fronts, this should be changed later though
        modifiers.add(Pair("Concurrent wars", -civInfo.getCivsAtWarWith().count { it.isMajorCiv() && it != targetCiv } * 20f))
        modifiers.add(Pair("Their concurrent wars", targetCiv.getCivsAtWarWith().count { it.isMajorCiv() } * 3f))

        if (civInfo.threatManager.getNeighboringCivilizations().none { it != targetCiv && it.isMajorCiv()
                        && civInfo.getDiplomacyManager(it)!!.isRelationshipLevelLT(RelationshipLevel.Friend) })
            modifiers.add(Pair("No other threats", 10f))

        if (targetCiv.isMajorCiv()) {
            val scoreRatioModifier = getScoreRatioModifier(targetCiv, civInfo)
            modifiers.add(Pair("Relative score", scoreRatioModifier))

            if (civInfo.stats.getUnitSupplyDeficit() != 0) {
                modifiers.add(Pair("Over unit supply", (civInfo.stats.getUnitSupplyDeficit() * 2f).coerceAtMost(20f)))
            } else if (targetCiv.stats.getUnitSupplyDeficit() == 0 && !targetCiv.isCityState) {
                modifiers.add(Pair("Relative production", getProductionRatioModifier(civInfo, targetCiv, report)))
            }
        }

        val minTargetCityDistance = targetCitiesWithOurCity.minOf { it.second.getCenterTile().aerialDistanceTo(it.first.getCenterTile()) }
        if (report != null) report.addInput("Distance to nearest target city" to minTargetCityDistance.toFloat())
        // Defensive civs should avoid fighting civilizations that are farther away and don't pose a threat
        modifiers.add(Pair("Far away cities", when {
            minTargetCityDistance > 20 -> -10f
            minTargetCityDistance > 14 -> -8f
            minTargetCityDistance > 10 -> -3f
            else -> 0f
        } * personality.inverseScaledFocus(PersonalityValue.Aggressive)))

        // Defensive civs want to deal with potential nearby cities to protect themselves
        if (minTargetCityDistance < 6)
            modifiers.add(Pair("Close cities", 5f * personality.inverseScaledFocus(PersonalityValue.Aggressive)))

        if (diplomacyManager.hasFlag(DiplomacyFlags.ResearchAgreement))
            modifiers.add(Pair("Research Agreement", -5f * personality.scaledFocus(PersonalityValue.Science) * personality.scaledFocus(PersonalityValue.Commerce)))

        if (diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            modifiers.add(Pair("Declaration of Friendship", -10f * personality.scaledFocus(PersonalityValue.Loyal)))

        if (diplomacyManager.hasFlag(DiplomacyFlags.DefensivePact))
            modifiers.add(Pair("Defensive Pact", -15f * personality.scaledFocus(PersonalityValue.Loyal)))

        modifiers.add(Pair("Relationship", getRelationshipModifier(diplomacyManager)))

        if (diplomacyManager.hasFlag(DiplomacyFlags.Denunciation)) {
            modifiers.add(Pair("Denunciation", 5f * personality.inverseScaledFocus(PersonalityValue.Diplomacy)))
        }

        if (diplomacyManager.hasFlag(DiplomacyFlags.WaryOf) && diplomacyManager.getFlag(DiplomacyFlags.WaryOf) < 0) {
            // Completely defensive civs will plan defensively and have a 0 here
            modifiers.add(Pair("PlanningAttack", -diplomacyManager.getFlag(DiplomacyFlags.WaryOf) * personality.scaledFocus(PersonalityValue.Aggressive) / 2))
        } else {
            val attacksPlanned = civInfo.diplomacy.values.count { it.hasFlag(DiplomacyFlags.WaryOf) && it.getFlag(DiplomacyFlags.WaryOf) < 0 }
            modifiers.add(Pair("PlanningAttackAgainstOtherCivs", -attacksPlanned * 5f * personality.inverseScaledFocus(PersonalityValue.Aggressive)))
        }

        if (diplomacyManager.resourcesFromTrade().any { it.amount > 0 })
            modifiers.add(Pair("Receiving trade resources", -8f * personality.scaledFocus(PersonalityValue.Commerce)))

        // If their cities have no nearby cities that are also targets for us and the group does not include their capital,
        // their cities are likely isolated and make good targets.
        if (targetCiv.getCapital(true) !in targetCities
                && targetCities.all { theirCity -> !theirCity.neighboringCities.any { it !in targetCities } }) {
            modifiers.add(Pair("Isolated city", 10f * personality.scaledFocus(PersonalityValue.Aggressive)))
        }

        if (targetCiv.isCityState) {
            modifiers.add(Pair("Protectors", -targetCiv.cityStateFunctions.getProtectorCivs().size * 3f * personality.scaledFocus(PersonalityValue.Diplomacy)))
            //The more potential friends of this CS, the more times the friend bonus is shared and the utilitarian option is to leave it alive
            modifiers.add(Pair("Influence", -targetCiv.getDiplomacyManager(civInfo)!!.getInfluence() / 10f * personality.scaledFocus(PersonalityValue.Diplomacy)))
            // The more we invested into the city state already, the less likely we're going to attack it, and vice versa
            if (targetCiv.allyCiv == civInfo)
                modifiers.add(Pair("Allied City-state", -20 * personality.scaledFocus(PersonalityValue.Diplomacy))) // There had better be a DAMN good reason
        }

        modifiers += getWonderBasedMotivations(targetCiv)

        modifiers.add(Pair("War with allies", getAlliedWarMotivation(civInfo, targetCiv)))

        // Retain zero contributions in the report to distinguish them from skipped comparisons.
        if (report != null) report.addComponents(modifiers)
        modifiers.removeAll { it.second == 0f }
        var motivationSoFar = modifiers.map { it.second }.sum()
        // DeclareWar's normal query uses threshold 0, even though the report evaluates paths in full.
        if (report != null) report.warPlanningSkippedBeforePaths = motivationSoFar < 0f

        // Short-circuit before the potentially expensive fallback path search
        if (motivationSoFar < atLeast) return motivationSoFar

        val pathsModifier = getAttackPathsModifier(civInfo, targetCiv, accessibleAttackTargets)
        if (report != null) report.addComponent("Attack paths" to pathsModifier)
        motivationSoFar += pathsModifier

        return motivationSoFar
    }

    @Readonly
    private fun rejectOrZero(@LocalState report: WarMotivationReport?, reason: String): Float {
        if (report != null) return report.reject(reason)
        return 0f
    }

    @Readonly
    private fun calculateCombatStrengthWithProtectors(
        otherCiv: Civilization,
        baseForce: Float,
        civInfo: Civilization,
        targetCities: Collection<City>,
    ): Float {
        var theirCombatStrength = calculateDefensiveCombatStrength(otherCiv, baseForce, civInfo, targetCities)

        //for city-states, also consider their protectors
        if (otherCiv.isCityState and otherCiv.cityStateFunctions.getProtectorCivs().isNotEmpty()) {
            theirCombatStrength += otherCiv.cityStateFunctions.getProtectorCivs().filterNot { it == civInfo }
                .sumOf { it.getStatForRanking(RankingType.Force) }
        }
        return theirCombatStrength
    }

    @Readonly
    @VisibleForTesting
    fun calculateDefensiveCombatStrength(
        civInfo: Civilization,
        baseForce: Float,
        attacker: Civilization,
        targetCities: Collection<City>,
    ): Float {
        var combatStrength = getDefensiveMilitaryMight(attacker, civInfo) + baseForce
        val weakestTargetCityStrength = targetCities.minOfOrNull { getDefendingCityStrength(it) }
        if (weakestTargetCityStrength != null) combatStrength += weakestTargetCityStrength
        return combatStrength
    }

    @Readonly
    @VisibleForTesting
    fun calculateOffensiveCombatStrength(civInfo: Civilization, baseForce: Float): Float =
        civInfo.getStatForRanking(RankingType.Force).toFloat() + baseForce

    /**
     * Keeps only nearby targets that can be approached without entering another enemy city's
     * bombardment range. The target city's own range is allowed because engaging it is the goal.
     */
    @Readonly
    @VisibleForTesting
    fun getAccessibleAttackTargets(
        civInfo: Civilization,
        otherCiv: Civilization,
        targetCitiesWithOurCity: List<Pair<City, City>>,
    ): List<Pair<City, City>> = findAccessibleAttackTargets(
        civInfo, otherCiv, targetCitiesWithOurCity,
    ).map { Pair(it.cityToAttackFrom, it.cityToAttack) }

    @Readonly
    private fun findAccessibleAttackTargets(
        civInfo: Civilization,
        otherCiv: Civilization,
        targetCitiesWithOurCity: List<Pair<City, City>>,
    ): List<AccessibleAttackTarget> {
        val accessibleTargets = ArrayList<AccessibleAttackTarget>()
        for (cities in targetCitiesWithOurCity.distinct()) {
            val landPath = getShortAttackPath(civInfo, otherCiv, cities.first, cities.second, landOnly = true)
            if (landPath != null) {
                accessibleTargets.add(AccessibleAttackTarget(cities.first, cities.second, landPath, true))
                continue
            }
            val amphibiousPath = getShortAttackPath(civInfo, otherCiv, cities.first, cities.second, landOnly = false)
            if (amphibiousPath != null)
                accessibleTargets.add(AccessibleAttackTarget(cities.first, cities.second, amphibiousPath, false))
        }
        return accessibleTargets
    }

    @Readonly
    private fun getShortAttackPath(
        civInfo: Civilization,
        otherCiv: Civilization,
        cityToAttackFrom: City,
        cityToAttack: City,
        landOnly: Boolean,
    ): List<Tile>? {
        val tilesUnderOtherCitiesFire = HashSet<Tile>()
        for (city in otherCiv.cities) {
            if (city == cityToAttack) continue
            if (!city.getCenterTile().isExplored(civInfo)) continue
            city.getCenterTile().forEachTileInDistance(city.getBombardRange()) {
                tilesUnderOtherCitiesFire.add(it)
            }
        }

        val startTile = cityToAttackFrom.getCenterTile()
        val path = MapPathing.getConnection(
            civInfo,
            startTile,
            cityToAttack.getCenterTile(),
            { _, tile ->
                if (tile in tilesUnderOtherCitiesFire) false
                else if (tile.aerialDistanceTo(startTile) >= MAX_SHORT_ATTACK_PATH_LENGTH) false
                else if (landOnly && !tile.isLand) false
                else {
                    val owner = tile.getOwner()
                    !tile.isImpassible() && (owner == otherCiv || owner == null ||
                        civInfo.diplomacyFunctions.canPassThroughTiles(owner))
                }
            },
        )
        if (path == null || path.size >= MAX_SHORT_ATTACK_PATH_LENGTH) return null
        return path
    }

    /**
     * Estimates military might while defending friendly territory.
     *
     * The ranking Force already includes unit-specific Strength uniques. This only adds global
     * friendly-territory bonuses, such as Himeji Castle, so they are not counted twice.
     */
    @Readonly
    fun getDefensiveMilitaryMight(attacker: Civilization, defender: Civilization): Float {
        val militaryMight = defender.getStatForRanking(RankingType.Force).toFloat()
        val firstCity = defender.cities.firstOrNull()
        if (firstCity == null) return militaryMight
        val homeTile = firstCity.getCenterTile()
        val defensiveUniques = ArrayList<Unique>()
        defender.forEachMatchingUnique(UniqueType.Strength, GameContext.IgnoreConditionals) {
            if (hasFriendlyTerritoryCombatConditional(it)) defensiveUniques.add(it)
        }
        if (defensiveUniques.isEmpty()) return militaryMight

        var totalUnitForce = 0f
        var bonusUnitForce = 0f
        for (unit in defender.units.getCivUnits()) {
            var unitForce = unit.getForceEvaluation().toFloat()
            if (unit.baseUnit.isWaterUnit) unitForce /= 2f
            if (unitForce == 0f) continue

            totalUnitForce += unitForce
            val state = GameContext(
                civInfo = defender,
                unit = unit,
                tile = homeTile,
                attackedTile = homeTile,
                combatAction = CombatAction.Defend,
                otherCiv = attacker,
            )
            var strengthBonus = 0f
            for (unique in defensiveUniques) {
                if (!unique.conditionalsApply(state)) continue
                for (multipliedUnique in unique.getMultiplied(state)) {
                    val bonus = multipliedUnique.params[0].toFloatOrNull()
                    if (bonus == null) continue
                    strengthBonus += bonus
                }
            }
            bonusUnitForce += unitForce * strengthBonus / 100f
        }
        if (totalUnitForce == 0f) return militaryMight

        val bonusWeight = defender.gameInfo.ruleset.modOptions.constants.aiFriendlyTerritoryStrengthBonusWeight
        val weightedBonus = bonusUnitForce / totalUnitForce * bonusWeight
        return (militaryMight * (1f + weightedBonus)).coerceAtLeast(0f)
    }

    @Readonly
    private fun hasFriendlyTerritoryCombatConditional(unique: Unique): Boolean {
        return unique.getModifiers(UniqueType.ConditionalFightingInTiles).any {
            containsPositiveFriendlyTerritoryFilter(it.params[0])
        }
    }

    @Readonly
    private fun containsPositiveFriendlyTerritoryFilter(filter: String): Boolean {
        if (MultiFilter.isNot(filter)) return false
        if (MultiFilter.isAnd(filter))
            return MultiFilter.getAndFilters(filter).any { containsPositiveFriendlyTerritoryFilter(it) }
        return filter == "Friendly Land" || filter == "Friendly" || filter == "your"
    }

    @Readonly
    @VisibleForTesting
    fun getDefendingCityStrength(city: City): Float {
        val cityCombatant = CityCombatant(city)
        val state = GameContext(
            civInfo = city.civ,
            city = city,
            tile = city.getCenterTile(),
            ourCombatant = cityCombatant,
            attackedTile = city.getCenterTile(),
            combatAction = CombatAction.Defend,
        )
        var strengthBonus = 0f
        city.forEachMatchingUnique(UniqueType.StrengthForCities, state) {
            val bonus = it.params[0].toFloatOrNull()
            if (bonus != null) strengthBonus += bonus
        }
        return cityCombatant.getCityStrength() * (1f + strengthBonus / 100f)
    }

    @Readonly
    private fun getWonderBasedMotivations(otherCiv: Civilization): MutableList<Pair<String, Float>> {
        var wonderCount = 0
        val modifiers = mutableListOf<Pair<String, Float>>()
        for (city in otherCiv.cities) {
            val construction = city.cityConstructions.getCurrentConstruction()
            if (construction is Building && construction.hasUnique(UniqueType.TriggersCulturalVictory))
                modifiers.add(Pair("About to win", 15f))
            if (construction is BaseUnit && construction.hasUnique(UniqueType.AddInCapital))
                modifiers.add(Pair("About to win", 15f))
            wonderCount += city.cityConstructions.getBuiltBuildings().count { it.isWonder }
        }

        // The more wonders they have, the more beneficial it is to conquer them
        // Civs need an army to protect thier wonders which give the most score
        if (wonderCount > 0)
            modifiers.add(Pair("Owned Wonders", wonderCount.toFloat()))
        return modifiers
    }

    /** If they are at war with our allies, then we should join in */
    @Readonly
    private fun getAlliedWarMotivation(civInfo: Civilization, otherCiv: Civilization): Float {
        var alliedWarMotivation = 0f
        for (thirdCiv in civInfo.getDiplomacyManager(otherCiv)!!.getCommonKnownCivs()) {
            val thirdCivDiploManager = civInfo.getDiplomacyManager(thirdCiv)
            if (thirdCivDiploManager!!.isRelationshipLevelLT(RelationshipLevel.Friend)) continue

            if (thirdCiv.getDiplomacyManager(otherCiv)!!.hasFlag(DiplomacyFlags.Denunciation))
                alliedWarMotivation += 2f

            if (thirdCiv.isAtWarWith(otherCiv)) {
                alliedWarMotivation += if (thirdCivDiploManager.hasFlag(DiplomacyFlags.DefensivePact)) 15f
                else if (thirdCivDiploManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) 5f
                else 2f
            }
        }
        return alliedWarMotivation * civInfo.getPersonality().scaledFocus(PersonalityValue.Loyal)
    }

    @Readonly
    private fun getRelationshipModifier(diplomacyManager: DiplomacyManager): Float {
        val relationshipModifier = when (diplomacyManager.relationshipIgnoreAfraid()) {
            RelationshipLevel.Unforgivable -> 10f
            RelationshipLevel.Enemy -> 5f
            RelationshipLevel.Competitor -> 2f
            RelationshipLevel.Favorable -> -2f
            RelationshipLevel.Friend -> -5f
            RelationshipLevel.Ally -> -10f // this is so that ally + DoF is not too unbalanced -
            // still possible for AI to declare war for isolated city
            else -> 0f
        }
        return relationshipModifier * diplomacyManager.civInfo.getPersonality().scaledFocus(PersonalityValue.Loyal)
    }

    /** Starting XP from buildings improves the quality of future reinforcements in this estimate. */
    @Readonly
    @VisibleForTesting
    fun getMilitaryProductionEstimate(civInfo: Civilization): Float = getMilitaryProductionEstimate(civInfo, null)

    @Readonly
    private fun getMilitaryProductionEstimate(civInfo: Civilization, @LocalState report: WarMotivationReport?): Float {
        var production = civInfo.getStatForRanking(RankingType.Production).toFloat()
        val percentPerXP = civInfo.gameInfo.ruleset.modOptions.constants.aiMilitaryProductionPercentPerStartingXP
        if (percentPerXP == 0f || civInfo.isDefeated()) return production

        for (city in civInfo.cities) {
            var startingXP = 0f
            city.forEachMatchingUnique(UniqueType.UnitStartingExperience) { unique ->
                if (unique.sourceObjectType != UniqueTarget.Building && unique.sourceObjectType != UniqueTarget.Wonder)
                    return@forEachMatchingUnique
                // Specialized training (e.g. ranged or naval units only) is not a general production bonus.
                if (unique.params[0] != "Military" && unique.params[0] != "All" && unique.params[0] != "all")
                    return@forEachMatchingUnique
                if (!city.matchesFilter(unique.params[2])) return@forEachMatchingUnique
                val experience = unique.params[1].toFloatOrNull()
                if (experience != null) startingXP += experience
            }
            val cityProduction = city.cityStats.currentCityStats.production.coerceAtLeast(0f)
            val bonus = cityProduction *
                startingXP.coerceAtLeast(0f) * percentPerXP / 100f
            production += bonus
            if (report != null) report.addTraining(WarMotivationReport.CityTraining(
                civInfo.civName, city.name, cityProduction, startingXP.coerceAtLeast(0f), bonus,
            ))
        }
        return production
    }

    @Readonly
    @VisibleForTesting
    fun getProductionRatioModifier(civInfo: Civilization, otherCiv: Civilization): Float =
        getProductionRatioModifier(civInfo, otherCiv, null)

    @Readonly
    private fun getProductionRatioModifier(civInfo: Civilization, otherCiv: Civilization, @LocalState report: WarMotivationReport?): Float {
        // If either of our Civs are suffering from a supply deficit, our army must be too large
        // There is no easy way to check the raw production if a civ has a supply deficit
        // We might try to divide the current production by the getUnitSupplyProductionPenalty()
        // but it only is true for our turn and not the previous turn and might result in odd values

        val ourProduction = getMilitaryProductionEstimate(civInfo, report)
        val theirProduction = getMilitaryProductionEstimate(otherCiv, report)
        val productionRatio = ourProduction / theirProduction
        if (report != null) report.addInputs(listOf(
            "Attacker base production" to civInfo.getStatForRanking(RankingType.Production).toFloat(),
            "Defender base production" to otherCiv.getStatForRanking(RankingType.Production).toFloat(),
            "Production percent per starting XP" to civInfo.gameInfo.ruleset.modOptions.constants.aiMilitaryProductionPercentPerStartingXP,
            "Attacker adjusted production" to ourProduction,
            "Defender adjusted production" to theirProduction,
            "Production ratio" to productionRatio,
        ))
        val productionRatioModifier = when {
            productionRatio > 2f -> 10f
            productionRatio > 1.5f -> 5f
            productionRatio > 1.2 -> 3f
            productionRatio > .8f -> 0f
            productionRatio > .5f -> -5f
            productionRatio > .25f -> -10f
            else -> -15f
        }
        return productionRatioModifier
    }

    @Readonly
    private fun getScoreRatioModifier(otherCiv: Civilization, civInfo: Civilization): Float {
        // Civs with more score are more threatening to our victory
        // Bias towards attacking civs with a high score and low military
        // Bias against attacking civs with a low score and a high military
        // Designed to mitigate AIs declaring war on weaker civs instead of their rivals
        val scoreRatio = otherCiv.getStatForRanking(RankingType.Score).toFloat() / civInfo.getStatForRanking(RankingType.Score).toFloat()
        val scoreRatioModifier = when {
            scoreRatio > 2f -> 15f
            scoreRatio > 1.5f -> 10f
            scoreRatio > 1.25f -> 5f
            scoreRatio > 1f -> 2f
            scoreRatio > .8f -> 0f
            scoreRatio > .5f -> -2f
            scoreRatio > .25f -> -5f
            else -> -10f
        }
        return scoreRatioModifier
    }

    /** The same defending coalition is used by motivation and joint-war plan comparisons. */
    @Readonly
    fun getDefensiveCoalitionMilitaryMight(
        attacker: Civilization,
        defender: Civilization,
        attackingPartner: Civilization? = null,
    ): Float = getDefensiveMilitaryMight(attacker, defender) + getDefensivePactSupport(attacker, defender, attackingPartner)

    /**
     * Direct pact partners join automatically. Existing enemies are already accounted for elsewhere;
     * partners of partners are not called in by the declaration code.
     * Only nearby reinforcements count: a land approach contributes the available army, a transport
     * approach half of it. Use the ally's transit rights, including open borders through the defender.
     */
    @Readonly
    @VisibleForTesting
    fun getDefensivePactSupport(attacker: Civilization, defender: Civilization, attackingPartner: Civilization? = null): Float {
        var support = 0f
        for (diplomacy in defender.diplomacy.values) {
            if (diplomacy.diplomaticStatus != DiplomaticStatus.DefensivePact) continue
            val ally = diplomacy.otherCiv
            if (ally == attacker || ally == attackingPartner || ally.isDefeated() || ally.isAtWarWith(attacker)) continue
            val availableForce = (ally.getStatForRanking(RankingType.Force) -
                0.8f * ally.threatManager.getCombinedForceOfWarringCivs()).coerceAtLeast(0f)
            if (availableForce == 0f) continue
            if (hasShortSupportRoute(ally, attacker, landOnly = true)) support += availableForce
            else if (ally.tech.unitsCanEmbark && hasShortSupportRoute(ally, attacker, landOnly = false))
                support += availableForce * 0.5f
        }
        return support
    }

    /** A bounded multi-source search, rather than a full-map search for each pair of cities. */
    @Readonly
    private fun hasShortSupportRoute(ally: Civilization, attacker: Civilization, landOnly: Boolean): Boolean {
        val reached = HashSet<Tile>()
        @LocalState val frontier = ArrayDeque<Pair<Tile, Int>>()
        for (city in ally.cities) {
            val tile = city.getCenterTile()
            reached.add(tile)
            frontier.addLast(tile to 0)
        }
        while (frontier.isNotEmpty()) {
            val (tile, distance) = frontier.removeFirst()
            if (tile.isLand && tile.getOwner() == attacker) return true
            if (distance >= MAX_SHORT_ATTACK_PATH_LENGTH - 1) continue
            for (neighbor in tile.neighbors) {
                if (neighbor in reached || neighbor.isImpassible()) continue
                if (neighbor.isWater && (landOnly || !ally.tech.unitsCanEmbark)) continue
                if (neighbor.isOcean && !ally.tech.embarkedUnitsCanEnterOcean) continue
                val owner = neighbor.getOwner()
                if (owner != null && owner != ally && owner != attacker
                    && (ally.isAtWarWith(owner) || !ally.diplomacyFunctions.canPassThroughTiles(owner))) continue
                reached.add(neighbor)
                frontier.addLast(neighbor to distance + 1)
            }
        }
        return false
    }

    @Readonly
    private fun getCombatStrengthModifier(civInfo: Civilization, targetCiv: Civilization, ourCombatStrength: Float, theirCombatStrength: Float, @LocalState report: WarMotivationReport?): Float {
        var combatStrengthRatio = ourCombatStrength / theirCombatStrength
        if (report != null) report.addInput("Unadjusted combat strength ratio" to combatStrengthRatio)

        // At higher difficulty levels the AI gets a unit production boost.
        // In that case while we may have more units than them, we don't nessesarily want to be more aggressive.
        // This is to reduce the amount that the AI targets players at these higher levels somewhat.
        if (civInfo.isAI() && targetCiv.isHuman()) {
            combatStrengthRatio *= civInfo.gameInfo.getDifficulty().aiUnitCostModifier.pow(1.5f)
            if (report != null) report.addInput("Difficulty ratio multiplier" to civInfo.gameInfo.getDifficulty().aiUnitCostModifier.pow(1.5f))
        }
        if (report != null) report.addInput("Adjusted combat strength ratio" to combatStrengthRatio)
        val combatStrengthModifier = when {
            combatStrengthRatio > 5f -> 20f
            combatStrengthRatio > 4f -> 15f
            combatStrengthRatio > 3f -> 12f
            combatStrengthRatio > 2f -> 10f
            combatStrengthRatio > 1.8f -> 8f
            combatStrengthRatio > 1.6f -> 6f
            combatStrengthRatio > 1.4f -> 4f
            combatStrengthRatio > 1.2f -> 2f
            combatStrengthRatio > .8f -> -5f
            combatStrengthRatio > .6f -> -10f
            combatStrengthRatio > .4f -> -20f
            else -> -40f
        }
        return combatStrengthModifier
    }

    @Readonly
    private fun hasNoUnitsThatCanAttackCityWithoutDying(civInfo: Civilization, theirCity: City) = civInfo.units.getCivUnits().filter { it.isMilitary() }.none {
        val damageReceivedWhenAttacking =
            BattleDamage.calculateDamageToAttacker(
                MapUnitCombatant(it),
                CityCombatant(theirCity)
            )
        damageReceivedWhenAttacking < 100
    }

    /**
     * Counts at most two non-overlapping approaches, preferring short land routes.
     * Shared choke points and repeated routes to the same city must not multiply the bonus.
     * One land approach is neutral; a second independent one raises the modifier to at most +3.
     */
    @Readonly
    private fun getAttackPathsModifier(
        civInfo: Civilization,
        otherCiv: Civilization,
        accessibleAttackTargets: List<AccessibleAttackTarget>,
    ): Float {

        @Readonly
        fun isTileCanMoveThrough(civInfo: Civilization, tile: Tile): Boolean {
            val owner = tile.getOwner()
            return !tile.isImpassible()
                    && (owner == otherCiv || owner == null || civInfo.diplomacyFunctions.canPassThroughTiles(owner))
        }

        val usedTiles = HashSet<Tile>()
        var approachCount = 0
        var attackPathModifiers: Float = -3f

        for (attackTarget in accessibleAttackTargets.sortedWith(
            compareByDescending<AccessibleAttackTarget> { it.isLandPath }.thenBy { it.path.size }
        )) {
            if (attackTarget.path.any { it in usedTiles }) continue
            usedTiles.addAll(attackTarget.path)
            attackPathModifiers += if (attackTarget.isLandPath) 3f else 1f
            approachCount++
            if (approachCount == 2) break
        }

        if (approachCount == 0) {
            // Do an expensive BFS to find any possible attack path
            val reachableEnemyCitiesBfs = BFS(civInfo.getCapital(true)!!.getCenterTile()) { isTileCanMoveThrough(civInfo, it) }
            reachableEnemyCitiesBfs.stepToEnd()
            val reachableEnemyCities = otherCiv.cities.filter { reachableEnemyCitiesBfs.hasReachedTile(it.getCenterTile()) }
            if (reachableEnemyCities.isEmpty()) return -50f // Can't even reach the enemy city, no point in war.
            val minAttackDistance = reachableEnemyCities.minOf { reachableEnemyCitiesBfs.getPathTo(it.getCenterTile()).count() }

            // Longer attack paths are worse, but if the attack path is too far away we shouldn't completely discard the possibility
            attackPathModifiers -= (minAttackDistance - 10).coerceIn(0, 30)
        }
        return attackPathModifiers
    }
}
