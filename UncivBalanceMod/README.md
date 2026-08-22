# UncivBalanceMod

Extension mod for [Unciv](https://github.com/yairm210/Unciv) that fixes a number of
balance issues without requiring a custom build of the game. Does not replace the
base ruleset — install it **in addition to** `Civ V - Vanilla` and/or
`Civ V - Gods & Kings`.

## Changes

### Wonders (`Buildings.json`)

- **Hubble Space Telescope**: free Great Scientists reduced from 2 to 1. Getting
  two free Great Scientists at once (instant tech + generation points) was a
  disproportionate late-game science spike.
- **Porcelain Tower**: Research Agreement science bonus reduced from +50% to +30%.
- **Angkor Wat**: flat culture increased from 1 to 4, original border-growth
  abilities kept intact. The wonder is expensive and arrives late for its main
  effect (cheaper border growth) — by the time it's built, most cities have
  already bought out their first 3 tiles, so the bonus mostly helps with the
  4th+ tile only. Raising flat culture makes it a worthwhile investment on its
  own. `requiredTech` unchanged (Education in G&K).
- **Pentagon**: added a permanent `-25%` maintenance cost for Military units on
  top of the original upgrade-cost discount. The wonder is very late (requires
  Combined Arms in G&K) and a one-time upgrade discount is nearly useless by
  that point in the game. `requiredTech` unchanged.
- **Kremlin**: restored to its original Civilization 5 ability profile —
  `+50%` Production when constructing Armored units in the city, and a free
  Social Policy on completion (cost 1060, +1 culture). In current Unciv, the
  wonder's properties actually matched the "Redoubt" from the original game
  (a purely defensive city-strength bonus), which is less interesting.
  `requiredTech` unchanged (Metallurgy in G&K).
- **CN Tower**: required technology moved from Telecommunications (Information
  era) to Radar (Atomic era) — noticeably earlier, while still staying after
  Radio, which is required for the free Broadcast Tower this wonder grants.

> **Note on rulesets**: Vanilla and Gods & Kings define different
> `requiredTech` values for several of these wonders (e.g. Porcelain Tower:
> Architecture in G&K vs Education in Vanilla; Angkor Wat: Education in G&K vs
> Theology in Vanilla; Kremlin: Metallurgy in G&K vs Acoustics in Vanilla).
> This mod's `requiredTech` values match **Gods & Kings** specifically. If you
> play with Vanilla only (no G&K), double check these values still make sense
> for your ruleset.

### Pantheons (`Beliefs.json`, `ModOptions.json`)

- **New pantheon: Earth Mother** — `+1 Faith` from every Copper, Iron and Salt
  tile. The base game had no pantheon covering these strategic resources.
- **New pantheon: Sea Expanse** — `+1 Faith` from every Coast and Ocean tile.
- **New pantheon: Blessing of the Waves** — `+2 Faith` from every Fish, Whales
  and Pearls tile. Together with Sea Expanse, this covers the base game's
  complete lack of a pantheon rewarding coastal/ocean-heavy starts.
- **Removed pantheons/follower beliefs** (weakest and least impactful):
  - `Ancestor Worship` — effectively just +1 Culture per Shrine, negligible.
  - `Goddess of Protection` — bonus only applies when a city attacks, which
    happens rarely if ever during a game.
  - `Monument to the Gods` — by the time religion spreads to all cities, the
    Ancient/Classical eras it applies to are usually already over.
  - `Religious Settlements` — too weak (`-15%` border growth culture cost).
  - `Sacred Waters` — `+1` Happiness, and only in cities on a River tile.
  - `Liturgical Drama` — at most `+1` Faith from a single building, trivial.

### Units (`Units.json`)

- **Great Person point pool regrouping**: in Gods & Kings, Great Artist,
  Scientist, Merchant and Engineer all shared the SAME point pool (getting one
  type raised the cost of all the others). Regrouped into 3 separate pools:
  `Scientist+Engineer` (both strong and always desirable), `Artist` on its own,
  and `Merchant` on its own (comparatively weak, so it shouldn't get more
  expensive just because you picked up a Scientist).
- **Anti-Aircraft Gun / Mobile SAM redesign**: these units only had `strength`
  set (no `rangedStrength`), meaning the same stat governed both melee combat
  and air defense — they ended up as tough as late-game infantry in melee while
  Artillery of the same era is deliberately weak there. Split into:
  - Low `strength` (Artillery-tier for the era) so ground units can actually
    kill them in melee.
  - High `rangedStrength` (previous strength value) used specifically when
    defending against ranged/air attacks (including Air Sweep).
  - `range: 0` and `"Cannot attack"` — the unit should never initiate an
    attack itself, only defend/intercept.
  - `interceptRange` increased from 2 to 4, plus `+2` extra interceptions per
    turn, so a single enemy Air Sweep can no longer "bait out" the unit's only
    interception attempt for free before a real bomber/nuke attack follows.

### Difficulty (`Difficulties.json`)

- **Immortal**: `aiCityGrowthModifier` raised from 0.75 to 0.8 (AI cities need
  slightly more food to grow, softening the jump from Emperor's 0.85), and the
  free starting `Worker` removed from the AI's bonus starting units.

## Not yet decided

A few items from the original balance discussion (see `problems.md` in the
Unciv repo root) are intentionally left out of this mod for now:
- Nuclear Missile interception (`Cannot be intercepted` on the Missile unit
  type) — in the original Civilization 5 this is also not solvable through
  standard means, so no reference balance exists to copy from.
- Mongolia's unique Keshik unit stats.

## Installation

Copy (or symlink) this folder into your Unciv installation's `mods` directory,
next to the `.jar` file on Desktop (or `android/assets/mods/` when running from
Android Studio). Enable it in New Game alongside Vanilla and/or Gods & Kings.

Two code changes complementing this mod's content (conquered city resistance
duration cap, and deterministic tech theft target for spies) live directly in
the Unciv fork's Kotlin source, not in this mod.
