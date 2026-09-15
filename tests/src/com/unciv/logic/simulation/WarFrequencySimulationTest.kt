package com.unciv.logic.simulation

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import com.unciv.utils.Log
import com.unciv.utils.LogBackend
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import java.io.File

/** Opt-in real AI games, not mocked motivation checks. See Testing-AI-changes.md. */
@RunWith(BaseTestRunner::class)
class WarFrequencySimulationTest {
    @Test
    @RedirectOutput(RedirectPolicy.Show)
    fun measureWarsInAutomatedGames() {
        assumeTrue(java.lang.Boolean.getBoolean("unciv.warSimulation.enabled"))
        val games = Integer.getInteger("unciv.warSimulation.games", 3)
        val turns = Integer.getInteger("unciv.warSimulation.turns", 200)
        val minimumWars = Integer.getInteger("unciv.warSimulation.minimumWars", 1)
        val label = System.getProperty("unciv.warSimulation.label", "current")
        require(games in 1..1000 && turns in 1..1000 && minimumWars >= 0)
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val root = File("../../tests/build/reports/war-simulation")
        val output = File(root, label)
        // Never overwrite a previous measurement accidentally.
        require(!output.exists()) { "Report already exists: $output. Choose a new unciv.warSimulation.label." }
        output.mkdirs()
        val starts = File(root, "starts-small-quick-v1")
        starts.mkdirs()
        RulesetCache.loadRulesets(noMods = true)
        val oldGame = if (UncivGame.isCurrentInitialized()) UncivGame.Current else UncivGame()
        val oldLogBackend = Log.backend
        val rows = arrayListOf("sample,seed,turns,wars_started,first_war_turn,defensive_entries,voluntary_joins,elapsed_ms")
        var totalWars = 0
        var peacefulGames = 0
        val firstWarTurns = ArrayList<Int>()
        try {
            // Keep errors visible without flooding the report with per-path debug messages.
            Log.backend = object : LogBackend by oldLogBackend {
                override fun isRelease() = true
            }
            UncivGame.Current = UncivGame()
            // Ordinary spies retain millions of getter invocations over a long AI batch.
            // We only stub persistence; no invocation history or verification is needed.
            val settings = mock(GameSettings::class.java, withSettings()
                .spiedInstance(GameSettings()).defaultAnswer(CALLS_REAL_METHODS).stubOnly())
            doNothing().`when`(settings).save()
            settings.soundEffectsVolume = 0f
            settings.musicVolume = 0f
            UncivGame.Current.settings = settings
            for (sample in 1..games) {
                val startFile = File(starts, "$sample.json")
                if (!startFile.exists())
                    startFile.writeText(UncivFiles.gameInfoToString(createGame(sample.toLong()), forceZip = false))
                val game = UncivFiles.gameInfoFromString(startFile.readText())
                UncivGame.Current.gameInfo = game
                val log = WarSimulationLog()
                game.warSimulationLog = log
                game.simulateUntilWin = true
                game.simulateMaxTurns = turns
                val started = System.nanoTime()
                println("WAR_SIMULATION $label: game $sample/$games, up to turn $turns")
                game.nextTurn()
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue("Simulation did not advance", game.turns > 0)
                totalWars += log.warsStarted
                if (log.warsStarted == 0) peacefulGames++
                val firstWar = log.firstWarTurn
                if (firstWar != null) firstWarTurns.add(firstWar)
                val firstWarText = if (firstWar == null) "" else firstWar.toString()
                rows.add(listOf(sample, game.tileMap.mapParameters.seed, game.turns, log.warsStarted,
                    firstWarText, log.declarations.count { it.type == WarType.DefensivePactWar },
                    log.declarations.count { it.type == WarType.JoinWar }, elapsed).joinToString(","))
                File(output, "games.csv").writeText(rows.joinToString("\n", postfix = "\n"))
                File(output, "wars-$sample.csv").writeText("turn,attacker,defender,type\n" +
                    log.declarations.joinToString("\n", postfix = "\n") {
                        "${it.turn},${it.attacker},${it.defender},${it.type}"
                    })
                println("WAR_SIMULATION ${rows.last()}")
            }
            val firstWarMean = if (firstWarTurns.isEmpty()) "N/A" else firstWarTurns.average().toString()
            val summary = "Games: $games\nTurn limit: $turns\nWars started: $totalWars\n" +
                "Wars per game: ${totalWars.toDouble() / games}\n" +
                "Games without a new war: $peacefulGames/$games\n" +
                "Mean first war turn (only games with wars): $firstWarMean\n"
            File(output, "summary.txt").writeText(summary)
            println(summary)
            assertTrue("Only $totalWars wars started; requested minimum $minimumWars. See $output", totalWars >= minimumWars)
        } finally {
            Log.backend = oldLogBackend
            UncivGame.Current = oldGame
        }
    }

    private fun createGame(seed: Long): GameInfo {
        val parameters = GameParameters().apply {
            difficulty = "King"
            speed = "Quick"
            numberOfCityStates = 0
            noBarbarians = true
            players.clear()
            for (nation in listOf("Aztecs", "Rome", "Greece", "Japan"))
                players.add(Player(nation, PlayerType.AI))
            players.add(Player(Constants.spectator, PlayerType.Human))
        }
        val map = MapParameters().apply {
            mapSize = MapSize.Small
            type = MapType.pangaea
            noRuins = true
            noNaturalWonders = true
            this.seed = seed
        }
        return GameStarter.startNewGame(GameSetupInfo(parameters, map))
    }
}
