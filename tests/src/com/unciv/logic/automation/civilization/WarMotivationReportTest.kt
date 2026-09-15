package com.unciv.logic.automation.civilization

import com.unciv.UncivGame
import com.unciv.logic.automation.unit.CityLocationTileRanker
import com.unciv.logic.automation.civilization.WarMotivationReport.StandaloneWarReadiness
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapSize
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.nation.Personality
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

@RunWith(BaseTestRunner::class)
class WarMotivationReportTest {
    private lateinit var game: TestGame
    private lateinit var attacker: Civilization
    private lateinit var defender: Civilization

    @Before
    fun setUp() {
        game = TestGame()
        game.makeHexagonalMap(8)
        attacker = game.addCiv()
        defender = game.addCiv(
            "[+20]% Strength <for [All] units> <when fighting in [Friendly Land] tiles>",
        )
        game.addCity(attacker, game.getTile(-4, 0), initialPopulation = 12)
        game.addCity(defender, game.getTile(0, 0))
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        defender.cities.first().getCenterTile().setExplored(attacker, true)
        attacker.cities.first().getCenterTile().setExplored(defender, true)
        for (tile in listOf(HexCoord(-3, 0), HexCoord(-4, 1), HexCoord(-5, 1), HexCoord(-5, 0)))
            game.addUnit("Swordsman", attacker, game.getTile(tile))
        game.addUnit("Swordsman", defender, game.getTile(1, 0))
        attacker.stats.happiness = 20
        defender.stats.happiness = 20
    }

    @Test
    fun `report matches the full AI calculation and components sum to the total`() {
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertNull(report.rejectionReason)
        assertTrue(report.declarationBlockers.isEmpty())
        assertEquals(fullMotivation(attacker, defender), report.motivation!!, 0f)
        assertEquals(report.motivation!!, report.components.sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
        assertTrue(report.components.any { it.first == "Attack paths" })
        assertTrue(report.components.any { it.first == "Expansion opportunity" && it.second == 0f })
    }

    @Test
    fun `negative reports include path contributions skipped by threshold queries`() {
        game.addCity(attacker, game.getTile(-4, 4))
        // A water barrier leaves a nonzero path contribution even though repeated routes to the
        // same city no longer earn a bonus.
        for (tile in defender.cities.first().getCenterTile().neighbors)
            game.setTileTerrain(tile.position, "Coast")
        attacker.getDiplomacyManager(defender)!!.signDeclarationOfFriendship()
        attacker.getDiplomacyManager(defender)!!.signDefensivePact(30)
        val personality = Personality().apply { declareWar = 0f }
        game.ruleset.personalities["Peaceful test"] = personality
        attacker.nation.personality = "Peaceful test"

        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertNull(report.rejectionReason)
        assertTrue(report.motivation!! < 0f)
        val paths = report.components.single { it.first == "Attack paths" }.second
        assertTrue(paths != 0f)
        val shortResult = MotivationToAttackAutomation.hasAtLeastMotivationToAttack(attacker, defender, 0f)
        assertEquals(shortResult + paths, report.motivation!!, 0.0001f)
        assertEquals(fullMotivation(attacker, defender), report.motivation!!, 0f)
    }

    @Test
    fun `both directions are independently evaluated including a human player`() {
        defender.playerType = PlayerType.Human
        game.setDifficulty("Deity")
        val forward = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        val reverse = MotivationToAttackAutomation.getWarMotivationReport(defender, attacker)
        assertEquals(fullMotivation(attacker, defender), forward.motivation!!, 0f)
        assertEquals(fullMotivation(defender, attacker), reverse.motivation!!, 0f)
        assertEquals(game.gameInfo.getDifficulty().aiUnitCostModifier.pow(1.5f),
            forward.inputs.single { it.first == "Difficulty ratio multiplier" }.second, 0f)
        assertFalse(reverse.inputs.any { it.first == "Difficulty ratio multiplier" })
    }

    @Test
    fun `report shows defensive force and dynamic training production`() {
        val city = attacker.cities.first()
        city.cityConstructions.addBuilding("Barracks")
        city.cityConstructions.addBuilding("Armory")
        city.cityStats.currentCityStats.production = 100f
        attacker.stats.statsForNextTurn.production = 100f
        defender.stats.statsForNextTurn.production = 100f

        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        val inputs = report.inputs.toMap()
        assertEquals(130f, inputs["Attacker adjusted production"]!!, 0.001f)
        assertEquals(100f, inputs["Defender adjusted production"]!!, 0.001f)
        assertEquals(3f, report.components.single { it.first == "Relative production" }.second, 0f)
        assertTrue(inputs["Friendly territory defense adjustment"]!! > 0f)
        val training = report.training.single { it.civilization == attacker.civName }
        assertEquals(30f, training.startingXP, 0f)
        assertEquals(30f, training.productionBonus, 0.001f)
        assertEquals(report.targetCities.minOf { it.second }, inputs["Weakest target city strength"]!!, 0f)
    }

    @Test
    fun `unknown civilizations have no fabricated zero score`() {
        val unknown = game.addCiv()
        game.addCity(unknown, game.getTile(6, 0))
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, unknown)
        assertNull(report.motivation)
        assertTrue("Civilizations have not met" in report.declarationBlockers)
        assertTrue(report.components.isEmpty())
        assertNull(attacker.getDiplomacyManager(unknown))
    }

