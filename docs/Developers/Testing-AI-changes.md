# Testing AI changes

The codebase supports the ability to run automated simulations and compare the results for AI development.

## Enabling

Set up a custom build configuration.

Set the working directory to android/assets, just like when running desktop. And use the `unciv.desktop.main` module.
![image](../assets/ConsoleLauncher_Config.png)

## Running Simulations

Execute the ConsoleLauncher Build Configuration set up above. Results should appear in the Console of the IDE.

## Configuring Simulations

Within the `desktop\src\ConsoleLauncher.kt` file, you can adjust the number of simulations to run, which nations to use, etc.

Recommend using generic civs with no Uniques. You can see the code add a generic `Nation` to the `ruleset` object, and you can key different behavior throughout the code using the Nation Name constant as the control switch.

You can also adjust the game parameters and map parameters. To get more consistent results, turning off Natural Wonders and Barbarians can help.

By default the order of players is randomized to make it more fair.

The `statTurns` list parameter allows the system to save certain stats at particular turns so you can track it over the course of the games.

## Understanding Results

By default the console will report the number of wins per civ, type of win, and the `popsum` at the end of the game. This is the average amount of population for the whole civ at the end of the game.

It is a good habit to validate your expectations for which Civ would win, how they would win, and any changes in the reported values. Some changes won't show up in the win rates, so are better tested by making a scenario in the Map Editor or with the in-game Console then testing the AI behaviors.

The p-value is also reported, showing based on the winrate how likely this result is in a binomial test. If this value is very small, then there is a low chance this arose out of random chance and that your changes made a statistically significant change in the overall winrate. Running 200-400 Sims is usually a good baseline point.

## Automated war-frequency regression check

`WarFrequencySimulationTest` runs real AI turns headlessly: no window, clicking, or manual play is required.
It is opt-in because a batch is substantially slower than unit tests. Ordinary `tests:test` runs skip it,
but always run the fast `WarSimulationLogTest` checks of the event counter.
The opt-in batch gives the test JVM a 1 GiB heap; ordinary tests keep their usual limit.

```sh
./gradlew tests:test --tests 'com.unciv.logic.simulation.WarFrequencySimulationTest' \
  -Dunciv.warSimulation.enabled=true \
  -Dunciv.warSimulation.games=20 \
  -Dunciv.warSimulation.turns=200 \
  -Dunciv.warSimulation.label=war-balance-before
```

The default sample has four AI major civilizations (Aztecs, Rome, Greece, Japan), Small Pangaea,
Quick speed, King difficulty, and no city-states, barbarians, ruins, or natural wonders.
It stops at victory or the turn limit, whichever comes first. No motivation values or declaration
decisions are mocked or forced. Defaults are 3 games and 200 turns.

Reports go to `tests/build/reports/war-simulation/<label>/`:

- `games.csv`: actual turns simulated, newly initiated wars, first-war turn, defensive-pact entries,
  voluntary joins, and elapsed time for each game.
- `wars-N.csv`: every major-versus-major declaration, its turn, participants, and `WarType`.
- `summary.txt`: totals, wars per game, games without a new war, and mean first-war turn.
  The mean excludes games without wars; always read it together with the peaceful-game count.

The count of **wars started** includes `DirectWar` and `TeamWar`; the two declarations of a joint
attack count as one start. Joining an existing war or being drawn in by a defensive pact does not
count as a new start. City-state/barbarian conflicts are excluded. The event log is optional,
transient, and attached to one game, so normal play does not accumulate diagnostic data.

The test fails if the **whole batch** starts fewer than `unciv.warSimulation.minimumWars` wars
(default 1). This catches total inactivity, not subtle balance regressions. Set it to 0 for a purely
observational run, or explicitly raise it for a chosen regression baseline. Do not require a war in
every game: a peaceful outcome can be legitimate.

Starts are saved under `tests/build/reports/war-simulation/starts-small-quick-v1/` and reused by
later labels. To compare revisions, retain these starts and run the same game count/turn limit with
a new label, then compare the two `games.csv` files. Both revisions need this diagnostic harness;
backport only the instrumentation when measuring an older AI formula. A label's existing directory is never overwritten.
Seed alone is insufficient for identical maps, and even loading the same start does **not** guarantee
identical AI randomness. Repeat batches before drawing conclusions from small differences.
Preserve the reports outside `build` before running `clean`.

This small-map sample is a quick warning system, not proof that frequency or war outcomes are balanced
on larger maps, other difficulties, mods, or in human-player diplomacy. A separate old-version run is
needed to measure the effect of a formula change; a successful current-version run alone only shows
that AI wars still occur in these scenarios.
