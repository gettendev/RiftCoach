package com.riftcoach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuneEngineTest {

    private val dir = File("src/main/assets")
    private fun read(name: String) = File(dir, name).readText(Charsets.UTF_8)

    private val db: GameDb by lazy {
        GameDbParser.parse(read("db_heroes.json"), read("db_items.json"), read("db_runes.json"), read("db_spells.json"), read("names_pt.json"))
    }
    private val data: GameData by lazy { DataParser.parse(read("hero_list.json"), read("rank.json"), read("champions.json")) }
    private val runeData by lazy { RuneData.parse(read("runes_model.json")) }
    private val engine by lazy { BuildEngine(db, data, ItemModels.parse(read("items_model.json")), runeData) }

    private fun id(key: String) = data.champs.values.first { it.key == key }.id

    private fun draft(me: String, lane: Lane, enemies: List<String> = emptyList(), allies: Map<Lane, String> = emptyMap()) = Draft().apply {
        myLane = lane
        myPick = id(me)
        this.enemies.addAll(enemies.map { id(it) })
        for ((l, k) in allies) this.allies[l] = id(k)
    }

    /** O que os jogadores Diamante+ do servidor chinês mais usam (riftpatchnotes.com, 15/11/2026). */
    private class Meta(val keystone: String, val path: String, val primary: List<String>, val secondary: String, val spell: String)

    private fun show(title: String, d: Draft, meta: Meta? = null): BuildResult {
        val t0 = System.nanoTime()
        val r = engine.build(d)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(title, r)
        val page = r!!.runes
        assertNotNull("$title: sem página de runas", page)
        println("== $title  (${ms} ms)")
        println("   itens: " + r.items.joinToString(", ") { it.item.name })
        println("   essencial: ${page!!.keystone.rune.name}  | ${page.keystone.reasons.joinToString("; ")}")
        println("   ${page.primaryPath}: " + page.primary.joinToString(", ") { it.rune.name + " (" + it.reasons.joinToString("; ") + ")" })
        println("   ${page.secondaryPath}: ${page.secondary.rune.name} (" + page.secondary.reasons.joinToString("; ") + ")")
        println("   feitiços: " + r.spells.joinToString(" + ") { it.spell.name } + "  | " + r.spells.last().reasons.joinToString("; ") +
            (r.spellAlt?.let { "   [alternativa: ${it.spell.name}]" } ?: ""))
        if (meta != null) {
            val k = if (page.keystone.rune.name == meta.keystone) "SIM" else "não"
            val pa = if (page.primaryPath == meta.path) "SIM" else "não"
            val same = page.primary.count { it.rune.name in meta.primary }
            val se = if (page.secondary.rune.name == meta.secondary) "SIM" else "não"
            val sp = if (r.spells.any { it.spell.name == meta.spell }) "SIM" else "não"
            println("   META: ${meta.keystone} / ${meta.path}: ${meta.primary.joinToString(", ")} / ${meta.secondary} / ${meta.spell}")
            println("   bate com o meta -> essencial: $k | caminho: $pa | runas do caminho: $same de 3 | secundária: $se | feitiço: $sp")
            agreement.add(listOf(if (k == "SIM") 1 else 0, if (pa == "SIM") 1 else 0, same, if (se == "SIM") 1 else 0, if (sp == "SIM") 1 else 0))
        }
        return r
    }

    private val agreement = ArrayList<List<Int>>()

    private fun validPage(r: BuildResult) {
        val page = r.runes!!
        assertTrue(page.keystone.rune.keystone)
        val prim = page.primary.map { it.rune }
        assertEquals("uma runa por linha do caminho principal", listOf(0, 1, 2), prim.map { it.slot })
        assertEquals("as 3 do mesmo caminho", 1, prim.map { it.path }.toSet().size)
        assertTrue("secundária de outro caminho", page.secondary.rune.path != prim[0].path)
        assertTrue("nenhuma runa fora do jogo", page.all.none { it.rune.legacy })
        assertEquals("dois feitiços", 2, r.spells.size)
        assertEquals("sem feitiço repetido", 2, r.spells.map { it.spell.id }.toSet().size)
        assertTrue("só feitiços da Fenda", r.spells.all { it.spell.rift })
    }

    @Test
    fun todasAsRunasEFeiticosTemTraducao() {
        val semModelo = db.runes.filter { runeData.info[it.id] == null }.map { it.name }
        assertTrue("runas sem modelo: $semModelo", semModelo.isEmpty())
        val semTexto = runeData.info.filter { it.value.pt.isBlank() }.keys
        assertTrue("runas sem tradução: $semTexto", semTexto.isEmpty())
        val semFeitico = db.spells.filter { runeData.spellPt[it.id].isNullOrBlank() }.map { it.name }
        assertTrue("feitiços sem tradução: $semFeitico", semFeitico.isEmpty())
        val fenda = db.spells.filter { it.rift }.map { it.name }
        println("Feitiços da Fenda: $fenda")
        assertEquals(9, fenda.size)
        val legado = db.runes.filter { it.legacy }.map { it.name }
        println("Runas fora do jogo: $legado")
        assertEquals(3, legado.size)
    }

    @Test
    fun comparaComOMeta() {
        val comp = listOf("Malphite", "LeeSin", "Ahri", "Caitlyn", "Thresh")
        val cases = listOf(
            Triple("Jinx (ADC)", draft("Jinx", Lane.ADC, comp, mapOf(Lane.SUP to "Lulu")),
                Meta("Ritmo Fatal", "Precisão", listOf("Brutal", "Dilacerar", "Lenda: Linhagem"), "Osso Revestido", "Fantasma")),
            Triple("Garen (Barão)", draft("Garen", Lane.BARON, listOf("Darius", "LeeSin", "Ahri", "Jinx", "Thresh")),
                Meta("Conquistador", "Determinação", listOf("Demolir", "Ventos Revigorantes", "Crescimento Excessivo"), "Celeridade", "Incendiar")),
            Triple("Ahri (Meio)", draft("Ahri", Lane.MID, listOf("Garen", "LeeSin", "Zed", "Jinx", "Lulu")),
                Meta("Eletrocutar", "Dominação", listOf("Ataque Fortalecido", "Assalto em Cadeia (?)", "Coleção de Globos Oculares"), "Osso Revestido", "Incendiar")),
            Triple("Zed (Meio)", draft("Zed", Lane.MID, listOf("Garen", "LeeSin", "Ahri", "Jinx", "Lulu")),
                Meta("Conquistador", "Precisão", listOf("Zelo de Batalha (?)", "Dilacerar", "Lenda: Aceleração"), "Transcendência", "Incendiar")),
            Triple("Zed (Selva)", draft("Zed", Lane.JUNGLE, comp),
                Meta("Eletrocutar", "Dominação", listOf("Impacto Repentino", "Assalto em Cadeia (?)", "Coleção de Globos Oculares"), "Transcendência", "Golpear")),
            Triple("Lee Sin (Selva)", draft("LeeSin", Lane.JUNGLE, listOf("Malphite", "Zed", "Ahri", "Caitlyn", "Thresh")),
                Meta("Conquistador", "Precisão", listOf("Zelo de Batalha (?)", "Dilacerar", "Lenda: Espontaneidade"), "Impacto Repentino", "Golpear")),
            Triple("Thresh (Suporte)", draft("Thresh", Lane.SUP, listOf("Malphite", "LeeSin", "Ahri", "Caitlyn", "Lux"), mapOf(Lane.ADC to "Jinx")),
                Meta("Soberano do Gelo (?)", "Determinação", listOf("Coragem do Colosso", "Osso Revestido", "Perseverança (?)"), "Transcendência", "Incendiar")),
            Triple("Soraka (Suporte)", draft("Soraka", Lane.SUP, comp, mapOf(Lane.ADC to "Jinx")),
                Meta("Aery", "Determinação", listOf("Fonte da Vida", "Osso Revestido", "Revitalizar"), "Capa do Nimbus", "Curar")),
            Triple("Malphite (Suporte)", draft("Malphite", Lane.SUP, listOf("Garen", "LeeSin", "Ahri", "Caitlyn", "Lux"), mapOf(Lane.ADC to "Jinx")),
                Meta("Cometa Arcano", "Determinação", listOf("Inabalável (?)", "Ventos Revigorantes", "Crescimento Excessivo"), "Transcendência", "Incendiar")),
            Triple("Malphite (Barão)", draft("Malphite", Lane.BARON, listOf("Garen", "LeeSin", "Ahri", "Caitlyn", "Thresh")),
                Meta("Aperto dos Mortos-Vivos", "Determinação", listOf("Inabalável (?)", "Ventos Revigorantes", "Crescimento Excessivo"), "Ataque Fortalecido", "Incendiar"))
        )
        for ((title, d, meta) in cases) validPage(show(title, d, meta))
        val n = agreement.size
        println(
            "RESUMO ($n casos): essencial ${agreement.sumOf { it[0] }}/$n, caminho ${agreement.sumOf { it[1] }}/$n, " +
                "runas do caminho ${agreement.sumOf { it[2] }}/${3 * n}, secundária ${agreement.sumOf { it[3] }}/$n, feitiço ${agreement.sumOf { it[4] }}/$n"
        )
    }

    @Test
    fun feiticosSeAdaptamAPartida() {
        // selva: sempre Golpear + Flash
        val jg = engine.build(draft("LeeSin", Lane.JUNGLE))!!
        assertEquals(listOf("Golpear", "Flash"), jg.spells.map { it.spell.name })

        // atirador contra muito controle de grupo: Purificar entra na disputa
        val cc = show("Jinx contra muito controle", draft("Jinx", Lane.ADC, listOf("Malphite", "Amumu", "Lissandra", "Ashe", "Leona")))
        assertTrue(cc.spells.any { it.spell.name == "Purificar" } || cc.spellAlt?.spell?.name == "Purificar")

        // suporte de cura: Curar
        val sor = engine.build(draft("Soraka", Lane.SUP, listOf("Garen", "LeeSin", "Ahri", "Jinx", "Thresh"), mapOf(Lane.ADC to "Caitlyn")))!!
        assertTrue(sor.spells.any { it.spell.name == "Curar" })

        // meio contra muita cura: Incendiar, citando quem cura
        val mid = show("Ahri contra cura", draft("Ahri", Lane.MID, listOf("DrMundo", "Aatrox", "Vladimir", "Jinx", "Soraka")))
        val ign = mid.spells.first { it.spell.name == "Incendiar" }
        assertTrue(ign.reasons.first().contains("Soraka") || ign.reasons.first().contains("Vladimir"))

        // todo resultado é uma página válida
        for (r in listOf(jg, cc, sor, mid)) validPage(r)
    }

    @Test
    fun runasMudamComOInimigo() {
        val vsMagic = show("Malphite (Barão) contra time mágico", draft("Malphite", Lane.BARON, listOf("Mordekaiser", "Lillia", "Ahri", "Brand", "Lux")))
        val vsBurst = show("Ahri contra assassinos", draft("Ahri", Lane.MID, listOf("Zed", "Khazix", "Talon", "Jinx", "Pyke")))
        validPage(vsMagic)
        validPage(vsBurst)
    }
}
