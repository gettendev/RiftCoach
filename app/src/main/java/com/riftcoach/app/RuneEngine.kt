package com.riftcoach.app

import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Runas e feitiços de invocador.
 *
 * Runas: cada página possível é avaliada com as mesmas contas de luta da build de itens
 * (dano causado, dano aguentado, utilidade), em três momentos da partida: nível 6 com o
 * 1º item, nível 11 com 3 itens e nível 15 com a build completa.
 *
 * Feitiços: o Flash é quase obrigatório e o Golpear é obrigatório na selva. O segundo feitiço
 * parte do padrão do meta para a rota e o tipo de campeão, e é ajustado pela partida
 * (cura inimiga, assassinos, controle de grupo, rota lateral).
 */

class RuneInfo(val pt: String, val needs: String?, val styles: Set<String>, val legacy: Boolean)

class RuneData(val models: Map<String, ItemModel>, val info: Map<String, RuneInfo>, val spellPt: Map<String, String>) {
    companion object {
        /** chave do modelo de uma runa no mesmo mapa das passivas de itens */
        fun key(runeId: String) = "r$runeId"

        fun parse(json: String): RuneData {
            val root = JSONObject(json)
            val runes = root.getJSONObject("runes")
            val models = HashMap<String, ItemModel>()
            val info = HashMap<String, RuneInfo>()
            for (id in runes.keys()) {
                val o = runes.getJSONObject(id)
                val fx = ArrayList<Fx>()
                o.optJSONArray("fx")?.let { a ->
                    for (i in 0 until a.length()) {
                        val f = a.getJSONObject(i)
                        fx.add(Fx(f.optString("t"), f))
                    }
                }
                val pt = o.optString("pt")
                models[key(id)] = ItemModel(pt, fx, null, null, emptySet(), false, false, null, null)
                info[id] = RuneInfo(
                    pt, o.optString("needs").ifEmpty { null },
                    o.optString("style").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    o.optBoolean("legacy")
                )
            }
            val spells = HashMap<String, String>()
            root.optJSONObject("spells")?.let { s -> for (id in s.keys()) spells[id] = s.optString(id) }
            return RuneData(models, info, spells)
        }
    }
}

class RunePick(val rune: Rune, val reasons: List<String>)

class RunePage(
    val keystone: RunePick,
    /** nome do caminho em português */
    val primaryPath: String,
    val primary: List<RunePick>,
    val secondaryPath: String,
    val secondary: RunePick
) {
    val all: List<RunePick> get() = listOf(keystone) + primary + secondary
}

class SpellPick(val spell: SummonerSpell, val reasons: List<String>)

class RuneEngine(private val db: GameDb, private val rd: RuneData) {

    /** cada runa vira um "item" sem atributos, só com o efeito, para entrar nas mesmas contas */
    private val pseudo: Map<String, Item> = db.runes.associate { r ->
        r.id to Item(RuneData.key(r.id), r.name, r.name, 0, 0, "Runa", emptyList(), emptyMap(), emptyList(), r.textCn, r.icon)
    }

    private fun usable(r: Rune, p: BuildEngine.Profile): Boolean {
        val info = rd.info[r.id] ?: return false
        if (info.legacy || r.legacy) return false
        return when (info.needs) {
            "cc" -> p.champ.has("cc")
            "mana" -> p.usesMana
            else -> true
        }
    }

    /** Runa essencial fora do estilo do campeão precisa ganhar nas contas por uma margem maior. */
    private fun keystoneFit(p: BuildEngine.Profile, r: Rune): Double {
        val styles = rd.info[r.id]?.styles ?: return 1.0
        if (styles.isEmpty()) return 1.0
        val ap = p.champ.dmg == Dmg.AP
        val want: Map<String, Double> = when (p.arch) {
            Arch.MARKSMAN -> mapOf("marksman" to 1.0, "fighter" to 0.93, "burst" to 0.9, "mage" to 0.88)
            Arch.ASSASSIN -> if (ap) mapOf("burst" to 1.0, "mage" to 1.0, "fighter" to 0.93) else mapOf("burst" to 1.0, "fighter" to 0.97)
            Arch.FIGHTER -> if (ap) mapOf("fighter" to 1.0, "mage" to 0.97, "tank" to 0.95, "burst" to 0.93)
            else mapOf("fighter" to 1.0, "tank" to 0.95, "burst" to 0.93)
            Arch.TANK -> mapOf("tank" to 1.0, "fighter" to 0.95, "support" to 0.9, "mage" to 0.88)
            Arch.MAGE -> mapOf("mage" to 1.0, "burst" to 1.0, "support" to 0.9)
            Arch.ENCHANTER -> mapOf("support" to 1.0, "mage" to 0.95)
            Arch.TANK_SUPPORT -> mapOf("support" to 1.0, "tank" to 1.0, "mage" to 0.93)
        }
        return styles.maxOf { want[it] ?: OFF_STYLE }
    }

