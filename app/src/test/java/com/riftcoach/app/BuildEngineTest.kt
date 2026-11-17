package com.riftcoach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildEngineTest {

    private val dir = File("src/main/assets")
    private fun read(name: String) = File(dir, name).readText(Charsets.UTF_8)

    private val db: GameDb by lazy {
        GameDbParser.parse(read("db_heroes.json"), read("db_items.json"), read("db_runes.json"), read("db_spells.json"), read("names_pt.json"))
    }
    private val data: GameData by lazy { DataParser.parse(read("hero_list.json"), read("rank.json"), read("champions.json")) }
    private val models by lazy { ItemModels.parse(read("items_model.json")) }
    private val engine by lazy { BuildEngine(db, data, models) }

    private fun id(key: String) = data.champs.values.first { it.key == key }.id

    private fun draft(me: String, lane: Lane, enemies: List<String> = emptyList(), allies: Map<Lane, String> = emptyMap()) = Draft().apply {
        myLane = lane
        myPick = id(me)
        this.enemies.addAll(enemies.map { id(it) })
        for ((l, k) in allies) this.allies[l] = id(k)
    }

    private fun show(title: String, d: Draft): BuildResult {
        val r = engine.build(d)
        assertNotNull(title, r)
        println("== $title")
        r!!.boots?.let { println("   botas: ${it.item.name}" + (r.bootsUpgrade?.let { u -> " -> ${u.name}" } ?: "") + "  | " + it.reasons.joinToString("; ")) }
        r.items.forEachIndexed { i, p -> println("   ${i + 1}. ${p.item.name}  | " + p.reasons.joinToString("; ")) }
        r.notes.forEach { println("   nota: $it") }
        return r
    }

    private fun names(r: BuildResult) = r.items.map { it.item.name }

    @Test
    fun todoItemCompletoTemPassivaModelada() {
        val semModelo = db.items.filter { it.tier == 3 && it.price >= 2000 && models[it.id] == null }.map { it.name }
        println("Itens completos sem modelo de passiva: $semModelo")
        assertTrue("faltam modelos: $semModelo", semModelo.isEmpty())
        val semTraducao = models.filter { it.value.pt.isBlank() }.keys
        assertTrue("sem tradução: $semTraducao", semTraducao.isEmpty())
    }

    @Test
    fun formulasLidasDosCampeoesDeTeste() {
        for (key in listOf("Zed", "MasterYi", "Yasuo", "Garen", "Darius", "LeeSin", "Soraka", "Kaisa", "Malphite", "Leona")) {
            val h = db.heroes[id(key)]!!
            println("-- $key (${h.role})")
            for (s in h.skills) println("     ${s.slotLabel}: " + s.effects.joinToString(" | ") { it.describe() } + "   cd=" + (s.table.firstOrNull { it.first == "Recarga (s)" }?.second ?: "?"))
        }
    }

    @Test
    fun buildsDeVariosCampeoes() {
        val comp = listOf("Malphite", "LeeSin", "Ahri", "Caitlyn", "Thresh")
        show("Jinx (ADC) vs comp equilibrada", draft("Jinx", Lane.ADC, comp))
        show("Ahri (Meio) vs comp equilibrada", draft("Ahri", Lane.MID, listOf("Garen", "LeeSin", "Zed", "Jinx", "Lulu")))
        show("Garen (Barão) vs time físico", draft("Garen", Lane.BARON, listOf("Darius", "LeeSin", "Zed", "Jinx", "Thresh")))
        show("Malphite (Barão) vs time mágico", draft("Malphite", Lane.BARON, listOf("Mordekaiser", "Lillia", "Ahri", "Kaisa", "Lux")))
        show("Zed (Meio) vs frágeis", draft("Zed", Lane.MID, listOf("Jayce", "Graves", "Lux", "Jinx", "Soraka")))
        show("Master Yi (Selva)", draft("MasterYi", Lane.JUNGLE, comp))
        show("Soraka (Suporte)", draft("Soraka", Lane.SUP, comp, mapOf(Lane.ADC to "Jinx", Lane.MID to "Orianna")))
        show("Leona (Suporte)", draft("Leona", Lane.SUP, comp, mapOf(Lane.ADC to "Jinx")))
        show("Lux (Suporte)", draft("Lux", Lane.SUP, comp))
        show("Kai'Sa (ADC)", draft("Kaisa", Lane.ADC, comp))
        show("Darius sem inimigos conhecidos", draft("Darius", Lane.BARON))
        show("Vayne (ADC) vs três tanques", draft("Vayne", Lane.ADC, listOf("Malphite", "Maokai", "Galio", "Jinx", "Leona")))
        show("Yasuo (Meio)", draft("Yasuo", Lane.MID, comp))
        show("Lee Sin (Selva)", draft("LeeSin", Lane.JUNGLE, comp))
    }

    @Test
    fun buildSeAdaptaAoTimeInimigo() {
        // mesmo campeão, dois times inimigos: a build tem que mudar
        val vsCura = show("Darius vs cura (Soraka, Dr. Mundo, Aatrox)", draft("Darius", Lane.BARON, listOf("DrMundo", "Aatrox", "Vladimir", "Jinx", "Soraka")))
        val semCura = show("Darius vs sem cura", draft("Darius", Lane.BARON, listOf("Malphite", "LeeSin", "Zed", "Caitlyn", "Thresh")))
        val antiCura = setOf("Espada-Serra Quimiopunk", "Lembrete Mortal", "Armadura de Espinhos", "Morellonomicon")
        assertTrue("contra muita cura deve ter anti-cura: ${names(vsCura)}", names(vsCura).any { it in antiCura })
        assertTrue(names(vsCura) != names(semCura))

        val vsFisico = show("Malphite vs time todo físico", draft("Malphite", Lane.BARON, listOf("Darius", "LeeSin", "Zed", "Jinx", "Pyke")))
        val vsMagico = show("Malphite vs time todo mágico", draft("Malphite", Lane.BARON, listOf("Mordekaiser", "Lillia", "Ahri", "Brand", "Lux")))
        fun armor(r: BuildResult) = r.items.sumOf { it.item.values["armor"] ?: 0.0 } + (r.boots?.item?.values?.get("armor") ?: 0.0)
        fun mr(r: BuildResult) = r.items.sumOf { it.item.values["mr"] ?: 0.0 } + (r.boots?.item?.values?.get("mr") ?: 0.0)
        println("armadura/RM vs físico: ${armor(vsFisico)}/${mr(vsFisico)}   vs mágico: ${armor(vsMagico)}/${mr(vsMagico)}")
        assertTrue("contra time físico, mais armadura", armor(vsFisico) > armor(vsMagico))
        assertTrue("contra time mágico, mais resistência mágica", mr(vsMagico) > mr(vsFisico))
    }

    @Test
    fun regrasBasicas() {
        val jinx = show("Jinx regras", draft("Jinx", Lane.ADC, listOf("Malphite", "LeeSin", "Ahri", "Caitlyn", "Thresh")))
        assertEquals(5, jinx.items.size)
        assertEquals("sem itens repetidos", 5, jinx.items.map { it.item.id }.toSet().size)
        assertTrue("atiradora não compra item só de AP", jinx.items.none { it.item.values.containsKey("ap") && !it.item.values.containsKey("ad") && !it.item.values.containsKey("crit%") })
        val garen = show("Garen regras", draft("Garen", Lane.BARON))
        assertTrue("Garen não usa mana: nada de item de mana", garen.items.none { it.item.values.containsKey("mana") })
        val ahri = show("Ahri regras", draft("Ahri", Lane.MID))
        assertTrue("maga não compra item de AD", ahri.items.none { it.item.values.containsKey("ad") && !it.item.values.containsKey("ap") })
    }
}
