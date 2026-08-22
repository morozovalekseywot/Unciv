package com.unciv.logic.city.managers

object CityConquestConstants {
    /** Divisor applied to city population to determine the number of turns
     *  a newly conquered city spends in Resistance.
     *  Vanilla behavior is a 1:1 ratio (divisor of 1) - lowered so large
     *  late-game cities don't suffer disproportionately long resistance. */
    const val RESISTANCE_TURNS_POPULATION_DIVISOR = 1.25

    /** Hard cap on the number of turns a conquered city can spend in Resistance,
     *  regardless of population. */
    const val RESISTANCE_TURNS_MAX = 18
}
