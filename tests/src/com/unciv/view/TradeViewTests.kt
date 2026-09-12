package com.unciv.view

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TradeViewTests {

    private lateinit var testGame: TestGame
    private lateinit var human: Civilization
    private lateinit var ai: Civilization

    @Before
    fun setUp() {
        testGame = TestGame()
        human = testGame.addCiv(isPlayer = true)
        ai = testGame.addCiv()
        human.diplomacyFunctions.makeCivilizationsMeet(ai)
        human.addGold(1000)
        ai.addGold(1000)
        human.stats.statsForNextTurn.gold = 20f
    }

    @Test
    fun `Equalizing adds an acceptable AI counteroffer`() {
        val tradeView = createUnacceptableTrade()

        assertFalse(tradeView.isStagedTradeAcceptableToPartner())
        assertTrue(tradeView.tryEqualizeStagedTrade())
        assertTrue(tradeView.isStagedTradeAcceptableToPartner())
        assertTrue(tradeView.ourStagedOffers().any {
            it.type == TradeOfferType.Gold_Per_Turn && it.amount > 0
        })
    }

    @Test
    fun `Normalizing trade nets fungible offers but preserves treaties`() {
        val trade = Trade()
        val peaceTreaty = TradeOffer(
            Constants.peaceTreaty,
            TradeOfferType.Treaty,
            speed = testGame.gameInfo.speed
        )
        trade.ourOffers.add(
            TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, 5, speed = testGame.gameInfo.speed)
        )
        trade.theirOffers.add(
            TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, 2, speed = testGame.gameInfo.speed)
        )
        trade.ourOffers.add(
            TradeOffer(Constants.flatGold, TradeOfferType.Gold, 10, speed = testGame.gameInfo.speed)
        )
        trade.theirOffers.add(
            TradeOffer(Constants.flatGold, TradeOfferType.Gold, 10, speed = testGame.gameInfo.speed)
        )
        trade.ourOffers.add(peaceTreaty)
        trade.theirOffers.add(peaceTreaty.copy())

        assertTrue(trade.normalizeOffers())
        assertEquals(3, trade.ourOffers.single { it.type == TradeOfferType.Gold_Per_Turn }.amount)
        assertTrue(trade.theirOffers.none { it.type == TradeOfferType.Gold_Per_Turn })
        assertTrue(trade.ourOffers.none { it.type == TradeOfferType.Gold })
        assertTrue(trade.theirOffers.none { it.type == TradeOfferType.Gold })
        assertTrue(trade.ourOffers.any { it.type == TradeOfferType.Treaty })
        assertTrue(trade.theirOffers.any { it.type == TradeOfferType.Treaty })
    }

    @Test
    fun `Equalizing peace deal can ask for gold when AI already offers gold`() {
        ai.stats.statsForNextTurn.gold = 9f
        human.getDiplomacyManager(ai)!!.declareWar()
        val tradeView = TradeView(human, ai)
        val peaceTreaty = TradeOffer(
            Constants.peaceTreaty,
            TradeOfferType.Treaty,
            speed = testGame.gameInfo.speed
        )
        tradeView.ourStagedOffers().add(peaceTreaty)
        tradeView.theirStagedOffers().add(peaceTreaty.copy())
        tradeView.theirStagedOffers().add(
            TradeOffer(Constants.flatGold, TradeOfferType.Gold, 14, speed = testGame.gameInfo.speed)
        )
        tradeView.theirStagedOffers().add(
            TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, 2, speed = testGame.gameInfo.speed)
        )

        assertTrue(tradeView.isStagedTradeValid())
        assertFalse(tradeView.isStagedTradeAcceptableToPartner())
        assertTrue(tradeView.tryEqualizeStagedTrade())
        assertTrue(tradeView.isStagedTradeAcceptableToPartner())
        assertTrue(tradeView.ourStagedOffers().any {
            it.type == TradeOfferType.Gold && it.amount > 0
        })
        assertTrue(tradeView.theirStagedOffers().none { it.type == TradeOfferType.Gold })
    }

    @Test
    fun `Acceptable AI trade is completed immediately`() {
        val tradeView = createUnacceptableTrade()
        tradeView.tryEqualizeStagedTrade()

        assertTrue(tradeView.tryAcceptStagedTradeImmediately())
        assertEquals(1100, human.gold)
        assertEquals(900, ai.gold)
        assertTrue(human.getDiplomacyManager(ai)!!.trades.isNotEmpty())
        assertTrue(ai.tradeRequests.isEmpty())
    }

    @Test
    fun `Trade between humans remains pending`() {
        val otherHuman = testGame.addCiv(isPlayer = true)
        human.diplomacyFunctions.makeCivilizationsMeet(otherHuman)
        val tradeView = TradeView(human, otherHuman)
        tradeView.ourStagedOffers().add(
            TradeOffer(Constants.flatGold, TradeOfferType.Gold, 10, speed = testGame.gameInfo.speed)
        )

        assertFalse(tradeView.tryAcceptStagedTradeImmediately())
        assertTrue(tradeView.tryProposeStagedTrade())
        assertEquals(1, otherHuman.tradeRequests.size)
        assertEquals(human.civID, otherHuman.tradeRequests.single().requestingCiv)
    }

    private fun createUnacceptableTrade(): TradeView {
        val tradeView = TradeView(human, ai)
        tradeView.theirStagedOffers().add(
            TradeOffer(Constants.flatGold, TradeOfferType.Gold, 100, speed = testGame.gameInfo.speed)
        )
        return tradeView
    }
}
