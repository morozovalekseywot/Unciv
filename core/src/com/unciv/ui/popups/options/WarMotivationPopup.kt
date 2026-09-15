package com.unciv.ui.popups.options

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.civilization.DeclareWarPlanEvaluator
import com.unciv.logic.automation.civilization.MotivationToAttackAutomation
import com.unciv.logic.automation.civilization.WarMotivationReport
import com.unciv.logic.civilization.Civilization
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import java.util.Locale

/** Debug-only, explicitly refreshed: no recurring work and no references from the game model. */
internal class WarMotivationPopup(
    stage: Stage,
    private val gameInfo: GameInfo,
) : Popup(stage) {
    private val matrix = Table(skin)
    private val turnLabel = "".toLabel()

    init {
        addGoodSizedLabel("AI war evaluations", 24).row()
        add(turnLabel).row()
        addGoodSizedLabel("Rows attack columns. Select a value for the breakdown.").row()
        addGoodSizedLabel("* Declaration blocked; — no evaluation. Values are not probabilities or promises of war.", 16).row()
        val scrollPane = AutoScrollPane(matrix, skin)
        scrollPane.setOverscroll(false, false)
        add(scrollPane).width(stage.width * 0.8f).height(stage.height * 0.45f).row()
        addButton("Refresh list") { refresh() }
        addCloseButton()
        refresh()
    }

    private fun refresh() {
        // Turn processing can mutate caches and collections on a background thread.
        val world = GUI.getWorldScreen()
        if (world.gameInfo !== gameInfo || world.isNextTurnUpdateRunning() || world.autoPlay.isAutoPlaying()) {
            ToastPopup("Wait until turn processing has finished", stageToShowOn)
            return
        }
        val civilizations = gameInfo.civilizations.filter {
            !it.isBarbarian && !it.isSpectator() && !it.isCityState && it.isAlive()
        }.sortedBy { it.civName }
        matrix.clear()
        matrix.defaults().pad(4f)
        turnLabel.setText("Turn [${gameInfo.turns}]".tr())
        matrix.add("Attacker / Target".toLabel().apply {
            wrap = true
            setAlignment(Align.left)
        }).width(160f).left()
        for (target in civilizations)
            matrix.add(civCaption(target).toLabel().apply { wrap = true }).width(120f)
        matrix.row()
        for (attacker in civilizations) {
            matrix.add(civCaption(attacker).toLabel().apply { wrap = true }).width(160f).left()
            for (target in civilizations) {
                if (attacker == target) {
                    matrix.add("—".toLabel())
                    continue
                }
                val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, target)
                val value = report.motivation
                var caption = "—"
                if (value != null) caption = formatValue(value)
                if (report.declarationBlockers.isNotEmpty() || report.rejectionReason != null) caption += " *"
                val button = caption.toTextButton()
                button.onClick { showDetails(attacker, target, report) }
                matrix.add(button).minWidth(90f).fillX()
            }
            matrix.row()
        }
    }

    private fun civCaption(civ: Civilization, hideIcons: Boolean = false): String {
        if (civ.isHuman()) return "{${civ.civName}} ({Human})".tr(hideIcons = hideIcons)
        return civ.civName.tr(hideIcons = hideIcons)
    }

    private fun showDetails(attacker: Civilization, target: Civilization, report: WarMotivationReport) {
        val popup = Popup(stageToShowOn)
        val detailText = StringBuilder()
        fun addText(text: String, size: Int = Constants.defaultFontSize, color: Color = Color.WHITE, plainText: String = text) {
            detailText.appendLine(plainText.tr(hideIcons = true))
            popup.addGoodSizedLabel(text, size, color = color).row()
        }
        addText("${civCaption(attacker)} → ${civCaption(target)}", 24,
            plainText = "${civCaption(attacker, hideIcons = true)} → ${civCaption(target, hideIcons = true)}")
        addText(turnLabel.text.toString(), 16)
        if (attacker.isHuman())
            addText("For a human player this is the AI formula, not the player's intentions.", 16)
        if (attacker.isCityState)
            addText("City-states do not choose war targets independently.", 16)
        addText("This is the full current motivation, not a record of the last AI decision.", 16)
        val motivation = report.motivation
        if (motivation != null)
            addText("War motivation: [${formatValue(motivation)}]")
        else addText("War motivation is unavailable")
        addText(report.standaloneWarReadiness.caption, 20)
        addText("Start preparation: more than [${formatValue(DeclareWarPlanEvaluator.PREPARE_WAR_THRESHOLD)}], if preparation is not blocked.", 16)
        addText("Prepared war: at least [${formatValue(DeclareWarPlanEvaluator.DECLARE_WAR_THRESHOLD)}] and a passed preparation check.", 16)
        addText("War without preparation: more than [${formatValue(DeclareWarPlanEvaluator.SURPRISE_WAR_THRESHOLD)}].", 16)
        addText("Joint war checks: more than [${formatValue(DeclareWarPlanEvaluator.TEAM_WAR_CHECK_THRESHOLD)}] to invite an ally; at least [${formatValue(DeclareWarPlanEvaluator.JOIN_WAR_CHECK_THRESHOLD)}] to join a war. Additional plan checks must pass.", 16)
        addText("These are eligibility checks, not a promise of war. Another target or a joint war plan may be preferred.", 16)
        if (report.declarationBlockers.isNotEmpty()) {
            addText("Declaration prerequisites — first failed checks", 20)
            for (reason in report.declarationBlockers)
                addText(reason, 16, color = Color.ORANGE)
        }
        val rejection = report.rejectionReason
        if (rejection != null) {
            addText("Calculation stopped: [$rejection]", 16, color = Color.ORANGE)
        }
        if (report.components.isNotEmpty()) {
            addText("Contributions to war motivation", 20)
            addValues(popup, detailText, report.components, signed = true)
            addText("A missing component was not applicable; zero means it was evaluated.", 16)
        }
        if (report.inputs.isNotEmpty()) {
            addText("Calculation inputs", 20)
            addValues(popup, detailText, report.inputs)
        }
        if (report.targetCities.isNotEmpty()) {
            addText("Cities considered — defensive strength", 20)
            addValues(popup, detailText, report.targetCities)
            val weakestCity = report.weakestCity
            if (weakestCity != null)
                addText("Weakest city used: [$weakestCity]", 16)
        }
        if (report.expansionFallback)
            addText("Expansion fallback: the original capital is considered even without a short attack path.", 16)
        if (report.excludedCities.isNotEmpty()) {
            addText("Cities excluded — no safe short approach", 20)
            for (city in report.excludedCities) addText(city, 16)
        }
        if (report.training.isNotEmpty()) {
            addText("Production adjustment from training buildings", 20)
            for (city in report.training) {
                addText("{${city.civilization}}: {${city.city}}", 18)
                addValues(popup, detailText, listOf(
                    "City production" to city.production,
                    "General starting XP from buildings" to city.startingXP,
                    "Added production in the estimate" to city.productionBonus,
                ))
            }
        }
        val textToCopy = detailText.toString().trimEnd()
        popup.addButton("Copy details") {
            Gdx.app.clipboard.contents = textToCopy
            ToastPopup("Details copied to clipboard", stageToShowOn)
        }
        popup.addCloseButton()
        popup.open(force = true)
    }

    private fun addValues(popup: Popup, detailText: StringBuilder, values: List<Pair<String, Float>>, signed: Boolean = false) {
        val table = Table(skin)
        table.defaults().pad(4f)
        for ((name, value) in values) {
            val formattedValue = formatValue(value, signed)
            detailText.appendLine("${name.tr(hideIcons = true)}: $formattedValue")
            table.add(name.toLabel(fontSize = 16).apply { wrap = true })
                .width(stageToShowOn.width * 0.38f).left()
            table.add(formattedValue.toLabel(fontSize = 16)).right().row()
        }
        popup.add(table).row()
    }

    private fun formatValue(value: Float, signed: Boolean = false): String {
        if (value.isNaN()) return "Undefined".tr()
        if (value == Float.POSITIVE_INFINITY) return "+∞"
        if (value == Float.NEGATIVE_INFINITY) return "−∞"
        return String.format(Locale.ROOT, if (signed) "%+.2f" else "%.2f", value)
            .trimEnd('0').trimEnd('.')
    }
}
