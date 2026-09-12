package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView
import com.unciv.view.ForeignCivView

class TradeTable(
    private val civ: CivView,
    private val otherCivilization: ForeignCivView,
    private val diplomacyScreen: DiplomacyScreen
): Table(BaseScreen.skin) {
    internal val tradeView = civ.getTradeView(otherCivilization)
    internal val offerColumnsTable = OfferColumnsTable(tradeView, diplomacyScreen, civ, otherCivilization) { onChange() }
    // This is so that after a trade has been traded, we can switch out the offersToDisplay to start anew - this is the easiest way
    private val offerColumnsTableWrapper = Table()

    val offerTradeText = if (tradeView.isTradePartnerAI()) "Offer trade"
        else "{Offer trade}\n({They'll decide on their turn})"
    private val offerButton = offerTradeText.toTextButton()
    private val equalizeButton = "Equalize trade".toTextButton()

    private fun retractOffer() {
        tradeView.tryRetractOffer()
        updateActionButtons()
    }

    init {
        offerColumnsTableWrapper.add(offerColumnsTable)
        add(offerColumnsTableWrapper).row()

        val lowerTable = Table().apply { defaults().pad(10f) }

        if (tradeView.tryLoadOurPendingOffer())
            offerColumnsTable.update()

        updateActionButtons()

        offerButton.onClick {
            if (tradeView.hasPendingOfferFromUs()) {
                retractOffer()
                return@onClick
            }
            ensureResearchAgreementCanBePaid()
            normalizeStagedTrade()

            if (!tradeView.isTradePartnerAI()) {
                tradeView.tryProposeStagedTrade()
                updateActionButtons()
                return@onClick
            }

            when {
                !tradeView.isStagedTradeValid() ->
                    ToastPopup("Our proposed trade is no longer relevant!".tr(), diplomacyScreen)
                !tradeView.isStagedTradeAcceptableToPartner() ->
                    ToastPopup("I think not.".tr(), diplomacyScreen)
                tradeView.tryAcceptStagedTradeImmediately() -> {
                    diplomacyScreen.updateLeftSideTable(otherCivilization.getCiv())
                    diplomacyScreen.setRightSideFlavorText(
                        otherCivilization.getCiv(),
                        "Pleasure doing business with you!",
                        "Very well."
                    )
                }
            }
        }

        if (tradeView.isTradePartnerAI()) {
            equalizeButton.onClick {
                ensureResearchAgreementCanBePaid()
                normalizeStagedTrade()
                when {
                    !tradeView.isStagedTradeValid() ->
                        ToastPopup("Our proposed trade is no longer relevant!".tr(), diplomacyScreen)
                    tradeView.isStagedTradeAcceptableToPartner() ->
                        ToastPopup("That is acceptable.".tr(), diplomacyScreen)
                    tradeView.tryEqualizeStagedTrade() -> onChange()
                    else -> ToastPopup("I think not.".tr(), diplomacyScreen)
                }
            }
            lowerTable.add(equalizeButton)
        }
        lowerTable.add(offerButton)

        lowerTable.pack()
        lowerTable.y = 10f
        add(lowerTable)
        pack()
    }

    /** Adds gold to either side when needed to let both civilizations pay for a research agreement. */
    private fun ensureResearchAgreementCanBePaid() {
        if (tradeView.ourStagedOffers().none { it.name == Constants.researchAgreement }) return

        val researchCost = civ.getResearchAgreementCost(otherCivilization)
        val currentPlayerGoldOffer = tradeView.ourStagedOffers()
            .firstOrNull { it.type == TradeOfferType.Gold }
        val currentPlayerOfferedGold =
            if (currentPlayerGoldOffer == null) 0 else currentPlayerGoldOffer.amount
        val otherCivGoldOffer = tradeView.theirStagedOffers()
            .firstOrNull { it.type == TradeOfferType.Gold }
        val otherCivOfferedGold = if (otherCivGoldOffer == null) 0 else otherCivGoldOffer.amount
        val newCurrentPlayerGold = civ.gold + otherCivOfferedGold - researchCost
        val newOtherCivGold = otherCivilization.gold + currentPlayerOfferedGold - researchCost

        if (newCurrentPlayerGold < 0) {
            val goldOffer = tradeView.theirAvailableOffers().first { it.type == TradeOfferType.Gold }
            offerColumnsTable.addOffer(
                goldOffer.copy(amount = -newCurrentPlayerGold),
                tradeView.theirStagedOffers(),
                tradeView.ourStagedOffers()
            )
        }
        if (newOtherCivGold < 0) {
            val goldOffer = tradeView.ourAvailableOffers().first { it.type == TradeOfferType.Gold }
            offerColumnsTable.addOffer(
                goldOffer.copy(amount = -newOtherCivGold),
                tradeView.ourStagedOffers(),
                tradeView.theirStagedOffers()
            )
        }
    }

    private fun onChange() {
        offerColumnsTable.update()
        tradeView.tryRetractOffer()
        updateActionButtons()
    }

    private fun normalizeStagedTrade() {
        if (!tradeView.normalizeStagedTrade()) return
        offerColumnsTable.update()
    }

    private fun updateActionButtons() {
        val tradeIsEmpty = tradeView.theirStagedOffers().isEmpty() && tradeView.ourStagedOffers().isEmpty()
        if (tradeView.hasPendingOfferFromUs()) {
            offerButton.setText("Retract offer".tr())
            offerButton.isEnabled = true
        } else {
            offerButton.setText(offerTradeText.tr())
            offerButton.isEnabled = !tradeIsEmpty
        }
        equalizeButton.isEnabled = !tradeIsEmpty
    }

    internal fun update() {
        offerColumnsTable.update()
        updateActionButtons()
    }
}
