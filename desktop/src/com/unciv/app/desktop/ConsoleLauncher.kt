package com.unciv.app.desktop

import com.unciv.Constants
import com.unciv.Constants.simulationCiv1
import com.unciv.Constants.simulationCiv2
import com.unciv.UncivGame
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.simulation.Simulation
import com.unciv.models.metadata.*
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.Speed
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.utils.Log
import kotlin.time.ExperimentalTime

internal object ConsoleLauncher {

    // To run,set working directory to android/assets in run configuration
    @ExperimentalTime
    @JvmStatic
    fun main(arg: Array<String>) {
        Log.backend = DesktopLogBackend()

        val game = UncivGame(true)

        UncivGame.Current = game
        UncivGame.Current.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
        }

        RulesetCache.loadRulesets(true)
        TileSetCache.loadTileSetConfigs(true)
        SkinCache.loadSkinConfigs(true)

        runSimulation()
    }

    @ExperimentalTime
    private fun runSimulation() {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!

        // Shoshone balance test: 1 Shoshone vs 5 generic civs (no uniques)
        // Medium Pangaea, 6 players, 5 city-states — realistic game conditions
        val shoshoneNation = ruleset.nations["Shoshone"]
            ?: throw Exception("Shoshone not found in ruleset!")
        val generic1 = Nation().apply { name = simulationCiv1 }
        val generic2 = Nation().apply { name = simulationCiv2 }
        val generic3 = Nation().apply { name = "SimulationCiv3" }
        val generic4 = Nation().apply { name = "SimulationCiv4" }
        val generic5 = Nation().apply { name = "SimulationCiv5" }
        ruleset.nations[simulationCiv1] = generic1
        ruleset.nations[simulationCiv2] = generic2
        ruleset.nations["SimulationCiv3"] = generic3
        ruleset.nations["SimulationCiv4"] = generic4
        ruleset.nations["SimulationCiv5"] = generic5

        val gameParameters = getGameParameters(shoshoneNation, generic1, generic2, generic3, generic4, generic5)
        gameParameters.players.last().setNationTransient(ruleset) // set the Spectator
        val mapParameters = getMapParameters()
        val gameSetupInfo = GameSetupInfo(gameParameters, mapParameters)
        val newGame = GameStarter.startNewGame(gameSetupInfo)
        newGame.gameParameters.victoryTypes = ArrayList(newGame.ruleset.victories.keys)
        UncivGame.Current.gameInfo = newGame

        val simulation = Simulation(newGame, 500, 8)
        //Unless the effect size is very large, you'll typically need a large number of games to get a statistically significant result

        simulation.start()
    }

    private fun getMapParameters(): MapParameters {
        return MapParameters().apply {
            mapSize = MapSize.Medium  // Medium supports 6 players
            type = MapType.pangaea    // Pangaea is default but explicit for clarity
            noRuins = true
            noNaturalWonders = true
            legendaryStart = true
            strategicBalance = true
        }
    }

    private fun getGameParameters(vararg civilizations: Nation): GameParameters {
        return GameParameters().apply {
            difficulty = "King"
            numberOfCityStates = 5
            speed = Speed.DEFAULT
            noBarbarians = true
            players = ArrayList<Player>().apply {
                for (it in civilizations) {
                    add(Player(it))
                }
                add(Player(Constants.spectator, PlayerType.Human))
            }
        }
    }

}