    private class Snap(val level: Int, val items: List<Item>, val ctx: BuildEngine.Ctx, val w: Double)

    // ------------------------------------------------------------------------- runas

    internal fun runes(be: BuildEngine, p: BuildEngine.Profile, d: Draft, items: List<Item>, boots: Item?): RunePage? {
        val start = listOfNotNull(boots)
        val snaps = listOf(
            Snap(6, start + items.take(1), be.context(p, d, 6, 1), 0.4),
            Snap(11, start + items.take(3), be.context(p, d, 11, 3), 0.4),
            Snap(15, start + items, be.context(p, d, 15, 5), 0.2)
        )

        val cache = HashMap<List<String>, Double>()
        fun value(page: List<Rune>): Double {
            val key = page.map { it.id }.sorted()
            cache[key]?.let { return it }
            val extra = page.mapNotNull { pseudo[it.id] }
            var v = 0.0
            for (s in snaps) v += s.w * ln(max(1e-9, be.score(p, s.level, s.items + extra, s.ctx).total))
            page.firstOrNull { it.keystone }?.let { v += ln(keystoneFit(p, it)) }
            cache[key] = v
            return v
        }
        fun best(options: List<Rune>, fixed: List<Rune>): Rune? = options.maxByOrNull { value(fixed + it) }

        val pool = db.runes.filter { usable(it, p) }
        val keystones = pool.filter { it.keystone }
        val minors = pool.filter { !it.keystone && it.slot in 0..2 }
        val paths = minors.groupBy { it.path }.filter { (_, rs) -> (0..2).all { i -> rs.any { it.slot == i } } }
        if (keystones.isEmpty() || paths.isEmpty()) return null
        fun slot(path: String, i: Int) = paths[path].orEmpty().filter { it.slot == i }

        fun primaryFor(path: String, key: Rune): MutableList<Rune> =
            (0..2).map { i -> best(slot(path, i), listOf(key))!! }.toMutableList()
        fun secondaryFor(path: String, fixed: List<Rune>): Rune? = best(minors.filter { it.path != path }, fixed)

        // 1) runa essencial sozinha, depois o melhor caminho principal + runa secundária para ela
        var key = best(keystones, emptyList())!!
        var path = ""
        var prim = mutableListOf<Rune>()
        var sec: Rune? = null
        fun choosePath() {
            var bestV = Double.NEGATIVE_INFINITY
            for (pp in paths.keys) {
                val pr = primaryFor(pp, key)
                val sc = secondaryFor(pp, listOf(key) + pr) ?: continue
                val v = value(listOf(key) + pr + sc)
                if (v > bestV) {
                    bestV = v
                    path = pp
                    prim = pr
                    sec = sc
                }
            }
        }
        choosePath()
        if (sec == null) return null

        // 2) refino: troca uma peça por vez, mantendo as outras, enquanto a página melhorar
        repeat(2) {
            key = best(keystones, prim + sec!!)!!
            choosePath()
            for (i in 0..2) prim[i] = best(slot(path, i), listOf(key) + prim.filterIndexed { j, _ -> j != i } + sec!!)!!
            sec = secondaryFor(path, listOf(key) + prim)
        }
        val page = listOf(key) + prim + sec!!

        // motivo de cada runa: o que ela soma na luta do meio da partida
        val mid = snaps[1]
        val full = be.score(p, mid.level, mid.items + page.mapNotNull { pseudo[it.id] }, mid.ctx)
        fun pick(r: Rune): RunePick {
            val without = be.score(p, mid.level, mid.items + (page - r).mapNotNull { pseudo[it.id] }, mid.ctx)
            return RunePick(r, reasons(r, p, full, without, mid.ctx, be))
        }
        return RunePage(pick(key), prim[0].tree, prim.map { pick(it) }, sec!!.tree, pick(sec!!))
    }

