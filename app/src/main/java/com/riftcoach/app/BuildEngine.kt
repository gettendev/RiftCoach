package com.riftcoach.app

import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Motor de cálculo de build.
 *
 * Para cada item candidato ele faz as contas de uma luta: quanto dano o seu campeão causa nos
 * inimigos desta partida (habilidades + ataques + passivas de itens, contra a armadura e a
 * resistência mágica deles) e quanto dano ele aguenta do tipo de dano que eles causam.
 * A build é montada item por item, sempre pegando o que mais melhora a conta naquele momento.
 */

/** Uma passiva de item já transformada em conta. */
class Fx(val t: String, private val o: JSONObject) {
    fun str(key: String): String = o.optString(key)

    /** Número fixo, ou [mín, máx] crescendo do nível 1 ao 15 ("pow" deixa o começo mais lento). */
    fun num(key: String, level: Int = 15): Double {
        val a = o.optJSONArray(key)
        if (a != null && a.length() == 2) {
            val lo = a.optDouble(0)
            val hi = a.optDouble(1)
            var x = (level.coerceIn(1, 15) - 1) / 14.0
            if (o.has("pow")) x = x.pow(o.optDouble("pow", 1.0))
            return lo + (hi - lo) * x
        }
        return o.optDouble(key, 0.0)
    }

    fun has(key: String) = o.has(key)

    /** O efeito já existe neste nível? ("minlv": só a partir de um nível) */
    fun activeAt(level: Int) = !o.has("minlv") || level >= o.optInt("minlv")
}

class ItemModel(
    val pt: String,
    val fx: List<Fx>,
    val group: String?,
    val only: String?,
    val tags: Set<String>,
    val hide: Boolean,
    val boot: Boolean,
    val up: String?,
    val src: String?
)

object ItemModels {
    fun parse(json: String): Map<String, ItemModel> {
        val root = JSONObject(json)
        val out = HashMap<String, ItemModel>()
        for (id in root.keys()) {
            if (id.startsWith("_")) continue
            val o = root.optJSONObject(id) ?: continue
            val fx = ArrayList<Fx>()
            o.optJSONArray("fx")?.let { a ->
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i)
                    fx.add(Fx(f.optString("t"), f))
                }
            }
            out[id] = ItemModel(
                o.optString("pt"), fx,
                o.optString("g").ifEmpty { null }, o.optString("only").ifEmpty { null },
                o.optString("tags").split(',').filter { it.isNotBlank() }.toSet(),
                o.optBoolean("hide"), o.optBoolean("boot"), o.optString("up").ifEmpty { null }, o.optString("src").ifEmpty { null }
            )
        }
        return out
    }
}

enum class Arch { MARKSMAN, MAGE, ASSASSIN, FIGHTER, TANK, ENCHANTER, TANK_SUPPORT }

class BuildPick(val item: Item, val reasons: List<String>)

class BuildResult(
    val boots: BuildPick?,
    val bootsUpgrade: Item?,
    val items: List<BuildPick>,
    val notes: List<String>,
    val runes: RunePage? = null,
    val spells: List<SpellPick> = emptyList(),
    /** segunda opção de feitiço, quando a diferença é pequena */
    val spellAlt: SpellPick? = null
)

