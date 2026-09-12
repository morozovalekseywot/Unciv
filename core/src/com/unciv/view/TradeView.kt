package com.unciv.view

import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffersList
import com.unciv.logic.trade.TradeRequest
import yairm210.purity.annotations.Readonly

/** View of a trade negotiation between [civ] (always the viewer's own civ) and [otherCiv].
 *  Always built fresh, same as [TradeLogic] itself - staged offers live in this instance only. */
class TradeView(private val civ: Civilization, private val otherCiv: Civilization) {
    private val tradeLogic = TradeLogic(civ, otherCiv)

    // Data retrieval - lists are the live mutable instances, callers may add/remove offers directly
    @Readonly fun ourAvailableOffers(): TradeOffersList = tradeLogic.ourAvailableOffers
    @Readonly fun theirAvailableOffers(): TradeOffersList = tradeLogic.theirAvailableOffers
    @Readonly fun ourStagedOffers(): TradeOffersList = tradeLogic.currentTrade.ourOffers
    @Readonly fun theirStagedOffers(): TradeOffersList = tradeLogic.currentTrade.theirOffers
    @Readonly fun hasPendingOfferFromUs(): Boolean =
        otherCiv.tradeRequests.any { it.requestingCiv == civ.civID }
    @Readonly fun isTradePartnerAI(): Boolean = otherCiv.isAI()
    @Readonly fun isStagedTradeValid(): Boolean =
        TradeEvaluation().isTradeValid(tradeLogic.currentTrade, civ, otherCiv)
    fun isStagedTradeAcceptableToPartner(): Boolean =
        TradeEvaluation().isTradeAcceptable(tradeLogic.currentTrade.reverse(), otherCiv, civ)

    // Actions - staging
    fun setStagedTrade(trade: Trade) = tradeLogic.currentTrade.set(trade)
    fun normalizeStagedTrade(): Boolean = tradeLogic.currentTrade.normalizeOffers()

    /** Restores a trade we already sent to [otherCiv] (if any) into staging, so it can be re-displayed or retracted. */
    fun tryLoadOurPendingOffer(): Boolean {
        val existing = otherCiv.tradeRequests.firstOrNull { it.requestingCiv == civ.civID } ?: return false
        tradeLogic.currentTrade.set(existing.trade.reverse())
        return true
    }

    // Actions - proposing
    fun tryProposeStagedTrade(): Boolean {
        normalizeStagedTrade()
        if (tradeLogic.currentTrade.ourOffers.isEmpty() && tradeLogic.currentTrade.theirOffers.isEmpty()) return false
        otherCiv.tradeRequests.add(TradeRequest(civ.civID, tradeLogic.currentTrade.reverse()))
        civ.cache.updateCivResources()
        return true
    }

    /** Adjusts an unacceptable staged trade to the counteroffer the AI partner would make on its turn. */
    fun tryEqualizeStagedTrade(): Boolean {
        if (!otherCiv.isAI()) return false
        normalizeStagedTrade()
        if (tradeLogic.currentTrade.ourOffers.isEmpty() && tradeLogic.currentTrade.theirOffers.isEmpty()) return false
        if (!isStagedTradeValid()) return false
        if (isStagedTradeAcceptableToPartner()) return false

        val request = TradeRequest(civ.civID, tradeLogic.currentTrade.reverse())
        val counteroffer = TradeAutomation.getCounteroffer(otherCiv, request)
        if (counteroffer == null) return false
        counteroffer.trade.normalizeOffers()
        if (!TradeEvaluation().isTradeValid(counteroffer.trade, civ, otherCiv)) return false
        if (!TradeEvaluation().isTradeAcceptable(counteroffer.trade.reverse(), otherCiv, civ)) return false

        tradeLogic.currentTrade.set(counteroffer.trade)
        return true
    }

    /** Immediately accepts a valid staged trade when the partner is controlled by the AI. */
    fun tryAcceptStagedTradeImmediately(): Boolean {
        if (!otherCiv.isAI()) return false
        normalizeStagedTrade()
        if (tradeLogic.currentTrade.ourOffers.isEmpty() && tradeLogic.currentTrade.theirOffers.isEmpty()) return false
        if (!isStagedTradeValid()) return false
        if (!isStagedTradeAcceptableToPartner()) return false

        tryRetractOffer()
        tradeLogic.acceptTrade()
        return true
    }

    fun tryRetractOffer(): Boolean {
        if (!hasPendingOfferFromUs()) return false
        otherCiv.tradeRequests.removeAll { it.requestingCiv == civ.civID }
        civ.cache.updateCivResources()
        return true
    }
}
