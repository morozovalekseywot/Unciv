package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.managers.PendingRuinChoice
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

/** Mandatory, scrollable choice using the current ruleset's rewards, including modded ones. */
class RuinsRewardPopup(
    private val worldScreen: WorldScreen,
    private val choice: PendingRuinChoice
) : Popup(worldScreen) {
    private val manager = worldScreen.viewingCiv.ruinsManager

    init {
        showRewards()
    }

    private fun showRewards(failed: Boolean = false) {
        clear()
        val contentWidth = (worldScreen.stage.width * 0.65f).coerceAtMost(600f)
        addGoodSizedLabel("Choose a reward from ancient ruins").row()
        addGoodSizedLabel("The two most recently received rewards cannot be chosen.").row()
        if (failed)
            addGoodSizedLabel("This reward could not be granted. Please choose another.").row()

        val rewards = manager.getAvailableRewards(choice)
        if (rewards.isEmpty()) {
            addGoodSizedLabel("No rewards are available.").row()
            addCloseButton {
                if (worldScreen.canChangeState) manager.dismissEmptyChoice(choice)
            }
            return
        }

        for (reward in rewards) {
            val button = reward.name.toTextButton()
            val iconName = reward.uniqueObjects.firstNotNullOfOrNull { getRewardIcon(it) }
            if (iconName != null) {
                button.clearChildren()
                button.add(ImageGetter.getStatIcon(iconName)).size(24f).padRight(8f)
                button.add(button.label).growX()
            }
            button.label.setWrap(true)
            button.label.setAlignment(Align.center)
            button.onActivation {
                if (!worldScreen.canChangeState) return@onActivation
                if (manager.chooseReward(choice, reward.name)) close()
                else showRewards(failed = true)
            }
            add(button).width(contentWidth).padTop(12f).row()
            val lines = reward.civilopediaText + reward.uniqueObjects
                .filter { !it.isHiddenToUsers() && it.type != UniqueType.OnlyAvailable && it.type != UniqueType.Unavailable }
                .map { describeRewardEffect(it) }
            add(MarkupRenderer.render(lines, contentWidth, linkAction = worldScreen::openCivilopedia)).row()
        }
        pack()
    }

    /** Use effect types rather than translated reward names, including renamed/modded rewards. */
    private fun getRewardIcon(unique: Unique): String? = when (unique.type) {
        UniqueType.OneTimeGainStat, UniqueType.OneTimeGainStatRange ->
            unique.params.last().takeIf { it == "Culture" || it == "Gold" }
        UniqueType.OneTimeFreeTechRuins, UniqueType.OneTimeFreeTech -> "Science"
        UniqueType.OneTimeGainPopulation, UniqueType.OneTimeGainPopulationRandomCity -> "Population"
        else -> null
    }

    private fun describeRewardEffect(unique: Unique): FormattedLine {
        // Keep conditions and special modifiers visible for modded effects.
        if (unique.modifiers.any { it.type != UniqueType.ModifiedByGameSpeed })
            return FormattedLine(unique)
        when (unique.type) {
            UniqueType.OneTimeGainStat, UniqueType.OneTimeGainStatRange -> {
                val tile = worldScreen.viewingCiv.gameInfo.tileMap[choice.position]
                val amount = UniqueTriggerActivation.getStatGainAmount(unique, worldScreen.viewingCiv, tile)
                if (amount != null) {
                    when (unique.params.last()) {
                        "Culture" -> return FormattedLine("Culture: [$amount]")
                        "Gold" -> return FormattedLine("Gold: [$amount]")
                    }
                }
            }
            UniqueType.OneTimeFreeTechRuins -> {
                val text = if (unique.params[0] == "1")
                    "Grants one random researchable technology for free. Era: [${unique.params[1]}]."
                else
                    "Free random researchable technologies: [${unique.params[0]}]. Era: [${unique.params[1]}]."
                return FormattedLine(text, link = FormattedLine(unique).link)
            }
            UniqueType.OneTimeRevealCrudeMap -> {
                // The simple description is for the standard reward; retain the parameters
                // for mods that reveal different areas or reveal them without any gaps.
                if (unique.params == listOf("4", "4", "80"))
                    return FormattedLine("Reveals a random area of the map near the ruins. Some tiles remain unexplored.")
            }
            else -> Unit
        }
        return FormattedLine(unique)
    }

    override fun close() {
        worldScreen.shouldUpdate = true
        super.close()
    }
}
