package com.unciv.logic.civilization.managers

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.RuinReward
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.utils.randomWeighted
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

class RuinsManager(
    private val lastChosenRewards: ArrayList<String> = arrayListOf("", ""),
    private val pendingChoices: ArrayList<PendingRuinChoice> = arrayListOf()
) : IsPartOfGameInfoSerialization {

    @Transient
    lateinit var civInfo: Civilization
    @Transient
    lateinit var validRewards: Collection<RuinReward>

    @Readonly fun clone() = RuinsManager(ArrayList(lastChosenRewards), ArrayList(pendingChoices.map { it.clone() }))

    @Readonly fun hasPendingChoice() = pendingChoices.isNotEmpty()
    @Readonly fun hasPendingChoice(unit: MapUnit) = pendingChoices.any { it.unitId == unit.id }
    @Readonly fun getPendingChoice() = pendingChoices.firstOrNull()

    fun setTransients(civInfo: Civilization) {
        this.civInfo = civInfo
        validRewards = civInfo.gameInfo.ruleset.ruinRewards.values
    }

    private fun rememberReward(reward: String) {
        lastChosenRewards[0] = lastChosenRewards[1]
        lastChosenRewards[1] = reward
    }

    @Readonly
    private fun getShuffledPossibleRewards(triggeringUnit: MapUnit): Iterable<RuinReward> {
        val candidates =
            validRewards.asSequence().filter { isPossibleReward(it, triggeringUnit, triggeringUnit.getTile()) }
            // This might be a dirty way to do this, but it works (we do have randomWeighted in CollectionExtensions, but below we
            // need to choose another when the first choice's TriggerActivations report failure, and that's simpler this way)
            // For each possible reward, this feeds (reward.weight) copies of this reward to the overall Sequence to implement 'weight'.
            .flatMap { reward -> generateSequence { reward }.take(reward.weight) }
            // Convert to List since Sequence.shuffled would do one anyway, Mutable so shuffle doesn't need to pull a copy
            .toMutableList()
        // The resulting List now gets shuffled, using a tile-based random to thwart save-scumming.
        // Note both Sequence.shuffled and Iterable.shuffled (with a 'd') always pull an extra copy of a MutableList internally, even if you feed them one.
        candidates.shuffle(Random(triggeringUnit.getTile().position.toVector2().hashCode()))
        return candidates
    }

    @Readonly
    private fun isPossibleReward(ruinReward: RuinReward, unit: MapUnit?, tile: Tile): Boolean {
        if (ruinReward.name in lastChosenRewards) return false
        if (ruinReward.isUnavailableBySettings(civInfo.gameInfo)) return false
        val gameContext = GameContext(civInfo, unit = unit, tile = tile)
        if (ruinReward.hasUnique(UniqueType.Unavailable, gameContext)) return false
        if (ruinReward.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals)
                .any { !it.conditionalsApply(gameContext) }) return false
        return true
    }

    fun selectNextRuinsReward(triggeringUnit: MapUnit) {
        if (triggeringUnit.hasUnique(UniqueType.ChooseRuinsReward) && !civInfo.isAIOrAutoPlaying()) {
            pendingChoices.add(PendingRuinChoice(triggeringUnit.id, triggeringUnit.getTile().position))
            return
        }
        for (possibleReward in getShuffledPossibleRewards(triggeringUnit)) {
            if (applyReward(possibleReward, triggeringUnit, triggeringUnit.getTile())) break
        }
    }

    /** Resolve by ID at use time: upgrading, capture and loading can replace the unit object. */
    private fun getTriggeringUnit(choice: PendingRuinChoice): MapUnit? {
        val unit = civInfo.units.getUnitById(choice.unitId)
        if (unit == null || unit.getTile().position != choice.position) return null
        return unit
    }

    fun getAvailableRewards(choice: PendingRuinChoice): List<RuinReward> {
        if (choice !== getPendingChoice()) return emptyList()
        val unit = getTriggeringUnit(choice)
        val tile = civInfo.gameInfo.tileMap[choice.position]
        val context = GameContext(civInfo, unit = unit, tile = tile)
        return validRewards.filter { reward ->
            reward.weight > 0 && reward.name !in choice.failedRewards && isPossibleReward(reward, unit, tile) &&
                reward.uniqueObjects.any { unique ->
                    !isPresentationOnly(unique) && unique.conditionalsApply(context) &&
                        unique.getUniqueMultiplier(context) > 0 &&
                        UniqueTriggerActivation.getTriggerFunction(unique, civInfo, unit = unit, tile = tile,
                            notification = reward.notification, triggerNotificationText = "from the ruins") != null
                }
        }
    }

    /** Revalidate at confirmation; stale callbacks and repeated clicks must never grant a second reward. */
    fun chooseReward(choice: PendingRuinChoice, rewardName: String): Boolean {
        val reward = getAvailableRewards(choice).firstOrNull { it.name == rewardName }
        if (reward == null) return false
        val unit = getTriggeringUnit(choice)
        val tile = civInfo.gameInfo.tileMap[choice.position]
        pendingChoices.removeAt(0)
        if (applyReward(reward, unit, tile, requireGameplayEffect = true)) return true

        // Some effects (e.g. placing a unit) can still fail after the availability check.
        choice.failedRewards.add(rewardName)
        pendingChoices.add(0, choice)
        return false
    }

    fun dismissEmptyChoice(choice: PendingRuinChoice) {
        if (choice === getPendingChoice() && getAvailableRewards(choice).isEmpty())
            pendingChoices.removeAt(0)
    }

    /** Handles a pending human choice if the game is subsequently handed over to AI/autoplay. */
    fun resolvePendingChoicesAutomatically() {
        while (pendingChoices.isNotEmpty()) {
            val choice = pendingChoices.first()
            val rewards = getAvailableRewards(choice)
            if (rewards.isEmpty()) {
                dismissEmptyChoice(choice)
                continue
            }
            val rng = Random(choice.position.toVector2().hashCode())
            chooseReward(choice, rewards.randomWeighted(rng) { it.weight.toFloat() }.name)
        }
    }

    private fun isPresentationOnly(unique: Unique) =
        unique.type == UniqueType.PlaySound || unique.type == UniqueType.ChooseMusic

    private fun applyReward(reward: RuinReward, unit: MapUnit?, tile: Tile, requireGameplayEffect: Boolean = false): Boolean {
        val context = GameContext(civInfo, unit = unit, tile = tile)
        var hadEffect = false
        for (unique in reward.uniqueObjects) {
            if (requireGameplayEffect && isPresentationOnly(unique)) continue
            if (!unique.conditionalsApply(context)) continue
            repeat(unique.getUniqueMultiplier(context)) {
                hadEffect = UniqueTriggerActivation.triggerUnique(unique, civInfo, unit = unit, tile = tile,
                    notification = reward.notification, triggerNotificationText = "from the ruins") || hadEffect
            }
        }
        if (!hadEffect) return false
        rememberReward(reward.name)
        if (requireGameplayEffect) {
            for (unique in reward.uniqueObjects) {
                if (!isPresentationOnly(unique) || !unique.conditionalsApply(context)) continue
                repeat(unique.getUniqueMultiplier(context)) {
                    UniqueTriggerActivation.triggerUnique(unique, civInfo, unit = unit, tile = tile)
                }
            }
        }
        return true
    }
}

/** Only identifiers are persisted, so a choice also survives saves and undo snapshots. */
class PendingRuinChoice(
    val unitId: Int = -1,
    val position: HexCoord = HexCoord.Zero,
    val failedRewards: ArrayList<String> = arrayListOf()
) : IsPartOfGameInfoSerialization {
    @Readonly fun clone() = PendingRuinChoice(unitId, position, ArrayList(failedRewards))
}