    @Test
    fun `inaccessible cities explain a zero score`() {
        // A mountain ring leaves no land or amphibious route to the target.
        for (tile in defender.cities.first().getCenterTile().neighbors)
            game.setTileTerrain(tile.position, "Mountain")
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertEquals(0f, report.motivation!!, 0f)
        assertEquals("No accessible target cities", report.rejectionReason)
        assertTrue(report.components.isEmpty())
        assertTrue(defender.cities.first().name in report.excludedCities)
        assertEquals(fullMotivation(attacker, defender), report.motivation!!, 0f)
    }

    @Test
    fun `no surviving attackers is distinct from a neutral score`() {
        for (unit in attacker.units.getCivUnits().toList()) unit.destroy()
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertEquals(0f, report.motivation!!, 0f)
        assertEquals("No military unit can survive an attack on any target city", report.rejectionReason)
        assertTrue(report.components.isEmpty())
    }

    @Test
    fun `existing war and peace treaty block declarations without hiding motivation`() {
        val diplomacy = attacker.getDiplomacyManager(defender)!!
        diplomacy.diplomaticStatus = DiplomaticStatus.War
        var report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertTrue("Already at war" in report.declarationBlockers)
        assertNotNull(report.motivation)
        diplomacy.diplomaticStatus = DiplomaticStatus.Peace
        val trade = Trade()
        trade.ourOffers.add(TradeOffer("Peace Treaty", TradeOfferType.Treaty, duration = 10))
        diplomacy.trades.add(trade)
        report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertTrue("A peace treaty prevents declaring war" in report.declarationBlockers)
        assertNotNull(report.motivation)
    }

    @Test
    fun `declaration prerequisites are separate from formula contributions`() {
        attacker.stats.happiness = 0
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertTrue("The attacker has no positive happiness" in report.declarationBlockers)
        assertNotNull(report.motivation)
        assertEquals(fullMotivation(attacker, defender), report.motivation!!, 0f)
    }

    @Test
    fun `cities screened by two border cities are excluded from the report`() {
        game = TestGame()
        game.makeHexagonalMap(8, "Mountain")
        for (x in -6..6) game.setTileTerrain(HexCoord(x, 0), "Plains")
        for (tile in listOf(HexCoord(-2, -2), HexCoord(-2, -1), HexCoord(3, 2), HexCoord(3, 1)))
            game.setTileTerrain(tile, "Plains")
        attacker = game.addCiv()
        defender = game.addCiv()
        game.addCity(attacker, game.getTile(-5, 0))
        val firstBorder = game.addCity(defender, game.getTile(-2, -2), initialPopulation = 20)
        val secondBorder = game.addCity(defender, game.getTile(3, 2), initialPopulation = 20)
        val weakCity = game.addCity(defender, game.getTile(5, 0))
        for (city in listOf(firstBorder, secondBorder)) city.cityConstructions.addBuilding("Walls")
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        for (city in defender.cities) city.getCenterTile().setExplored(attacker, true)
        game.addUnit("Modern Armor", attacker, game.getTile(-4, 0))

        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertNull(report.rejectionReason)
        assertTrue(weakCity.name in report.excludedCities)
        assertFalse(report.targetCities.any { it.first == weakCity.name })
        assertTrue(report.weakestCity in listOf(firstBorder.name, secondBorder.name))
        assertEquals(fullMotivation(attacker, defender), report.motivation!!, 0f)
    }

