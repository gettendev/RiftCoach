package com.riftcoach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineTest {

    private val data: GameData by lazy {
        val dir = File("src/main/assets")
        DataParser.parse(
            File(dir, "hero_list.json").readText(Charsets.UTF_8),
            File(dir, "rank.json").readText(Charsets.UTF_8),
            File(dir, "champions.json").readText(Charsets.UTF_8)
        )
    }

    private fun id(key: String): String =
        data.champs.values.firstOrNull { it.key == key }?.id ?: error("campeão não encontrado: $key")

    private fun show(title: String, list: List<Suggestion>) {
        println("== $title")
        for (s in list) {
            println(
                "  %-14s nota %2d  WR %.1f  Pick %.1f  Ban %.1f  T%d  | %s".format(
                    s.champ.name, s.score, s.stat.wr, s.stat.pr, s.stat.br, s.stat.level,
                    s.reasons.take(3).joinToString(" • ")
                )
            )
        }
    }

    @Test
    fun todosOsCampeoesTemAtributos() {
        val meta = org.json.JSONObject(File("src/main/assets/champions.json").readText(Charsets.UTF_8))
        val missing = data.champs.values.filter { !meta.has(it.key) }.map { it.key }
        println("Campeões: ${data.champs.size}, dados de ${data.dateLabel()}, sem atributos: $missing")
        assertTrue("Campeões sem atributos: $missing", missing.isEmpty())
    }

    @Test
    fun bansParaAdc() {
        val e = Engine(data)
        val d = Draft().apply { myLane = Lane.ADC }
        val b = e.suggestBans(d)
        show("BANS para ADC (rota)", b.lane)
        show("BANS gerais", b.global)
        assertTrue(b.lane.isNotEmpty())
        assertTrue(b.lane.all { it.lane == Lane.ADC })
    }

    @Test
    fun deduzRotasInimigas() {
        val e = Engine(data)
        val enemies = listOf("Malphite", "LeeSin", "Ahri", "Jinx", "Thresh").map { id(it) }
        val r = e.deduceEnemyLanes(enemies)
        println("== Rotas deduzidas: " + r.map { (k, v) -> e.champ(k)!!.name + "=" + v.short })
        assertEquals(Lane.JUNGLE, r[id("LeeSin")])
        assertEquals(Lane.MID, r[id("Ahri")])
        assertEquals(Lane.ADC, r[id("Jinx")])
        assertEquals(Lane.SUP, r[id("Thresh")])
        assertEquals(Lane.BARON, r[id("Malphite")])

        // campeões flex: Sylas, Darius, Lux, Seraphine, Kaisa
        val r2 = e.deduceEnemyLanes(listOf("Sylas", "Darius", "Lux", "Seraphine", "Kaisa").map { id(it) })
        println("== Rotas deduzidas (flex): " + r2.map { (k, v) -> e.champ(k)!!.name + "=" + v.short })
        assertEquals(5, r2.values.toSet().size)
    }

    @Test
    fun picksAdcComTimeMagico() {
        val e = Engine(data)
        val d = Draft().apply {
            myLane = Lane.ADC
            allies[Lane.MID] = id("Ahri")
            allies[Lane.BARON] = id("Mordekaiser")
            allies[Lane.JUNGLE] = id("Lillia")
            allies[Lane.SUP] = id("Lulu")
        }
        val picks = e.suggestPicks(d)
        show("PICKS ADC, time aliado todo AP", picks)
        val kaisa = picks.indexOfFirst { it.champ.key == "Kaisa" }
        val cait = picks.indexOfFirst { it.champ.key == "Caitlyn" }
        println("posição Caitlyn=$cait Kai'Sa=$kaisa")
        assertTrue("o primeiro pick deve ser dano físico", picks.first().champ.dmg == Dmg.AD)
    }

    @Test
    fun picksAdcContraEngage() {
        val e = Engine(data)
        val d = Draft().apply {
            myLane = Lane.ADC
            allies[Lane.MID] = id("Yasuo")
            allies[Lane.SUP] = id("Nami")
            enemies.addAll(listOf("Malphite", "JarvanIV", "Diana", "Leona").map { id(it) })
        }
        val picks = e.suggestPicks(d)
        show("PICKS ADC contra muito engage", picks)
        val a = e.analyze(d)
        println("== Análise")
        a.ally?.plan?.forEach { println("  ALIADO: $it") }
        a.enemy?.plan?.forEach { println("  INIMIGO: $it") }
        a.compare.forEach { println("  COMPARA: $it") }
        a.tips.forEach { println("  DICA: $it") }
        assertTrue(picks.isNotEmpty())
    }

    @Test
    fun picksSuporteEJungle() {
        val e = Engine(data)
        val d = Draft().apply {
            myLane = Lane.SUP
            allies[Lane.ADC] = id("Jinx")
            allies[Lane.MID] = id("Orianna")
            enemies.addAll(listOf("Zed", "Khazix", "Caitlyn").map { id(it) })
        }
        show("PICKS SUP com Jinx contra assassinos", e.suggestPicks(d))

        val j = Draft().apply {
            myLane = Lane.JUNGLE
            allies[Lane.ADC] = id("Caitlyn")
            allies[Lane.MID] = id("Syndra")
            allies[Lane.SUP] = id("Lux")
            allies[Lane.BARON] = id("Fiora")
            enemies.addAll(listOf("Jhin", "Lux", "Veigar").map { id(it) })
        }
        show("PICKS SELVA sem tanque/engage no time", e.suggestPicks(j))
    }
}
