package com.unciv.logic.map.mapgenerator

import com.unciv.logic.map.HexCoord

/** Optional, in-memory diagnostics for map-generation tests and balance investigations.
 *  Records coordinates rather than retaining every attempted map. Not part of saved games. */
class MapGenerationDiagnostics {
    val attempts = ArrayList<Attempt>()
    /** One-based index of the attempt whose map was returned. */
    var selectedAttempt = 0
        internal set

    internal fun clear() {
        attempts.clear()
        selectedAttempt = 0
    }

    /** [satisfiedRegions] is -1 when the city-site guarantee does not apply. */
    data class Attempt(val seed: Long, val satisfiedRegions: Int, val regions: List<RegionResult>)

    data class RegionResult(
        val capital: HexCoord?,
        val tiles: Set<HexCoord>,
        val type: String,
        val passed: Boolean,
        /** City center -> distinct luxury deposit, including partial selections in failed regions. */
        val cityLuxuries: Map<HexCoord, HexCoord>
    )
}