    private fun reasons(r: Rune, p: BuildEngine.Profile, with: BuildEngine.Score, without: BuildEngine.Score, ctx: BuildEngine.Ctx, be: BuildEngine): List<String> {
        val out = ArrayList<String>()
        val dOff = (with.off / max(1e-9, without.off) - 1) * 100
        val dDef = (with.def / max(1e-9, without.def) - 1) * 100
        val support = p.arch == Arch.ENCHANTER
        if (dOff >= 0.5) out.add("+${pct(dOff)}% de " + if (support) "cura e escudo" else "dano")
        if (dDef >= 0.5) out.add("+${pct(dDef)}% de resistência")
        val fx = rd.models[RuneData.key(r.id)]?.fx ?: emptyList()
        for (f in fx) {
            if (f.t == "ally") {
                out.add("protege os aliados")
                break
            }
        }
        val util = fx.firstOrNull { it.t == "util" }?.str("k")
        if (util != null && be.utilValue(util, p, ctx) >= 0.8) UTIL_TEXT[util]?.let { out.add(it) }
        if (out.isEmpty()) out.add("melhor opção desta linha para a sua build")
        return out.take(2)
    }

    private fun pct(v: Double): String = if (v >= 10) v.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", v).replace('.', ',').removeSuffix(",0")

    // ---------------------------------------------------------------------- feitiços

    internal fun spells(p: BuildEngine.Profile, d: Draft, ctx: BuildEngine.Ctx): Pair<List<SpellPick>, SpellPick?> {
        val byId = db.spells.associateBy { it.id }
        val flash = byId[FLASH] ?: return emptyList<SpellPick>() to null
        val flashPick = SpellPick(flash, listOf("fuga e iniciação: quase todo campeão leva"))

        if (p.lane == Lane.JUNGLE) {
            val smite = byId[SMITE] ?: return listOf(flashPick) to null
            return listOf(SpellPick(smite, listOf("obrigatório na selva: limpa os monstros e garante dragão e barão")), flashPick) to null
        }

        val enemies = ctx.enemies
        fun names(l: List<Champion>) = l.take(3).joinToString(", ") { it.name }
        val healers = enemies.filter { it.has("cura") }
        val assassins = enemies.filter { it.has("assassino") }
        val threats = enemies.filter { it.hasAny("assassino", "dive") || it.key in HYPER_AUTO }
        val hardCc = enemies.filter { it.has("cc") }
        val immobile = enemies.filter { it.has("imovel") }
        val marksman = p.arch == Arch.MARKSMAN || p.lane == Lane.ADC
        val adc = if (p.lane == Lane.SUP) d.allies[Lane.ADC]?.let { id -> ctx.allies.firstOrNull { it.id == id } } else null

        // padrão do meta para o tipo de campeão e a rota (partidas Diamante+ do servidor chinês)
        val prior: Map<String, Double> = when {
            marksman -> mapOf(GHOST to 1.0, HEAL to 0.75, BARRIER to 0.35, CLEANSE to 0.35, EXHAUST to 0.2, IGNITE to 0.25)
            p.arch == Arch.ENCHANTER -> mapOf(HEAL to 1.0, EXHAUST to 0.5, IGNITE to 0.4, BARRIER to 0.2)
            p.lane == Lane.SUP -> mapOf(IGNITE to 1.0, EXHAUST to 0.55, HEAL to 0.4, TELEPORT to 0.05)
            p.lane == Lane.MID -> mapOf(IGNITE to 1.0, BARRIER to 0.35, CLEANSE to 0.3, EXHAUST to 0.25, TELEPORT to 0.2, GHOST to 0.2)
            p.lane == Lane.BARON && (p.arch == Arch.TANK || p.champ.has("tank")) ->
                mapOf(IGNITE to 1.0, TELEPORT to 0.85, GHOST to 0.25, EXHAUST to 0.2)
            else -> mapOf(IGNITE to 1.0, TELEPORT to 0.5, GHOST to 0.35, EXHAUST to 0.25, BARRIER to 0.2)
        }

        val value = HashMap<String, Double>()
        val why = HashMap<String, MutableList<String>>()
        for ((id, base) in prior) {
            var v = base
            val r = ArrayList<String>()
            when (id) {
                IGNITE -> {
                    if (healers.isNotEmpty()) {
                        v *= 1 + 0.15 * healers.size
                        r.add("corta a cura de ${names(healers)}")
                    }
                    if (p.champ.has("early")) v *= 1.1
                    r.add(if (p.lane == Lane.SUP) "dano verdadeiro para garantir abates na rota do Dragão" else "dano verdadeiro para garantir abates na rota")
                }
                HEAL -> {
                    if (adc != null) {
                        if (adc.has("hyper")) v *= 1.2
                        r.add("cura você e ${adc.name}, e dá velocidade para fugir")
                    } else r.add("cura você e o aliado mais ferido, e dá velocidade para fugir")
                }
                BARRIER -> {
                    if (assassins.isNotEmpty()) {
                        v *= 1 + 0.3 * assassins.size
                        r.add("escudo contra o dano explosivo de ${names(assassins)}")
                    } else r.add("escudo para sobreviver a uma investida")
                }
                EXHAUST -> {
                    if (threats.size >= 2) {
                        v *= 1 + 0.3 * (threats.size - 1)
                    }
                    r.add(if (threats.isNotEmpty()) "corta 40% do dano de ${names(threats)}" else "corta 40% do dano do carry inimigo")
                }
                CLEANSE -> {
                    if (hardCc.size >= 3) v *= 1 + 0.5 * (hardCc.size - 2)
                    r.add(if (hardCc.isNotEmpty()) "remove o controle de ${names(hardCc)}" else "remove controles de grupo")
                }
                GHOST -> {
                    if (immobile.size >= 3) v *= 1.1
                    r.add(if (marksman) "velocidade para perseguir e fugir; é o mais usado pelos atiradores no meta atual" else "alcança os alvos mesmo sem habilidade de avanço")
                }
                TELEPORT -> {
                    if (p.champ.has("split")) v *= 1.25
                    if (p.arch == Arch.TANK) v *= 1.1
                    r.add("volta rápido para a rota e ajuda o time em lutas longe")
                }
            }
            value[id] = v
            why[id] = r
        }
        val ranked = value.entries.filter { byId[it.key]?.rift == true }.sortedByDescending { it.value }
        val first = ranked.firstOrNull() ?: return listOf(flashPick) to null
        val second = ranked.getOrNull(1)
        val pick = SpellPick(byId[first.key]!!, why[first.key].orEmpty())
        // a alternativa só aparece se estiver perto da escolha principal
        val alt = second?.takeIf { it.value >= first.value * 0.7 }?.let { SpellPick(byId[it.key]!!, why[it.key].orEmpty()) }
        return listOf(flashPick, pick) to alt
    }