class BuildEngine(
    private val db: GameDb,
    private val data: GameData,
    itemModels: Map<String, ItemModel>,
    runeData: RuneData? = null
) {
    /** passivas de itens e efeitos de runas, no mesmo formato (as runas usam a chave "r" + id) */
    internal val models: Map<String, ItemModel> = itemModels + (runeData?.models ?: emptyMap())

    private val runeEngine: RuneEngine? = runeData?.let { RuneEngine(db, it) }

    // ----------------------------------------------------------- perfil do campeão

    internal class Profile(
        val champ: Champion, val hero: HeroDetail, val arch: Arch, val ranged: Boolean,
        val autoUptime: Double, val window: Double, val usesMana: Boolean, val lane: Lane
    )

    private fun cls(hero: HeroDetail): String = when (hero.role) {
        "射手" -> "marksman"
        "法师" -> "mage"
        "刺客" -> "assassin"
        "坦克" -> "tank"
        "辅助" -> "support"
        else -> "fighter"
    }

    internal fun profile(c: Champion, lane: Lane): Profile? {
        val hero = db.heroes[c.id] ?: return null
        val k = cls(hero)
        val ranged = when {
            c.key in MELEE -> false
            c.key in RANGED -> true
            else -> k == "marksman" || k == "mage" || k == "support"
        }
        val sup = lane == Lane.SUP
        val arch = when (k) {
            "marksman" -> Arch.MARKSMAN
            "assassin" -> Arch.ASSASSIN
            "tank" -> if (sup) Arch.TANK_SUPPORT else Arch.TANK
            "support" -> if (c.has("encant")) Arch.ENCHANTER else Arch.TANK_SUPPORT
            "mage" -> when {
                sup && c.has("encant") -> Arch.ENCHANTER
                c.has("tank") -> Arch.FIGHTER
                else -> Arch.MAGE
            }
            else -> if (sup && c.has("tank")) Arch.TANK_SUPPORT else Arch.FIGHTER
        }
        val uptime = AUTO_UPTIME[c.key] ?: when (arch) {
            Arch.MARKSMAN -> 1.0
            Arch.FIGHTER -> if (k == "mage") 0.3 else 0.6
            Arch.ASSASSIN -> 0.4
            Arch.TANK, Arch.TANK_SUPPORT -> 0.4
            Arch.MAGE, Arch.ENCHANTER -> 0.15
        }
        val window = when (arch) {
            Arch.ASSASSIN -> 4.5
            Arch.MAGE -> 6.0
            Arch.TANK, Arch.TANK_SUPPORT -> 10.0
            else -> 8.0
        }
        return Profile(c, hero, arch, ranged, uptime, window, hero.mp > 0, lane)
    }

    // -------------------------------------------------------------- inimigos e contexto

    internal class Target(val name: String, val hp: Double, val bonusHp: Double, val armor: Double, val mr: Double, val weight: Double)

    internal class Ctx(
        val targets: List<Target>,
        val phys: Double, val magic: Double, val trueDmg: Double,
        /** parte do dano inimigo que vem de ataques básicos, e a parte deles que é crítico */
        val autoShare: Double, val critShare: Double,
        val ccCount: Int, val ccNames: List<String>,
        val healers: List<String>, val shielders: List<String>,
        val divers: List<String>, val tanks: List<String>,
        val allyAutoers: Int, val allyAp: Int, val allyCarry: Boolean, val allyEngage: Boolean,
        val known: Boolean,
        val enemies: List<Champion>,
        val allies: List<Champion>,
        /** o inimigo que deve jogar na mesma rota que você (deduzido) */
        val laneOpp: Champion?
    )

    /** Atributos defensivos de um inimigo no nível dado, supondo `n` itens típicos da classe dele. */
    private fun target(c: Champion, level: Int, n: Int, weight: Double): Target? {
        val h = db.heroes[c.id] ?: return null
        val k = cls(h)
        val tanky = k == "tank" || (k == "fighter" && c.has("tank"))
        val (hpI, arI, mrI) = when {
            k == "tank" -> Triple(420.0, 38.0, 28.0)
            tanky -> Triple(340.0, 26.0, 20.0)
            k == "fighter" -> Triple(260.0, 16.0, 12.0)
            k == "assassin" -> Triple(60.0, 6.0, 4.0)
            k == "mage" -> Triple(90.0, 9.0, 3.0)
            k == "marksman" -> Triple(40.0, 5.0, 4.0)
            else -> Triple(110.0, 8.0, 8.0)
        }
        val base = h.at(h.hp, h.hpPer, level)
        return Target(c.name, base + hpI * n, hpI * n, h.at(h.armor, h.armorPer, level) + arI * n, h.at(h.mr, h.mrPer, level) + mrI * n, weight)
    }

    private val laneReader by lazy { Engine(data) }

    internal fun context(p: Profile, d: Draft, level: Int, n: Int): Ctx {
        val enemies = d.enemies.mapNotNull { data.champs[it] }
        val allies = d.allies.values.mapNotNull { data.champs[it] }
        val targets = ArrayList<Target>()
        for (e in enemies) {
            val h = db.heroes[e.id] ?: continue
            val k = cls(h)
            val squishy = k == "marksman" || k == "mage" || k == "support" || k == "assassin"
            val tank = k == "tank" || e.has("tank")
            val w = when (p.arch) {
                Arch.MARKSMAN -> if (tank) 1.3 else if (squishy) 0.8 else 1.2
                Arch.MAGE, Arch.ENCHANTER -> if (tank) 0.7 else if (squishy) 1.1 else 1.0
                Arch.ASSASSIN -> if (tank) 0.2 else if (squishy) 1.6 else 0.5
                else -> if (tank) 0.8 else 1.0
            }
            target(e, level, n, w)?.let { targets.add(it) }
        }
        if (targets.isEmpty()) {
            // draft inimigo desconhecido: um alvo frágil, um lutador e um tanque típicos
            val f = (level - 1) / 14.0
            targets.add(Target("alvo frágil", 620 + 1300 * f + 60 * n, 60.0 * n, 32 + 55 * f + 6 * n, 32 + 16 * f + 4 * n, 1.0))
            targets.add(Target("lutador", 680 + 1750 * f + 260 * n, 260.0 * n, 42 + 60 * f + 16 * n, 36 + 26 * f + 12 * n, 1.0))
            targets.add(Target("tanque", 700 + 1850 * f + 420 * n, 420.0 * n, 48 + 62 * f + 38 * n, 38 + 28 * f + 28 * n, 0.8))
        }

        var phys = 0.0
        var magic = 0.0
        var tr = 0.0
        var auto = 0.0
        var crit = 0.0
        for (e in enemies) {
            val h = db.heroes[e.id] ?: continue
            val k = cls(h)
            val threat = when (k) {
                "marksman", "assassin", "mage" -> 1.0
                "fighter" -> 0.8
                "tank" -> 0.45
                else -> 0.25
            }
            val (ph, mg) = when (e.dmg) {
                Dmg.AD -> 0.87 to 0.08
                Dmg.AP -> 0.08 to 0.87
                Dmg.MX -> 0.5 to 0.45
            }
            phys += threat * ph
            magic += threat * mg
            tr += threat * 0.05
            val autoPart = when (k) {
                "marksman" -> 0.8
                "fighter" -> 0.5
                "assassin" -> 0.3
                "tank" -> 0.3
                else -> 0.1
            }
            auto += threat * ph * autoPart
            if (k == "marksman") crit += threat * ph * autoPart * 0.75
        }
        var total = phys + magic + tr
        if (total <= 0.0) {
            phys = 0.5
            magic = 0.45
            tr = 0.05
            auto = 0.25
            crit = 0.1
            total = 1.0
        }
        val cc = enemies.filter { it.has("cc") }
        val opp = laneReader.deduceEnemyLanes(d.enemies).entries.firstOrNull { it.value == p.lane }?.key?.let { data.champs[it] }
        return Ctx(
            targets, phys / total, magic / total, tr / total, auto / total, crit / total,
            cc.size, cc.map { it.name },
            enemies.filter { it.has("cura") }.map { it.name },
            enemies.filter { it.hasAny("escudo", "encant") }.map { it.name },
            enemies.filter { it.hasAny("assassino", "dive") }.map { it.name },
            enemies.filter { it.has("tank") }.map { it.name },
            allies.count { db.heroes[it.id]?.let { h -> cls(h) == "marksman" } == true || it.key in AUTO_UPTIME },
            allies.count { it.dmg == Dmg.AP },
            allies.any { it.has("hyper") }, allies.any { it.has("engage") },
            enemies.isNotEmpty(), enemies, allies, opp
        )
    }

    // ---------------------------------------------------------------- atributos finais

    private class Stats {
        var hp = 0.0
        var baseHp = 0.0
        var mana = 0.0
        var ad = 0.0
        var baseAd = 0.0
        var ap = 0.0
        var armor = 0.0
        var baseArmor = 0.0
        var mr = 0.0
        var baseMr = 0.0
        var asPct = 0.0
        var attackSpeed = 0.0
        var crit = 0.0
        var critDmg = 2.0
        var haste = 0.0
        var hasteUlt = 0.0
        var hasteBasic = 0.0
        var ms = 0.0
        var armorPen = 0.0
        var armorPenPct = 0.0
        var magicPen = 0.0
        var magicPenPct = 0.0
        var tenacity = 0.0
        /** aumento das curas e escudos que você aplica, em % */
        var hsPower = 0.0
        val fx = ArrayList<Pair<Fx, ItemModel>>()
    }

    /** Efeitos que crescem durante a luta (acúmulos): quanto deles, em média, vale na conta. */
    private fun ramp(p: Profile) = if (p.window >= 8) 0.75 else 0.55

    /** Dano e bônus "adaptativos" viram AP para quem causa dano mágico e AD para o resto. */
    private fun adaptiveAp(p: Profile, s: Stats) =
        p.champ.dmg == Dmg.AP || (p.champ.dmg == Dmg.MX && s.ap > s.ad - s.baseAd)

    private fun stats(p: Profile, level: Int, items: List<Item>, ctx: Ctx): Stats {
        val h = p.hero
        val s = Stats()
        s.baseHp = h.at(h.hp, h.hpPer, level)
        s.hp = s.baseHp
        s.mana = h.at(h.mp, h.mpPer, level)
        s.baseAd = h.at(h.ad, h.adPer, level)
        s.ad = s.baseAd
        s.baseArmor = h.at(h.armor, h.armorPer, level)
        s.armor = s.baseArmor
        s.baseMr = h.at(h.mr, h.mrPer, level)
        s.mr = s.baseMr
        s.critDmg = h.critDamage / 100.0
        s.ms = h.moveSpeed
        var asIdx = h.at(h.attackSpeedIndex, h.attackSpeedPer, level)
        var msPct = 0.0

        fun add(key: String, v: Double) {
            when (key) {
                "hp" -> s.hp += v
                "mana" -> if (p.usesMana) s.mana += v
                "ad" -> s.ad += v
                "ap" -> s.ap += v
                "armor" -> s.armor += v
                "mr" -> s.mr += v
                "as%" -> s.asPct += v
                "crit%" -> s.crit += v
                "haste" -> s.haste += v
                "ms" -> s.ms += v
                "ms%" -> msPct += v
                "armorPen" -> s.armorPen += v
                "armorPen%" -> s.armorPenPct = 1 - (1 - s.armorPenPct) * (1 - v / 100)
                "magicPen" -> s.magicPen += v
                "magicPen%" -> s.magicPenPct = 1 - (1 - s.magicPenPct) * (1 - v / 100)
                "tenacity%" -> s.tenacity = 1 - (1 - s.tenacity) * (1 - v / 100)
            }
        }

        for (it in items) {
            for ((k, v) in it.values) add(k, v)
            val m = models[it.id] ?: continue
            for (f in m.fx) if (f.activeAt(level)) s.fx.add(f to m)
        }
        // atributos condicionais das passivas e das runas
        for ((f, _) in s.fx) {
            when (f.t) {
                "add" -> {
                    var v = f.num("v", level)
                    when (f.str("cond")) {
                        "magic" -> v *= min(1.0, ctx.magic / 0.45)
                        "ramp" -> v *= ramp(p)
                        // bônus que só vale enquanto você está sob controle de grupo
                        "ccd" -> v *= min(1.0, ctx.ccCount * 0.2)
                        // acumula nas trocas com campeões: mais na rota solo, bem menos na selva
                        "trades" -> v *= when (p.lane) {
                            Lane.BARON -> 1.0
                            Lane.MID -> 0.8
                            Lane.JUNGLE -> 0.35
                            else -> 0.6
                        }
                    }
                    // "au": o bônus só existe enquanto o campeão está atacando sem parar
                    if (f.has("au")) v *= min(1.0, p.autoUptime)
                    if (p.ranged && f.has("rm")) v *= f.num("rm")
                    add(f.str("stat"), v)
                }
                "haste" -> when (f.str("k")) {
                    "ult" -> s.hasteUlt += f.num("v", level)
                    "basic" -> s.hasteBasic += f.num("v", level)
                }
                "critdmg" -> s.critDmg += f.num("v") / 100
                "hspct" -> s.hsPower += f.num("pct", level)
            }
        }
        // bônus adaptativos depois dos atributos fixos: dependem de qual dano o campeão já tem mais
        for ((f, _) in s.fx) {
            if (f.t != "adaptive") continue
            var mul = if (f.has("f")) f.num("f") else 1.0
            if (p.ranged && f.has("rm")) mul *= f.num("rm")
            if (f.str("cond") == "ramp") mul *= ramp(p)
            if (adaptiveAp(p, s)) s.ap += f.num("ap", level) * mul else s.ad += f.num("ad", level) * mul
        }
        // conversões: primeiro a vida (que depende da mana), depois AP e AD, por fim os multiplicadores
        for ((f, _) in s.fx) if (f.t == "conv" && f.str("to") == "hp") s.hp += s.mana * f.num("pct") / 100
        for ((f, _) in s.fx) if (f.t == "mult" && f.str("stat") == "hp") s.hp *= 1 + f.num("pct", level) / 100
        val bonusHp = s.hp - s.baseHp
        for ((f, _) in s.fx) {
            if (f.t != "conv") continue
            val from = when (f.str("from")) {
                "mana" -> s.mana
                "bhp" -> bonusHp
                "bsad" -> s.baseAd
                else -> 0.0
            }
            when (f.str("to")) {
                "ap" -> s.ap += from * f.num("pct") / 100
                "ad" -> s.ad += from * f.num("pct") / 100
            }
        }
        for ((f, _) in s.fx) {
            when (f.t) {
                "mult" -> when (f.str("stat")) {
                    "ap" -> s.ap *= 1 + f.num("pct", level) / 100
                    "ad" -> s.ad *= 1 + f.num("pct", level) / 100
                    "armor" -> s.armor *= 1 + f.num("pct", level) / 100
                    "mr" -> s.mr *= 1 + f.num("pct", level) / 100
                }
                "multbonus" -> when (f.str("stat")) {
                    "armor" -> s.armor += (s.armor - s.baseArmor) * f.num("pct") / 100
                    "mr" -> s.mr += (s.mr - s.baseMr) * f.num("pct") / 100
                }
            }
        }
        s.crit = s.crit / 100
        // passiva de Yasuo e Yone: a chance de crítico vale em dobro, mas o crítico dá 10% menos dano
        if (p.champ.key in DOUBLE_CRIT) {
            s.crit *= 2
            s.critDmg *= 0.9
        }
        s.crit = min(1.0, s.crit)
        s.ms *= 1 + msPct / 100
        asIdx += s.asPct
        s.attackSpeed = min(2.5, 0.625 * (1 + asIdx / 100))
        return s
    }

    // --------------------------------------------------------------------- luta simulada

    private class Fight(var phys: Double = 0.0, var magic: Double = 0.0, var tru: Double = 0.0, var heal: Double = 0.0) {
        val total get() = phys + magic + tru
    }

    private fun basicRank(level: Int) = ((level + 1) / 3.5).toInt().coerceIn(1, 4)
    private fun ultRank(level: Int) = if (level < 5) 0 else if (level < 9) 1 else if (level < 13) 2 else 3

    /** Valor de uma parte "x% do atributo" de uma fórmula. */
    private fun statValue(stat: String, s: Stats, t: Target): Double = when (stat) {
        "AD" -> s.ad
        "AD bônus" -> s.ad - s.baseAd
        "AP" -> s.ap
        "vida máx.", "vida" -> s.hp
        "vida bônus" -> s.hp - s.baseHp
        "vida perdida" -> s.hp * 0.4
        "vida atual" -> s.hp * 0.6
        "armadura" -> s.armor
        "armadura bônus" -> s.armor - s.baseArmor
        "RM" -> s.mr
        "RM bônus" -> s.mr - s.baseMr
        "vida máx. do alvo" -> t.hp
        "vida perdida do alvo" -> t.hp * 0.5
        "vida atual do alvo" -> t.hp * 0.6
        "mana máx.", "mana" -> s.mana
        "vel. de movimento" -> s.ms
        else -> 0.0
    }

    /** Dano bruto de uma passiva de item ("flat" + porcentagens de atributos). */
    private fun fxDamage(f: Fx, s: Stats, t: Target, level: Int): Double {
        var v = f.num("flat", level)
        v += f.num("ap") / 100 * s.ap
        v += f.num("tad") / 100 * s.ad
        v += f.num("bad") / 100 * (s.ad - s.baseAd)
        v += f.num("bsad") / 100 * s.baseAd
        v += f.num("tmax") / 100 * t.hp
        v += f.num("tcur") / 100 * t.hp * 0.6
        v += f.num("mana") / 100 * s.mana
        v += f.num("bhp") / 100 * (s.hp - s.baseHp)
        v += f.num("mhp") / 100 * s.hp
        v += f.num("barm") / 100 * (s.armor - s.baseArmor)
        v += f.num("critflat") * s.crit * 100
        v += f.num("miss") / 100 * s.hp * 0.5
        return v
    }

    private fun fight(p: Profile, s: Stats, t: Target, level: Int): Fight {
        val T = p.window
        val out = Fight()
        val abil = Fight()
        val ult = Fight()
        val attack = Fight()
        val apMode = adaptiveAp(p, s)

        fun put(dst: Fight, type: String, v: Double) {
            when (type) {
                "p" -> dst.phys += v
                "m" -> dst.magic += v
                // adaptativo: mágico para quem tem mais AP, físico para o resto
                "a" -> if (apMode) dst.magic += v else dst.phys += v
                else -> dst.tru += v
            }
        }
        fun rm(f: Fx) = if (p.ranged && f.has("rm")) f.num("rm") else 1.0
        fun factor(f: Fx) = (if (f.has("f")) f.num("f") else 1.0) * (if (f.has("hit")) f.num("hit") else 1.0)

        // --- ataques básicos
        val nAutos = s.attackSpeed * T * p.autoUptime
        val critFactor = 1 + s.crit * (s.critDmg - 1)
        attack.phys += nAutos * s.ad * critFactor
        var onhitMult = 1.0
        for ((f, _) in s.fx) if (f.t == "onhitmult") onhitMult = max(onhitMult, f.num("v"))
        var cleave = 0.0
        for ((f, _) in s.fx) {
            when (f.t) {
                "onhit" -> {
                    var v = fxDamage(f, s, t, level) * rm(f) * onhitMult * factor(f)
                    if (f.has("every")) v /= f.num("every")
                    if (f.has("mult")) v *= f.num("mult")
                    // dano que cresce com a velocidade de ataque bônus (Ritmo Fatal)
                    if (f.has("asamp")) v *= 1 + f.num("asamp") * s.asPct / 100
                    // só depois de 6 ataques (acúmulos completos)
                    if (f.str("cond") == "stacks6") v *= ((nAutos - 6) / max(nAutos, 1.0)).coerceIn(0.0, 1.0)
                    put(attack, f.str("dt"), v * nAutos)
                }
                // flechas em alvos vizinhos: valem metade, porque não caem no mesmo alvo
                "cleave" -> cleave += f.num("tad") / 100 * s.ad * critFactor * f.num("n") * nAutos * 0.5
            }
        }
        attack.phys += cleave

        // --- habilidades do campeão
        var casts = 0.0
        var cdrAuto = 0.0
        for ((f, _) in s.fx) if (f.t == "cdrauto") cdrAuto = f.num("pct") / 100
        for (sk in p.hero.skills) {
            if (sk.slot == 0) continue
            val rank = if (sk.slot == 4) ultRank(level) else basicRank(level)
            if (rank == 0) continue
            val idx = min(rank, max(1, sk.ranks)) - 1
            val dmgFx = sk.effects.filter { it.raw == null && it.kind.startsWith("Dano") }
            var cd = (sk.table.firstOrNull { it.first == "Recarga (s)" }?.second ?: "")
                .split('/').mapNotNull { it.trim().toDoubleOrNull() }.let { l -> l.getOrNull(min(idx, l.size - 1)) } ?: if (sk.slot == 4) 70.0 else 9.0
            if (cd < 1.5) cd = 1.5
            var cdEff = cd * 100 / (100 + s.haste + if (sk.slot == 4) s.hasteUlt else s.hasteBasic)
            if (sk.slot != 4 && cdrAuto > 0) cdEff /= 1 + cdrAuto * s.attackSpeed * p.autoUptime * cdEff * 0.5
            // usos esperados na luta: conta contínua (a duração das lutas varia), para a aceleração
            // de habilidade valer de forma proporcional, e não aos saltos
            val n = if (sk.slot == 4) 1.0 else min(6.0, 1 + max(0.0, T - 0.5) / max(cdEff, 1.0))
            if (dmgFx.isEmpty()) continue
            casts += n
            val values = dmgFx.map { e ->
                var v = e.base.getOrElse(min(idx, e.base.size - 1)) { 0.0 }
                for (r in e.ratios) v += r.pct.getOrElse(min(idx, r.pct.size - 1)) { 0.0 } / 100 * statValue(r.stat, s, t)
                val type = when (e.kind) {
                    "Dano físico" -> "p"
                    "Dano mágico" -> "m"
                    "Dano verdadeiro" -> "t"
                    else -> if (p.champ.dmg == Dmg.AP) "m" else "p"
                }
                type to v
            }
            // a maior fórmula vale inteira; as outras costumam ser variações ou partes extras
            val best = values.maxByOrNull { it.second }!!
            val dst = if (sk.slot == 4) ult else abil
            put(dst, best.first, best.second * n)
            for (v in values) if (v !== best) put(dst, v.first, v.second * n * 0.35)
        }
        // passiva com dano: entra uma vez por habilidade usada
        p.hero.skills.firstOrNull { it.slot == 0 }?.effects?.firstOrNull { it.raw == null && it.kind.startsWith("Dano") }?.let { e ->
            var v = e.base.lastOrNull() ?: 0.0
            for (r in e.ratios) v += (r.pct.lastOrNull() ?: 0.0) / 100 * statValue(r.stat, s, t)
            put(abil, if (e.kind == "Dano físico") "p" else if (e.kind == "Dano verdadeiro") "t" else if (e.kind == "Dano mágico" || p.champ.dmg == Dmg.AP) "m" else "p", v * min(4.0, casts * 0.6 + nAutos * 0.15))
        }

        // --- passivas de itens e runas que causam dano
        val hasUlt = ultRank(level) > 0
        for ((f, _) in s.fx) {
            when (f.t) {
                "proc" -> {
                    val cd = max(0.5, f.num("cd", level))
                    val byCd = 1 + floor(T / cd)
                    val count = when (f.str("on")) {
                        "attack" -> if (nAutos >= 1) min(byCd, nAutos) else 0.0
                        "spell" -> min(byCd, casts)
                        "blade" -> min(byCd, min(casts, nAutos))
                        "ult" -> if (hasUlt) 1.0 else 0.0
                        "cc" -> if (p.champ.has("cc")) min(byCd, 3.0) else 0.0
                        "combat" -> floor(T / cd)
                        // 3 acertos separados no mesmo alvo (Eletrocutar)
                        "combo" -> if (casts + nAutos >= 3) min(byCd, max(1.0, floor((casts + nAutos) / 3))) else 0.0
                        // alvo com pouca vida: uma vez por luta
                        "lowhp" -> min(byCd, 1.0)
                        // 3 ataques seguidos no mesmo alvo
                        "auto3" -> if (nAutos >= 3) min(byCd, 1.0) else 0.0
                        // depois de um avanço (ou do Flash, para quem não tem avanço)
                        "dash" -> if (p.champ.has("mobil")) min(byCd, 1.0) else 0.4
                        // alvo lento ou preso: pelo controle do próprio campeão ou dos aliados
                        "impaired" -> if (p.champ.has("cc")) min(byCd, 2.0) else min(byCd, 0.7)
                        // qualquer acerto (ataque ou habilidade)
                        "hit" -> min(byCd, casts + nAutos)
                        else -> byCd
                    }
                    val on = f.str("on")
                    val dst = if (on == "attack" || on == "blade" || on == "auto3") attack else abil
                    put(dst, f.str("dt"), fxDamage(f, s, t, level) * rm(f) * count * factor(f))
                }
                "burn" -> put(abil, f.str("dt"), fxDamage(f, s, t, level) * min(casts * f.num("dur"), T))
                "onspell" -> put(abil, f.str("dt"), fxDamage(f, s, t, level) * rm(f) * casts)
                "aura" -> put(abil, f.str("dt"), fxDamage(f, s, t, level) * T * (if (p.ranged) 0.3 else 1.0))
            }
        }

        // --- amplificadores de dano
        var ampAll = 1.0
        var ampAbil = 1.0
        var ampUlt = 1.0
        var ampAttack = 1.0
        var ampMagic = 1.0
        for ((f, _) in s.fx) {
            if (f.t != "amp") continue
            var pct = f.num("pct", level)
            when (f.str("cond")) {
                "bonushp" -> pct *= min(1.0, t.bonusHp / 1200)
                "ramp" -> pct *= if (T >= 6) 0.8 else 0.55
                "far" -> pct *= if (p.ranged) 0.7 else 0.0
                // vale depois do 3º ataque seguido
                "auto3" -> pct *= ((nAutos - 3) / max(nAutos, 1.0)).coerceIn(0.0, 1.0)
                // alvo acima de 60% da vida: todo alvo começa assim; abaixo de 40%: só se a luta
                // for até o fim (muitas vezes o inimigo foge ou a luta acaba antes)
                "tgthigh" -> pct *= 0.5
                "tgtlow" -> pct *= 0.32
                // você mesmo com pouca vida: corpo a corpo fica mais tempo assim
                "selflow" -> pct *= if (p.ranged) 0.25 else 0.4
                // só os primeiros 3 s da luta
                "first3" -> pct *= min(1.0, 3.5 / T)
            }
            when (f.str("scope")) {
                "abil" -> ampAbil *= 1 + pct / 100
                "ult" -> ampUlt *= 1 + pct / 100
                "attack" -> ampAttack *= 1 + pct / 100
                "magic" -> ampMagic *= 1 + pct / 100
                else -> ampAll *= 1 + pct / 100
            }
        }

        // --- armadura e resistência mágica do alvo, com redução e penetração
        var armor = t.armor
        var mr = t.mr
        for ((f, _) in s.fx) {
            if (f.t != "shred") continue
            if (f.str("dt") == "p") armor *= 1 - f.num("pct") / 100 * 0.8 else mr *= 1 - f.num("pct") / 100 * 0.8
        }
        armor = max(0.0, armor * (1 - s.armorPenPct) - s.armorPen)
        mr = max(0.0, mr * (1 - s.magicPenPct) - s.magicPen)
        val pm = 100 / (100 + armor)
        val mm = 100 / (100 + mr)

        out.phys = ((abil.phys + ult.phys * ampUlt) * ampAbil + attack.phys * ampAttack) * ampAll * pm
        out.magic = ((abil.magic + ult.magic * ampUlt) * ampAbil + attack.magic * ampAttack) * ampAll * ampMagic * mm
        out.tru = ((abil.tru + ult.tru * ampUlt) * ampAbil + attack.tru * ampAttack) * ampAll

        // --- cura própria que depende do dano causado
        val selfHeal = 1 + s.hsPower / 100 * 0.6
        for ((f, _) in s.fx) {
            when (f.t) {
                "vamp" -> {
                    var pct = f.num("pct", level) / 100 * rm(f)
                    // vampirismo que só vem com acúmulos completos (Conquistador)
                    if (f.str("cond") == "ramp") pct *= 0.6
                    out.heal += pct * if (f.str("scope") == "attack") attack.phys * ampAttack * ampAll * pm else out.total * 0.8
                }
                "heal" -> {
                    val cd = max(1.0, f.num("cd", level))
                    var count = 1 + floor(T / cd)
                    when (f.str("on")) {
                        "blade" -> count = min(count, min(casts, nAutos))
                        "attack" -> count = min(count, nAutos)
                    }
                    out.heal += fxDamage(f, s, t, level) * rm(f) * count * factor(f) * selfHeal
                }
                "healhit" -> out.heal += f.num("flat") * nAutos
            }
        }
        return out
    }

    /**
     * Cura e escudo que as habilidades do campeão dão numa luta (para suportes de cura,
     * é isso que faz o papel do "dano").
     */
    private fun kitSupport(p: Profile, s: Stats, level: Int): Double {
        val dummy = Target("", 2000.0, 0.0, 60.0, 40.0, 1.0)
        var total = 0.0
        for (sk in p.hero.skills) {
            val fx = sk.effects.filter { it.raw == null && (it.kind == "Cura" || it.kind == "Escudo") }
            if (fx.isEmpty()) continue
            val rank = if (sk.slot == 4) ultRank(level) else if (sk.slot == 0) 1 else basicRank(level)
            if (rank == 0) continue
            val idx = min(rank, max(1, sk.ranks)) - 1
            var cd = (sk.table.firstOrNull { it.first == "Recarga (s)" }?.second ?: "")
                .split('/').mapNotNull { it.trim().toDoubleOrNull() }.let { l -> l.getOrNull(min(idx, l.size - 1)) } ?: if (sk.slot == 4) 70.0 else 9.0
            if (cd < 1.5) cd = 1.5
            val cdEff = cd * 100 / (100 + s.haste + if (sk.slot == 4) s.hasteUlt else s.hasteBasic)
            val n = if (sk.slot == 4) 1.0 else if (sk.slot == 0) 2.0 else min(6.0, 1 + max(0.0, p.window - 0.5) / max(cdEff, 1.0))
            val e = fx.maxByOrNull { it.base.lastOrNull() ?: 0.0 }!!
            var v = e.base.getOrElse(min(idx, e.base.size - 1)) { 0.0 }
            for (r in e.ratios) v += r.pct.getOrElse(min(idx, r.pct.size - 1)) { 0.0 } / 100 * statValue(r.stat, s, dummy)
            total += v * n
        }
        return total
    }

    // ------------------------------------------------------------------- nota da build

    internal class Score(val off: Double, val def: Double, val util: Double, val total: Double, val physPart: Double, val magicPart: Double)

    /**
     * Valor, em % da nota, de runas que não mudam a luta em si (torres, deslocamento, visão,
     * plantas, sustento na rota). Depende da rota e do tipo de campeão.
     */
    internal fun utilValue(k: String, p: Profile, ctx: Ctx): Double {
        val immobile = !p.champ.has("mobil")
        return when (k) {
            "tower" -> when (p.lane) {
                Lane.BARON -> 3.0 + if (p.champ.has("split")) 1.0 else 0.0
                Lane.MID -> 0.8
                else -> 0.3
            }
            "roam" -> when {
                p.lane == Lane.JUNGLE -> 2.5
                p.lane == Lane.SUP -> 1.8
                p.lane == Lane.MID && (p.arch == Arch.ASSASSIN || p.champ.has("mobil")) -> 1.4
                else -> 0.4
            }
            "ms" -> 1.0 + if (!p.ranged && immobile) 0.8 else 0.0
            "summms" -> (if (p.ranged) 1.6 else 0.9) + if (immobile) 0.4 else 0.0
            "mana" -> if (p.arch == Arch.MAGE || p.arch == Arch.ENCHANTER) 1.4 else 0.8
            "plants" -> when (p.lane) {
                Lane.JUNGLE -> 1.0
                Lane.SUP -> 0.8
                else -> 0.4
            }
            "seed" -> if (p.lane == Lane.SUP) 0.6 else 0.3
            "flash" -> 0.6
            // sustento na rota: vale mais para corpo a corpo contra quem bate de longe
            "lane" -> {
                val opp = ctx.laneOpp
                val oppRanged = opp != null && (opp.hasAny("poke", "alcance") || (db.heroes[opp.id]?.let { cls(it) } in setOf("marksman", "mage")))
                when {
                    p.ranged -> 0.5
                    opp == null -> 1.0
                    oppRanged -> 1.8
                    else -> 0.9
                }
            }
            "gold" -> 1.0
            "kite" -> 1.2 + if (immobile) 0.8 else 0.0
            "teamslow" -> when (p.arch) {
                Arch.TANK_SUPPORT -> 5.0
                Arch.TANK -> 3.5
                Arch.ENCHANTER -> 1.5
                else -> 1.0
            }
            "triumph" -> 0.8
            "fleetms" -> 0.8
            "vision" -> when (p.lane) {
                Lane.SUP -> 1.5
                Lane.JUNGLE -> 1.0
                else -> 0.2
            }
            else -> 0.0
        }
    }

    internal fun score(p: Profile, level: Int, items: List<Item>, ctx: Ctx): Score {
        val s = stats(p, level, items, ctx)
        var off = 0.0
        var heal = 0.0
        var phys = 0.0
        var magic = 0.0
        var wsum = 0.0
        for (t in ctx.targets) {
            val f = fight(p, s, t, level)
            off += f.total * t.weight
            heal += f.heal * t.weight
            phys += f.phys * t.weight
            magic += f.magic * t.weight
            wsum += t.weight
        }
        off /= wsum
        heal /= wsum
        val dmgTotal = max(1.0, phys + magic)

        // ---------- defesa: quanto dano bruto do time inimigo é preciso para derrubar o campeão
        var drAll = 0.0
        var drAuto = 0.0
        var drCrit = 0.0
        var enemyAs = 0.0
        var recv = 1.0
        var shields = 0.0
        var extra = 1.0
        var flatEhp = 0.0
        var reflect = 0.0
        val near = if (p.ranged) 0.4 else 1.0
        for ((f, _) in s.fx) {
            when (f.t) {
                "dr" -> {
                    val v = f.num("pct") / 100 * (if (p.ranged && f.has("rm")) f.num("rm") else 1.0)
                    when (f.str("vs")) {
                        "auto" -> drAuto += v
                        "crit" -> drCrit += v
                        else -> drAll += v
                    }
                }
                "enemyas" -> enemyAs += f.num("pct") / 100 * near
                "hsrecv" -> recv += f.num("pct") / 100
            }
        }
        for ((f, _) in s.fx) {
            when (f.t) {
                "shield" -> {
                    var v = f.num("flat", level) + f.num("mhp") / 100 * s.hp + f.num("bhp") / 100 * (s.hp - s.baseHp) +
                        f.num("mana") / 100 * s.mana + f.num("bad") / 100 * (s.ad - s.baseAd) + f.num("ap") / 100 * s.ap +
                        f.num("barm") / 100 * (s.armor - s.baseArmor) + f.num("bmr") / 100 * (s.mr - s.baseMr)
                    if (p.ranged && f.has("rm")) v *= f.num("rm")
                    val cd = f.num("cd", level)
                    // recarga curta: entra mais de uma vez na luta; recarga longa: nem toda luta tem
                    v *= if (cd <= 10) 1 + floor(p.window / max(cd, 3.0)) else if (cd <= 30) 1.0 else 0.55
                    if (f.str("vs") == "magic") v *= min(1.0, ctx.magic * 1.6)
                    shields += v * recv * (1 + s.hsPower / 100 * 0.5)
                }
                // Anjo Guardião: uma segunda vida com metade da vida base, se a luta continuar
                "revive" -> flatEhp += s.baseHp * 0.5 * (0.6 + 0.15 * min(2, ctx.divers.size))
                // Zhonya: 2,5 s sem poder levar dano; vale mais contra quem mergulha em você
                "stasis" -> extra *= 1.08 + 0.05 * min(3, ctx.divers.size)
                "spellshield" -> flatEhp += (260 + 28.0 * level) * min(1.5, 0.4 + 0.3 * ctx.ccCount)
                "cleanse" -> extra *= 1 + 0.035 * min(4, ctx.ccCount)
                "reflect" -> reflect += (f.num("flat") + f.num("barm") / 100 * (s.armor - s.baseArmor) + f.num("bhp") / 100 * (s.hp - s.baseHp)) * ctx.autoShare * 14
            }
        }
        extra *= 1 + s.tenacity * 0.15 * min(4, ctx.ccCount)
        val physMult = 100 / (100 + s.armor * 0.85)
        val magicMult = 100 / (100 + s.mr * 0.88)
        val autoPart = min(ctx.autoShare, ctx.phys)
        val critPart = min(ctx.critShare, autoPart)
        val physTaken = ((ctx.phys - autoPart) + (autoPart - critPart) * (1 - drAuto) * (1 - enemyAs * 0.8) +
            critPart * (1 - drAuto) * (1 - drCrit) * (1 - enemyAs * 0.8)) * physMult
        val taken = (physTaken + ctx.magic * magicMult + ctx.trueDmg) * (1 - drAll)
        val def = ((s.hp + shields + heal * recv * 0.7) / max(0.05, taken) + flatEhp) * extra
        off += reflect * 100 / (100 + 60.0)

        // ---------- utilidade: efeitos que não são dano nem resistência
        var util = 1.0
        var pts = 0.0
        val hasGw = s.fx.any { (f, _) ->
            f.t == "gw" && when (f.str("scope")) {
                "phys" -> phys / dmgTotal >= 0.3
                "magic" -> magic / dmgTotal >= 0.3
                else -> ctx.autoShare >= 0.2
            }
        }
        if (hasGw && ctx.healers.isNotEmpty()) util *= 1 + 0.05 * min(3, ctx.healers.size)
        if (s.fx.any { it.first.t == "antishield" } && ctx.shielders.isNotEmpty()) util *= 1 + 0.035 * min(3, ctx.shielders.size)
        if (s.fx.any { it.second.tags.contains("slow") } && !p.ranged) util *= 1.012
        for ((f, _) in s.fx) {
            if (f.t != "ally") continue
            // efeito que depende de mirar aliados com habilidades (escudo da Aery): só para encantadores
            if (f.str("cond") == "encant" && p.arch != Arch.ENCHANTER) continue
            var amount = f.num("flat", level) + f.num("ap") / 100 * s.ap + f.num("bhp") / 100 * (s.hp - s.baseHp) +
                f.num("mhp") / 100 * s.hp + f.num("mana") / 100 * s.mana + f.num("bad") / 100 * (s.ad - s.baseAd)
            amount *= 1 + s.hsPower / 100
            val times = if (f.has("cd")) 1 + floor(10 / max(1.0, f.num("cd", level))) else 1.0
            val r = if (p.ranged && f.has("rm")) f.num("rm") else 1.0
            pts += r * when (f.str("k")) {
                "heal" -> amount * times / 110
                "shield" -> amount * times / 80
                "as" -> (0.8 + 1.4 * ctx.allyAutoers) * f.num("v") / 30
                "ap" -> 1.0 + 0.8 * ctx.allyAp
                "healamp" -> 0.0 // já entra como multiplicador da cura e do escudo do campeão
                "ms" -> if (ctx.allyEngage) 3.0 else 2.0
                "redirect" -> if (ctx.allyCarry) 3.5 else 1.5
                "cleanse" -> min(5.0, 0.8 + 1.2 * ctx.ccCount)
                "amp" -> if (p.champ.has("cc")) 4.0 else 0.0
                "magicamp" -> 1.2 * ctx.allyAp
                else -> 0.0
            }
        }
        // suporte de cura: o que ele entrega é cura e escudo, então isso entra no lugar do dano
        if (p.arch == Arch.ENCHANTER) {
            var power = 1.0 + s.hsPower / 100
            for ((f, _) in s.fx) {
                if (f.t == "hsp") power += f.num("mana") / 100 * s.mana / 100
                if (f.t == "ally" && f.str("k") == "healamp") power += f.num("v") / 100
            }
            off = off * 0.35 + kitSupport(p, s, level) * power
        }
        val k = when (p.arch) {
            Arch.ENCHANTER -> 0.07
            Arch.TANK_SUPPORT -> 0.04
            Arch.TANK -> 0.02
            else -> 0.008
        }
        util *= 1 + pts * k
        // runas de utilidade (torres, deslocamento, visão…): valor direto, em % da nota
        for ((f, _) in s.fx) if (f.t == "util") util *= 1 + utilValue(f.str("k"), p, ctx) / 100

        var (a, b) = when (p.arch) {
            Arch.MARKSMAN, Arch.MAGE -> 0.78 to 0.22
            Arch.ASSASSIN -> 0.85 to 0.15
            // lutador "pesado" (Garen, Darius, Sett...) precisa aguentar mais do que o lutador de mergulho
            Arch.FIGHTER -> if (p.champ.has("tank")) 0.52 to 0.48 else 0.66 to 0.34
            Arch.TANK -> 0.25 to 0.75
            Arch.ENCHANTER -> 0.45 to 0.3
            Arch.TANK_SUPPORT -> 0.15 to 0.65
        }
        // inimigos que mergulham em você pedem mais resistência de quem é frágil
        if ((p.arch == Arch.MARKSMAN || p.arch == Arch.MAGE) && ctx.divers.size >= 2) {
            a -= 0.07
            b += 0.07
        }
        val total = off.pow(a) * def.pow(b) * util
        return Score(off, def, util, total, phys / dmgTotal, magic / dmgTotal)
    }

    // ----------------------------------------------------------------------- a build

    /**
     * Para que tipo de campeão o item foi feito. Um item fora do estilo do campeão ainda pode
     * entrar na build, mas precisa ganhar por uma margem maior nas contas.
     */
    private fun itemStyles(it: Item): Set<String> {
        STYLES[it.id]?.let { return it }
        val v = it.values
        val ad = v.containsKey("ad")
        val ap = v.containsKey("ap")
        val asp = v.containsKey("as%")
        val hp = v.containsKey("hp")
        return when {
            it.type == "Suporte" -> setOf("enchanter")
            v.containsKey("crit%") -> setOf("crit")
            ap && asp -> setOf("aponhit")
            ap && hp -> setOf("mage", "battlemage")
            ap -> setOf("mage")
            ad && v.containsKey("armorPen") -> setOf("lethality")
            ad && asp && hp -> setOf("bruiser")
            asp -> setOf("onhit")
            ad && (hp || v.containsKey("armor") || v.containsKey("mr")) -> setOf("bruiser")
            ad -> setOf("bruiser", "lethality")
            else -> setOf("tank")
        }
    }

    private fun champStyles(p: Profile): Map<String, Double> {
        val m = HashMap<String, Double>()
        val ap = p.champ.dmg == Dmg.AP
        val autoer = p.autoUptime >= 0.8
        when (p.arch) {
            Arch.MARKSMAN -> {
                m["crit"] = 1.0
                m["onhit"] = 1.0
                if (p.champ.dmg != Dmg.AD) m["aponhit"] = 1.0
                if (p.champ.has("poke")) m["lethality"] = 0.95
            }
            Arch.ASSASSIN -> if (ap) {
                m["mage"] = 1.0
                m["battlemage"] = 0.9
            } else {
                m["lethality"] = 1.0
                m["bruiser"] = 0.95
                if (autoer) {
                    m["onhit"] = 1.0
                    m["crit"] = 1.0
                }
            }
            Arch.FIGHTER -> if (ap) {
                m["battlemage"] = 1.0
                m["mage"] = 0.95
                m["tank"] = 0.93
                if (p.autoUptime >= 0.7) m["aponhit"] = 1.0
            } else {
                m["bruiser"] = 1.0
                m["tank"] = 0.93
                m["lethality"] = 0.9
                if (autoer) m["onhit"] = 1.0
                if (p.champ.key in CRIT_FIGHTERS) m["crit"] = 1.0
            }
            Arch.TANK -> {
                m["tank"] = 1.0
                m["tanksupport"] = 0.9
                m["bruiser"] = 0.88
                m["battlemage"] = 0.88
            }
            Arch.MAGE -> {
                m["mage"] = 1.0
                m["battlemage"] = 0.95
                if (p.autoUptime >= 0.6) m["aponhit"] = 1.0
            }
            Arch.ENCHANTER -> {
                m["enchanter"] = 1.0
                m["mage"] = 0.85
            }
            Arch.TANK_SUPPORT -> {
                m["tank"] = 1.0
                m["tanksupport"] = 1.0
            }
        }
        return m
    }

    private fun styleFit(p: Profile, it: Item, styles: Map<String, Double>): Double =
        itemStyles(it).maxOf { styles[it] ?: OFF_STYLE }

    private fun allowed(p: Profile, it: Item, m: ItemModel?, lane: Lane, chosen: List<Item>): Boolean {
        if (m != null) {
            when (m.only) {
                "ranged" -> if (!p.ranged) return false
                "melee" -> if (p.ranged) return false
                "mana" -> if (!p.usesMana) return false
                "enchanter" -> if (p.arch != Arch.ENCHANTER) return false
                "support" -> if (lane != Lane.SUP && p.arch != Arch.ENCHANTER && p.arch != Arch.TANK_SUPPORT) return false
                "auto" -> if (p.autoUptime < 0.6) return false
            }
            if (m.group != null && chosen.any { c -> models[c.id]?.group?.let { g -> g.split(',').any { it in m.group.split(',') } } == true }) return false
        }
        // itens de mana só servem para quem usa mana
        if (it.values.containsKey("mana") && !p.usesMana) return false
        val givesAp = it.values.containsKey("ap")
        val givesAd = it.values.containsKey("ad") || it.values.containsKey("crit%")
        val supportArch = p.arch == Arch.ENCHANTER || p.arch == Arch.TANK_SUPPORT
        when (p.champ.dmg) {
            Dmg.AD -> if (givesAp && !givesAd && !supportArch) return false
            Dmg.AP -> if (givesAd && !givesAp) return false
            Dmg.MX -> {}
        }
        // crítico e velocidade de ataque só valem para quem luta atacando
        if ((it.values.containsKey("crit%") || it.values.containsKey("as%")) && p.autoUptime < 0.6) return false
        // suportes e tanques não compram itens de dano puro
        if (p.arch == Arch.TANK_SUPPORT && (givesAd || givesAp) && !it.values.containsKey("hp")) return false
        return true
    }

    fun build(d: Draft): BuildResult? {
        val me = data.champs[d.myPick] ?: return null
        val p = profile(me, d.myLane) ?: return null
        val notes = ArrayList<String>()

        val candidates = db.items.filter { it.tier == 3 && it.price >= 2000 && models[it.id]?.let { m -> !m.hide && !m.boot } != false && it.type != "Outros" }
        val styles = champStyles(p)
        val ctx = context(p, d, 15, 5)

        /** Nota de um conjunto de itens no fim do jogo; itens fora do estilo do campeão pesam contra. */
        fun value(set: List<Item>): Double {
            var fit = 1.0
            for (it in set) fit *= styleFit(p, it, styles)
            return score(p, 15, set, ctx).total * fit
        }

        // 1) O melhor conjunto final: começa vazio e vai somando o item que mais melhora a build completa.
        val set = ArrayList<Item>()
        for (slot in 1..5) {
            var best: Item? = null
            var bestVal = 0.0
            for (c in candidates) {
                if (c in set || !allowed(p, c, models[c.id], d.myLane, set)) continue
                val v = value(set + c)
                if (v > bestVal) {
                    bestVal = v
                    best = c
                }
            }
            set.add(best ?: break)
        }
        // 2) Refino: tenta trocar cada item por outro de fora; fica com a troca se a build melhorar.
        //    (Pega combinações que a montagem passo a passo não enxerga, como crítico + dano crítico.)
        repeat(2) {
            for (i in set.indices) {
                val others = set.filterIndexed { k, _ -> k != i }
                var best = set[i]
                var bestVal = value(set)
                for (c in candidates) {
                    if (c in set || !allowed(p, c, models[c.id], d.myLane, others)) continue
                    val v = value(others + c)
                    if (v > bestVal * 1.003) {
                        bestVal = v
                        best = c
                    }
                }
                set[i] = best
            }
        }
        // 3) A ordem de compra: dos itens escolhidos, primeiro o que mais ajuda no nível em que
        //    você teria ouro para ele (o 1º item por volta do nível 7, o 5º no nível 15).
        val chosen = ArrayList<Item>()
        val picks = ArrayList<BuildPick>()
        val left = ArrayList(set)
        for (step in 1..set.size) {
            val level = 5 + 2 * step
            val stepCtx = context(p, d, level, step)
            val before = score(p, level, chosen, stepCtx)
            var best: Item? = null
            var bestVal = 0.0
            var bestScore: Score? = null
            for (c in left) {
                // um item de grupo exclusivo já foi validado no conjunto final
                val sc = score(p, level, chosen + c, stepCtx)
                val v = sc.total / (c.price / 3000.0).pow(0.25)
                if (v > bestVal) {
                    bestVal = v
                    best = c
                    bestScore = sc
                }
            }
            val pick = best ?: break
            picks.add(BuildPick(pick, reasons(p, pick, before, bestScore!!, stepCtx)))
            chosen.add(pick)
            left.remove(pick)
        }

        // botas: a que mais melhora a build completa
        val base = score(p, 15, chosen, ctx)
        var boots: Item? = null
        var bootScore: Score? = null
        for (b in db.items) {
            val m = models[b.id] ?: continue
            if (!m.boot) continue
            if (b.values.containsKey("mana") && !p.usesMana) continue
            if (b.id == "2409" && p.champ.dmg == Dmg.AD) continue
            if (b.id == "2410" && p.champ.dmg == Dmg.AP) continue
            if (b.id == "2403" && p.autoUptime < 0.6) continue
            val sc = score(p, 15, chosen + b, ctx)
            if (bootScore == null || sc.total > bootScore.total) {
                boots = b
                bootScore = sc
            }
        }
        val bootPick = boots?.let { BuildPick(it, reasons(p, it, base, bootScore!!, ctx)) }
        val upgrade = boots?.let { models[it.id]?.up }?.let { db.item(it) }

        if (!ctx.known) notes.add("Ainda não sei quem são os inimigos: esta é a build geral do campeão. Ela se ajusta quando o time inimigo aparecer.")
        if (d.myLane == Lane.SUP) notes.add("Suporte: comece pelo item inicial de suporte (Foice da Névoa Negra para dano, Baluarte da Montanha para resistência).")
        if (p.hero.skills.count { s -> s.slot > 0 && s.effects.any { it.raw == null && it.kind.startsWith("Dano") } } <= 1 && p.arch != Arch.ENCHANTER && p.arch != Arch.TANK_SUPPORT)
            notes.add("Aviso: a base oficial tem poucas fórmulas de dano legíveis para ${me.name}, então a conta dele pesa mais os ataques básicos do que deveria.")

        // runas e feitiços, calculados em cima desta build
        val re = runeEngine ?: return BuildResult(bootPick, upgrade, picks, notes)
        val page = re.runes(this, p, d, chosen, boots)
        val (spells, alt) = re.spells(p, d, ctx)
        return BuildResult(bootPick, upgrade, picks, notes, page, spells, alt)
    }

    /** Explica em poucas palavras por que o item entrou. */
    private fun reasons(p: Profile, it: Item, before: Score, after: Score, ctx: Ctx): List<String> {
        val out = ArrayList<String>()
        val dOff = (after.off / max(1.0, before.off) - 1) * 100
        val dDef = (after.def / max(1.0, before.def) - 1) * 100
        if (dOff >= 3) out.add("+${dOff.toInt()}% de " + if (p.arch == Arch.ENCHANTER) "cura e escudo" else "dano")
        if (dDef >= 3) out.add("+${dDef.toInt()}% de resistência")
        val m = models[it.id]
        val fx = m?.fx?.map { f -> f.t } ?: emptyList()
        fun names(l: List<String>) = l.take(3).joinToString(", ")
        if ("gw" in fx && ctx.healers.isNotEmpty()) out.add("corta a cura de ${names(ctx.healers)}")
        if ("antishield" in fx && ctx.shielders.isNotEmpty()) out.add("reduz os escudos de ${names(ctx.shielders)}")
        if ("spellshield" in fx && ctx.ccCount > 0) out.add("bloqueia uma habilidade de ${names(ctx.ccNames)}")
        if ("stasis" in fx && ctx.divers.isNotEmpty()) out.add("estase contra ${names(ctx.divers)}")
        if ("revive" in fx) out.add("segunda vida")
        if ("cleanse" in fx && ctx.ccCount > 0) out.add("remove o controle de ${names(ctx.ccNames)}")
        if ((it.values["tenacity%"] ?: 0.0) > 0 && ctx.ccCount >= 2) out.add("tenacidade contra ${names(ctx.ccNames)}")
        if (((it.values["armorPen%"] ?: 0.0) > 0 || "shred" in fx || fx.contains("amp") && m?.fx?.any { f -> f.str("cond") == "bonushp" } == true) && ctx.tanks.isNotEmpty())
            out.add("fura a resistência de ${names(ctx.tanks)}")
        if ((it.values["magicPen%"] ?: 0.0) > 0 && ctx.tanks.isNotEmpty() && "shred" !in fx) out.add("fura a resistência mágica de ${names(ctx.tanks)}")
        if ("ally" in fx) out.add("ajuda o time")
        if (out.isEmpty()) out.add("melhor ganho geral neste ponto da build")
        return out.take(3)
    }

    /** Tradução da passiva, para a tela da base de dados. */
    fun passive(itemId: String): ItemModel? = models[itemId]

    companion object {
        /** quanto vale, nas contas, um item fora do estilo do campeão */
        private const val OFF_STYLE = 0.82

        /** lutadores que montam crítico */
        private val CRIT_FIGHTERS = setOf("Yasuo", "Yone", "Tryndamere", "Graves")

        /** campeões cuja passiva dobra a chance de crítico */
        private val DOUBLE_CRIT = setOf("Yasuo", "Yone")

        /** estilo dos itens que a regra geral (pelos atributos) não acerta */
        private val STYLES: Map<String, Set<String>> = mapOf(
            "2238" to setOf("crit", "onhit"), "2285" to setOf("onhit"), "2291" to setOf("onhit", "aponhit"),
            "2240" to setOf("onhit", "aponhit", "crit"), "2242" to setOf("onhit"), "2266" to setOf("onhit"),
            "2292" to setOf("onhit"), "2244" to setOf("bruiser", "onhit"), "2243" to setOf("crit", "bruiser", "lethality"),
            "2259" to setOf("crit", "bruiser", "lethality", "onhit"), "2246" to setOf("bruiser", "lethality"),
            "2297" to setOf("crit", "bruiser", "lethality", "onhit"), "2257" to setOf("bruiser", "lethality"),
            "2262" to setOf("lethality", "bruiser"), "2269" to setOf("lethality", "bruiser"), "2281" to setOf("bruiser"),
            "2286" to setOf("lethality", "bruiser"), "2252" to setOf("bruiser", "lethality"), "2289" to setOf("bruiser"),
            "2350" to setOf("bruiser"), "2806" to setOf("bruiser", "onhit"), "2378" to setOf("bruiser"),
            "2367" to setOf("bruiser", "tank"), "2375" to setOf("bruiser"), "2294" to setOf("bruiser"), "2272" to setOf("bruiser"),
            "2287" to setOf("bruiser"), "2293" to setOf("bruiser"), "2161" to setOf("lethality"), "2280" to setOf("crit", "lethality"),
            "2151" to setOf("aponhit"), "2185" to setOf("aponhit", "battlemage"), "2143" to setOf("mage", "aponhit"),
            "2386" to setOf("mage", "battlemage", "aponhit"), "2186" to setOf("mage", "battlemage", "aponhit"),
            "2150" to setOf("tank", "bruiser"), "2179" to setOf("tanksupport", "enchanter"),
            "2380" to setOf("tanksupport"), "2379" to setOf("tanksupport"), "2372" to setOf("tanksupport"), "2374" to setOf("tanksupport"),
            "2377" to setOf("tank", "tanksupport"), "2704" to setOf("tank", "tanksupport", "battlemage"),
            "2611" to setOf("enchanter"), "2163" to setOf("enchanter"), "2188" to setOf("enchanter"), "2118" to setOf("enchanter"),
            "2187" to setOf("mage", "enchanter")
        )

        /** campeões que a classe oficial indica como "à distância", mas lutam corpo a corpo (e o contrário) */
        private val MELEE = setOf("Galio", "Sylas", "Gragas", "Rakan", "Nilah", "Lillia")
        private val RANGED = setOf("Urgot", "Graves", "Jayce", "Gnar", "Nidalee", "Thresh")

        /** quanto do tempo de luta o campeão passa atacando (quando difere do normal da classe) */
        private val AUTO_UPTIME = mapOf(
            "Kayle" to 0.9, "Teemo" to 0.7, "Gwen" to 0.8, "MasterYi" to 0.9, "Tryndamere" to 0.9, "Jax" to 0.8,
            "Yasuo" to 0.85, "Yone" to 0.85, "Irelia" to 0.8, "Fiora" to 0.8, "Nasus" to 0.7, "Viego" to 0.8,
            "Graves" to 0.8, "Warwick" to 0.8, "Olaf" to 0.8, "XinZhao" to 0.8, "Volibear" to 0.7, "Shyvana" to 0.7,
            "Nocturne" to 0.8, "Kennen" to 0.4, "Diana" to 0.6, "Camille" to 0.7, "Sett" to 0.7, "Darius" to 0.7
        )
    }
}
