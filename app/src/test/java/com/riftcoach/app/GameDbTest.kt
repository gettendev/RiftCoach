package com.riftcoach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameDbTest {

    private val dir = File("src/main/assets")
    private fun read(name: String) = File(dir, name).readText(Charsets.UTF_8)

    private val db: GameDb by lazy {
        GameDbParser.parse(read("db_heroes.json"), read("db_items.json"), read("db_runes.json"), read("db_spells.json"), read("names_pt.json"))
    }
    private val data: GameData by lazy { DataParser.parse(read("hero_list.json"), read("rank.json"), read("champions.json")) }

    private fun hero(key: String): HeroDetail = db.heroes[data.champs.values.first { it.key == key }.id]!!

    private fun hasChinese(s: String) = s.any { it.code in 0x4E00..0x9FFF }

    @Test
    fun tamanhoDaBase() {
        println("Base versão ${db.version}: ${db.heroes.size} campeões, ${db.items.size} itens, ${db.runes.size} runas, ${db.spells.size} feitiços")
        assertEquals(144, db.heroes.size)
        assertEquals(186, db.items.size)
        assertEquals(55, db.runes.size)
        assertEquals(14, db.spells.size)
        assertTrue(db.heroes.values.all { it.skills.size == 5 })
    }

    @Test
    fun numerosDoGaren() {
        val g = hero("Garen")
        assertEquals(690.0, g.hp, 0.01)
        assertEquals(64.0, g.ad, 0.01)
        assertEquals(44.0, g.armor, 0.01)
        assertEquals(350.0, g.moveSpeed, 0.01)
        // nível 15: 690 + 128 x (soma dos fatores de crescimento = 14) = 2482
        assertEquals(2482.0, g.at(g.hp, g.hpPer, 15), 0.5)
        val q = g.skills[1]
        println("Garen ${q.slotLabel}: " + q.effects.joinToString(" | ") { it.describe() } + "  tabela=" + q.table)
        assertEquals("Dano físico: 40/80/120/160 + 40% AD", q.effects[0].describe())
        assertTrue(q.table.any { it.first == "Recarga (s)" && it.second == "9/8/8/7" })
    }

    @Test
    fun numerosDeItens() {
        val ie = db.item("2245")!!
        println("${ie.name}: ${ie.stats} preço ${ie.price} de ${ie.from.map { db.item(it)?.name }}")
        assertEquals("Gume do Infinito", ie.name)
        assertEquals(3400, ie.price)
        assertEquals(75.0, ie.values["ad"]!!, 0.01)
        assertEquals(25.0, ie.values["crit%"]!!, 0.01)
        val boots = db.item("2403")!!
        assertEquals(35.0, boots.values["as%"]!!, 0.01)
        assertEquals(45.0, boots.values["ms"]!!, 0.01)
        val thorn = db.item("2348")!!
        assertEquals(75.0, thorn.values["armor"]!!, 0.01)
        assertEquals(200.0, thorn.values["hp"]!!, 0.01)
        // atributos que só existem no texto da descrição
        val warmog = db.item("2344")!!
        println("${warmog.name}: ${warmog.stats}")
        assertEquals(700.0, warmog.values["hp"]!!, 0.01)
        assertEquals(20.0, warmog.values["haste"]!!, 0.01)
        assertEquals(100.0, warmog.values["hpRegen%"]!!, 0.01)
        val lw = db.item("2225")!!
        assertEquals(15.0, lw.values["armorPen%"]!!, 0.01)
        val edge = db.item("2161")!!
        assertEquals(12.0, edge.values["armorPen"]!!, 0.01)
        // nenhum atributo ficou sem rótulo em português
        val semRotulo = db.items.flatMap { it.stats }.map { it.first }.filter { hasChinese(it) }.distinct()
        assertTrue("atributos sem tradução: $semRotulo", semRotulo.isEmpty())
        // a passiva não pode ter engolido as linhas de atributo
        assertTrue(ie.textCn.isNotEmpty() && !ie.textCn.startsWith("攻击力"))
    }

    @Test
    fun todosOsNomesEstaoEmPortugues() {
        val semNome = db.items.filter { hasChinese(it.name) }.map { it.id } +
            db.runes.filter { hasChinese(it.name) }.map { it.id } + db.spells.filter { hasChinese(it.name) }.map { it.id }
        assertTrue("sem nome em português: $semNome", semNome.isEmpty())
        val duvida = db.items.filter { it.nameUnsure }.map { it.name } + db.runes.filter { it.name.endsWith("(?)") }.map { it.name }
        println("Nomes a conferir com o jogo (${duvida.size}): $duvida")
    }

    @Test
    fun coberturaDasFormulas() {
        var skills = 0
        var comEfeito = 0
        var efeitos = 0
        var brutos = 0
        var deduzidos = 0
        val semRotulo = HashMap<String, Int>()
        for (h in db.heroes.values) for (s in h.skills) {
            skills++
            if (s.effects.isNotEmpty()) comEfeito++
            for (e in s.effects) {
                efeitos++
                if (e.raw != null) brutos++
                if (e.ratios.any { it.guessed }) deduzidos++
            }
            for ((label, _) in s.table) if (hasChinese(label)) semRotulo[label] = (semRotulo[label] ?: 0) + 1
        }
        println("Habilidades: $skills; com números extraídos: $comEfeito; efeitos: $efeitos; não interpretados: $brutos; atributo deduzido: $deduzidos")
        println("Rótulos de tabela ainda em chinês: ${semRotulo.size} tipos, ${semRotulo.values.sum()} ocorrências")
        for (key in listOf("Ahri", "Jinx", "Vayne", "Mordekaiser", "Lux")) {
            val h = hero(key)
            println("== $key  vida ${h.hp}+${h.hpPer}/nv  AD ${h.ad}+${h.adPer}  armadura ${h.armor}+${h.armorPer}  RM ${h.mr}+${h.mrPer}  mov ${h.moveSpeed}")
            for (s in h.skills) println("   ${s.slotLabel}: " + s.effects.joinToString(" | ") { it.describe() })
        }
        assertTrue(comEfeito > skills / 2)
    }
}
