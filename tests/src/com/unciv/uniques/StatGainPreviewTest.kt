package com.unciv.uniques

import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt
import kotlin.random.Random

@RunWith(BaseTestRunner::class)
class StatGainPreviewTest {
    @Test
    fun `gold preview preserves the original tile seeded roll and rounding`() {
        val game = TestGame()
        game.makeHexagonalMap(3)
        val civ = game.addCiv(isPlayer = true)
        for (speed in listOf("Quick", "Standard", "Epic", "Marathon")) {
            game.setSpeed(speed)
            for (tile in listOf(game.getTile(0, 0), game.getTile(1, 0), game.getTile(0, 2))) {
                for (range in listOf("[50]-[100]", "[100]-[50]")) {
                    val unique = Unique("Gain $range [Gold] <(modified by game speed)>")
                    val roll = (50..100).random(Random(tile.position.hashCode()))
                    val expected = (roll * game.gameInfo.speed.goldCostModifier).roundToInt()
                    assertEquals(expected, UniqueTriggerActivation.getStatGainAmount(unique, civ, tile))
                    val goldBefore = civ.gold
                    assertTrue(UniqueTriggerActivation.triggerUnique(unique, civ, tile = tile))
                    assertEquals(expected, civ.gold - goldBefore)
                }
            }
        }
    }

    @Test
    fun `unscaled and invalid effects are handled without changing game state`() {
        val game = TestGame()
        game.setSpeed("Marathon")
        val civ = game.addCiv(isPlayer = true)
        assertEquals(20, UniqueTriggerActivation.getStatGainAmount(Unique("Gain [20] [Culture]"), civ, null))
        assertNull(UniqueTriggerActivation.getStatGainAmount(Unique("Gain [bad] [Gold]"), civ, null))
        assertNull(UniqueTriggerActivation.getStatGainAmount(Unique("Gain [20] [Production]"), civ, null))
        assertNull(UniqueTriggerActivation.getStatGainAmount(Unique("Free Technology"), civ, null))
        assertEquals(0, civ.policies.storedCulture)
    }
}