    companion object {
        private const val OFF_STYLE = 0.85

        const val FLASH = "81090101"
        const val IGNITE = "81100101"
        const val HEAL = "81030101"
        const val BARRIER = "81050101"
        const val EXHAUST = "81060101"
        const val CLEANSE = "81070101"
        const val GHOST = "81010101"
        const val SMITE = "81110101"
        const val TELEPORT = "81210101"

        /** carregadores que ganham lutas atacando sem parar (Exaustão rende muito contra eles) */
        private val HYPER_AUTO = setOf("MasterYi", "Tryndamere", "Yasuo", "Yone", "Jax", "Fiora", "Irelia", "Viego", "Kayle")

        private val UTIL_TEXT = mapOf(
            "tower" to "derruba torres mais rápido",
            "roam" to "anda mais rápido entre as rotas",
            "ms" to "mais velocidade de movimento",
            "summms" to "corrida depois do Flash e dos outros feitiços",
            "mana" to "mais mana para as trocas",
            "plants" to "aproveita melhor as plantas",
            "seed" to "planta frutas onde precisar",
            "flash" to "um segundo avanço com o Flash em recarga",
            "lane" to "recupera vida nas trocas da rota",
            "gold" to "ouro extra",
            "kite" to "velocidade para entrar e sair da luta",
            "teamslow" to "zona de lentidão para o time ao prender",
            "triumph" to "cura e velocidade depois de abates",
            "fleetms" to "velocidade de movimento nas trocas",
            "vision" to "visão e dano ao limpar sentinelas"
        )
    }
}