    @Test
    fun `expansion motivation is included using the same settler evaluation as the AI`() {
        game = TestGame()
        game.makeHexagonalMap(16, "Snow")
        game.tileMap.mapParameters.mapSize = MapSize.Medium
        attacker = game.addCiv()
        defender = game.addCiv()
        val capital = game.addCity(defender, game.getTile(0, 0))
        game.addCity(attacker, game.getTile(0, 15))
        attacker.diplomacyFunctions.makeCivilizationsMeet(defender)
        capital.getCenterTile().setExplored(attacker, true)
        game.addUnit("Settler", attacker, game.getTile(7, 0))
        game.addUnit("Swordsman", attacker, game.getTile(7, 1))
        game.ruleset.modOptions.constants.minimumCityLocationTileValue = 0f
        val site = game.getTile(4, 0)
        site.forEachTileInDistance(2) { game.setTileTerrain(it.position, "Grassland") }
        game.setTileTerrainAndFeatures(site.position, "Grassland", "Hill")
        site.hasBottomRiver = true

        val expansion = CityLocationTileRanker.getExpansionWarMotivation(attacker, defender)
        assertTrue(expansion > 0f)
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertTrue(report.expansionFallback)
        assertEquals(expansion, report.components.single { it.first == "Expansion opportunity" }.second, 0f)
        assertEquals(fullMotivation(attacker, defender), report.motivation!!, 0f)
    }

    @Test
    fun `missing cities and supply limited production are handled explicitly`() {
        val nomad = game.addCiv()
        game.addUnit("Settler", nomad, game.getTile(6, 0))
        attacker.diplomacyFunctions.makeCivilizationsMeet(nomad)
        val nomadReport = MotivationToAttackAutomation.getWarMotivationReport(attacker, nomad)
        assertNull(nomadReport.motivation)
        assertTrue("The target has no cities" in nomadReport.declarationBlockers)

        for (tile in game.tileMap.values.filter { it.isLand && it.getOwner() == null && it.militaryUnit == null }.take(40))
            game.addUnit("Warrior", attacker, tile)
        assertTrue(attacker.stats.getUnitSupplyDeficit() != 0)
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertTrue(report.components.any { it.first == "Over unit supply" })
        assertFalse(report.components.any { it.first == "Relative production" })
        assertFalse(report.inputs.any { it.first == "Production ratio" })
        assertTrue(report.training.isEmpty())
    }

    @Test
    fun `opening diagnostics does not alter future motivation or diplomacy`() {
        val before = fullMotivation(attacker, defender)
        val diplomacy = attacker.getDiplomacyManager(defender)!!
        val status = diplomacy.diplomaticStatus
        val plans = diplomacy.getFlag(DiplomacyFlags.WaryOf)
        val trades = diplomacy.trades.size
        val units = attacker.units.getCivUnits().count()
        repeat(2) { MotivationToAttackAutomation.getWarMotivationReport(attacker, defender) }
        assertEquals(before, fullMotivation(attacker, defender), 0f)
        assertEquals(status, diplomacy.diplomaticStatus)
        assertEquals(plans, diplomacy.getFlag(DiplomacyFlags.WaryOf))
        assertEquals(trades, diplomacy.trades.size)
        assertEquals(units, attacker.units.getCivUnits().count())
    }

    @Test
    fun `Russian diagnostics translate components inputs and nested rejection reasons`() {
        UncivGame.Current.settings.language = "Russian"
        UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        for ((caption, _) in report.components + report.inputs)
            assertNotEquals("Missing Russian translation: $caption", caption, caption.tr())
        for (readiness in StandaloneWarReadiness.entries)
            assertNotEquals(readiness.caption, readiness.caption.tr())
        assertEquals("Мотивация войны: 12.50", "War motivation: [12.50]".tr())
        assertEquals("Расчёт остановлен: Нет доступных городов-целей",
            "Calculation stopped: [No accessible target cities]".tr())
    }

