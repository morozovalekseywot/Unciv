package com.unciv.logic.automation.civilization

import yairm210.purity.annotations.InternalState

/** On-demand debug output, never stored in a saved game. Values are a snapshot, not live civ references. */
@InternalState
class WarMotivationReport {
    enum class StandaloneWarReadiness(val caption: String) {
        Unavailable("Standalone war assessment is unavailable"),
        Blocked("War is blocked by prerequisites or target accessibility"),
        InsufficientMotivation("Not enough motivation to start preparing a standalone war"),
        SkippedBeforePaths("AI skips war planning because motivation before the path adjustment is below zero"),
        CanPrepare("Can start preparing a standalone war"),
        Preparing("Preparation is underway; the declaration check has not passed yet"),
        PreparingBelowThreshold("Preparation is underway, but motivation is below the declaration threshold"),
        PreparationBlocked("The WaryOf flag prevents starting war preparation"),
        CanDeclare("Can declare war independently now"),
    }

    var standaloneWarReadiness = StandaloneWarReadiness.Unavailable
        internal set
    var warPlanningSkippedBeforePaths = false
        internal set
    var motivation: Float? = null
        internal set
    var rejectionReason: String? = null
        internal set
    val declarationBlockers = ArrayList<String>()
    val components = ArrayList<Pair<String, Float>>()
    val inputs = ArrayList<Pair<String, Float>>()
    val targetCities = ArrayList<Pair<String, Float>>()
    val excludedCities = ArrayList<String>()
    val training = ArrayList<CityTraining>()
    var weakestCity: String? = null
        internal set
    var expansionFallback = false
        internal set

    internal fun addInput(input: Pair<String, Float>) { inputs.add(input) }
    internal fun addInputs(values: List<Pair<String, Float>>) { inputs.addAll(values) }
    internal fun addComponent(component: Pair<String, Float>) { components.add(component) }
    internal fun addComponents(values: List<Pair<String, Float>>) { components.addAll(values) }
    internal fun addTargetCities(values: List<Pair<String, Float>>) { targetCities.addAll(values) }
    internal fun addExcludedCities(names: List<String>) { excludedCities.addAll(names) }
    internal fun addTraining(value: CityTraining) { training.add(value) }

    data class CityTraining(
        val civilization: String,
        val city: String,
        val production: Float,
        val startingXP: Float,
        val productionBonus: Float,
    )

    internal fun reject(reason: String): Float {
        rejectionReason = reason
        return 0f
    }
}