    @Test
    fun `city states can be inspected in either direction`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val city = game.addCity(cityState, game.getTile(0, 6))
        game.addUnit("Swordsman", cityState, game.getTile(1, 6))
        attacker.diplomacyFunctions.makeCivilizationsMeet(cityState)
        city.getCenterTile().setExplored(attacker, true)
        attacker.cities.first().getCenterTile().setExplored(cityState, true)
        for ((from, to) in listOf(attacker to cityState, cityState to attacker)) {
            val report = MotivationToAttackAutomation.getWarMotivationReport(from, to)
            assertNull(report.rejectionReason)
            assertEquals(fullMotivation(from, to), report.motivation!!, 0f)
        }
    }

    @Test
    fun `preparation and surprise war thresholds are strict`() {
        assertEquals(StandaloneWarReadiness.InsufficientMotivation, readiness(15f))
        assertEquals(StandaloneWarReadiness.CanPrepare, readiness(15.01f))
        assertEquals(StandaloneWarReadiness.CanPrepare, readiness(20f))
        assertEquals(StandaloneWarReadiness.CanPrepare, readiness(40f))
        assertEquals(StandaloneWarReadiness.CanDeclare, readiness(40.01f))
    }

    @Test
    fun `prepared war requires both twenty motivation and the preparation check`() {
        val diplomacy = attacker.getDiplomacyManager(defender)!!
        diplomacy.setFlag(DiplomacyFlags.WaryOf, -1)
        assertEquals(StandaloneWarReadiness.Preparing, readiness(20f))
        diplomacy.setFlag(DiplomacyFlags.WaryOf, -2)
        assertEquals(StandaloneWarReadiness.CanDeclare, readiness(20f))
        diplomacy.setFlag(DiplomacyFlags.WaryOf, -100)
        assertEquals(StandaloneWarReadiness.PreparingBelowThreshold, readiness(19.99f))
        assertEquals(-100, diplomacy.getFlag(DiplomacyFlags.WaryOf))
    }

    @Test
    fun `positive wary flag blocks preparation but not surprise war`() {
        attacker.getDiplomacyManager(defender)!!.setFlag(DiplomacyFlags.WaryOf, 3)
        assertEquals(StandaloneWarReadiness.PreparationBlocked, readiness(25f))
        assertEquals(StandaloneWarReadiness.CanDeclare, readiness(41f))
    }

    @Test
    fun `high motivation cannot bypass happiness peace or existing war blockers`() {
        attacker.stats.happiness = 0
        assertEquals(StandaloneWarReadiness.Blocked, readiness(100f))
        attacker.stats.happiness = 20
        val diplomacy = attacker.getDiplomacyManager(defender)!!
        diplomacy.diplomaticStatus = DiplomaticStatus.War
        assertEquals(StandaloneWarReadiness.Blocked, readiness(100f))
        diplomacy.diplomaticStatus = DiplomaticStatus.Peace
        val trade = Trade()
        trade.ourOffers.add(TradeOffer("Peace Treaty", TradeOfferType.Treaty, duration = 10))
        diplomacy.trades.add(trade)
        assertEquals(StandaloneWarReadiness.Blocked, readiness(100f))
    }

    @Test
    fun `report readiness respects rejected targets and remains a snapshot`() {
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        val originalReadiness = report.standaloneWarReadiness
        for (tile in defender.cities.first().getCenterTile().neighbors)
            game.setTileTerrain(tile.position, "Mountain")
        val blocked = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertEquals(StandaloneWarReadiness.Blocked, blocked.standaloneWarReadiness)
        assertEquals(originalReadiness, report.standaloneWarReadiness)
    }

    @Test
    fun `readiness explains the AI short circuit before path evaluation`() {
        attacker.getDiplomacyManager(defender)!!.signDeclarationOfFriendship()
        attacker.getDiplomacyManager(defender)!!.signDefensivePact(30)
        val report = MotivationToAttackAutomation.getWarMotivationReport(attacker, defender)
        assertTrue(report.declarationBlockers.isEmpty())
        assertTrue(report.warPlanningSkippedBeforePaths)
        assertEquals(StandaloneWarReadiness.SkippedBeforePaths, report.standaloneWarReadiness)
        assertTrue(report.components.any { it.first == "Attack paths" })
    }

    private fun readiness(motivation: Float) =
        DeclareWarPlanEvaluator.getStandaloneWarReadiness(attacker, defender, motivation)

    private fun fullMotivation(from: Civilization, to: Civilization): Float =
        MotivationToAttackAutomation.hasAtLeastMotivationToAttack(from, to, Float.NEGATIVE_INFINITY,
            CityLocationTileRanker.getExpansionWarMotivation(from, to))
}
