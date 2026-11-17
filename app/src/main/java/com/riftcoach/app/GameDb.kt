package com.riftcoach.app

import org.json.JSONObject

/*
 * A "enciclopédia": todos os números do jogo, lidos da base oficial (lolm.qq.com).
 * Nada aqui depende do Android. Os textos originais vêm em chinês; os números são extraídos deles.
 */

/** Um multiplicador: "+45% AP". `stat` vazio = o texto oficial não diz de qual atributo (deduzido). */
class Ratio(val stat: String, val pct: List<Double>, val guessed: Boolean)

/** Um efeito numérico de uma habilidade: dano, cura ou escudo, com valor base por nível e multiplicadores. */
class Effect(
    val kind: String,
    val base: List<Double>,
    val ratios: List<Ratio>,
    val levelScaled: Boolean,
    /** fórmula original, quando não deu para interpretar por completo */
    val raw: String?
) {
    fun describe(): String {
        val sb = StringBuilder(kind).append(": ")
        if (raw != null) return sb.append(raw).toString()
        val parts = ArrayList<String>()
        if (base.any { it != 0.0 }) parts.add(GameDb.join(base))
        for (r in ratios) parts.add(GameDb.join(r.pct) + "% " + r.stat + if (r.guessed) "*" else "")
        if (levelScaled) parts.add("valor que cresce com o nível")
        return sb.append(parts.joinToString(" + ")).toString()
    }
}

class Skill(
    val slot: Int,
    val nameCn: String,
    val ranks: Int,
    /** tabela oficial de valores por nível: rótulo em português -> "9/8/8/7" */
    val table: List<Pair<String, String>>,
    val effects: List<Effect>,
    val textCn: String
) {
    val slotLabel: String
        get() = when (slot) {
            0 -> "Passiva"
            4 -> "Ultimate"
            else -> "Habilidade $slot"
        }
}

class HeroDetail(
    val id: String,
    /** classe oficial do campeão, em chinês: 战士 lutador, 法师 mago, 刺客 assassino, 坦克 tanque, 射手 atirador, 辅助 suporte */
    val role: String,
    val hp: Double, val hpPer: Double,
    val mp: Double, val mpPer: Double,
    val ad: Double, val adPer: Double,
    val armor: Double, val armorPer: Double,
    val mr: Double, val mrPer: Double,
    val hpRegen: Double, val hpRegenPer: Double,
    val moveSpeed: Double,
    val attackSpeedIndex: Double, val attackSpeedPer: Double,
    val critDamage: Double,
    /** fator de crescimento de cada nível (o nível 1 é 0) */
    val growth: DoubleArray,
    val skills: List<Skill>
) {
    /** Valor de um atributo no nível dado: base + crescimento por nível x soma dos fatores até o nível. */
    fun at(base: Double, per: Double, level: Int): Double {
        var f = 0.0
        for (i in 1 until level.coerceIn(1, growth.size)) f += growth[i]
        return base + per * f
    }
}

class Item(
    val id: String,
    val name: String,
    val nameCn: String,
    val price: Int,
    /** 1 = básico, 2 = intermediário, 3 = completo */
    val tier: Int,
    val type: String,
    /** atributo em português -> texto do valor ("75", "25%") */
    val stats: List<Pair<String, String>>,
    /** os mesmos atributos como número, para as contas: chave interna -> valor */
    val values: Map<String, Double>,
    val from: List<String>,
    val textCn: String,
    /** endereço do ícone do item no site oficial */
    val icon: String
) {
    val nameUnsure get() = name.endsWith("(?)")
}

/**
 * Uma runa. Página de runas do Wild Rift (versão 7.x): 1 runa essencial + 1 runa em cada uma das
 * 3 linhas (`slot` 0, 1, 2) de um caminho principal + 1 runa de outro caminho, de qualquer linha.
 * `path` é o caminho em inglês, como vem da base (PRECISION, DOMINATION, RESOLVE, SORCERY).
 */
class Rune(
    val id: String,
    val name: String,
    val tree: String,
    val keystone: Boolean,
    val textCn: String,
    val icon: String,
    val path: String,
    val slot: Int
) {
    /** runa que continua na base, mas não aparece em nenhuma linha (saiu do jogo) */
    val legacy get() = !keystone && slot < 0
}

/** Feitiço de invocador. `rift` = pode ser usado na Fenda (os outros são só do Abismo Uivante). */
class SummonerSpell(val id: String, val name: String, val textCn: String, val icon: String, val rift: Boolean)

class GameDb(
    val heroes: Map<String, HeroDetail>,
    val items: List<Item>,
    val runes: List<Rune>,
    val spells: List<SummonerSpell>,
    val version: String
) {
    fun item(id: String) = items.firstOrNull { it.id == id }

    companion object {
        fun num(v: Double): String =
            if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

        /** "40/80/120/160", ou só "40" quando todos os níveis são iguais. */
        fun join(list: List<Double>): String =
            if (list.distinct().size <= 1) num(list.firstOrNull() ?: 0.0) else list.joinToString("/") { num(it) }
    }
}

object GameDbParser {

    private val STATS = linkedMapOf(
        "额外攻击力" to "AD bônus", "攻击力" to "AD", "法术强度" to "AP",
        "目标最大生命值" to "vida máx. do alvo", "目标已损失生命值" to "vida perdida do alvo", "目标当前生命值" to "vida atual do alvo",
        "已损失生命值" to "vida perdida", "当前生命值" to "vida atual", "额外生命值" to "vida bônus", "最大生命值" to "vida máx.", "生命值" to "vida",
        "额外护甲" to "armadura bônus", "护甲" to "armadura", "额外魔法抗性" to "RM bônus", "魔法抗性" to "RM",
        "最大法力值" to "mana máx.", "法力值" to "mana", "移动速度" to "vel. de movimento"
    )

    private val KINDS = listOf(
        "物理伤害" to "Dano físico", "魔法伤害" to "Dano mágico", "真实伤害" to "Dano verdadeiro",
        "护盾" to "Escudo", "生命值" to "Cura", "伤害" to "Dano"
    )

    private val NUMBER = Regex("^(\\d+(?:\\.\\d+)?)$")
    private val PERCENT = Regex("^(\\d+(?:\\.\\d+)?)%(.*)$")

    fun parse(heroesJson: String, itemsJson: String, runesJson: String, spellsJson: String, namesJson: String): GameDb {
        val names = JSONObject(namesJson)
        val labels = names.getJSONObject("labels")

        val heroes = HashMap<String, HeroDetail>()
        var version = ""
        val all = JSONObject(heroesJson)
        for (id in all.keys()) {
            val o = all.getJSONObject(id)
            if (version.isEmpty()) version = o.optString("version")
            heroes[id] = parseHero(id, o, labels)
        }

        val itemNames = names.getJSONObject("items")
        val items = ArrayList<Item>()
        val arr = JSONObject(itemsJson).getJSONArray("equipList")
        for (i in 0 until arr.length()) items.add(parseItem(arr.getJSONObject(i), itemNames))

        val runeNames = names.getJSONObject("runes")
        val runes = ArrayList<Rune>()
        val ra = JSONObject(runesJson).getJSONArray("runeList")
        for (i in 0 until ra.length()) {
            val o = ra.getJSONObject(i)
            val id = o.optString("runeId")
            val type = o.optString("type")
            val tree = when {
                type.endsWith("KEY_STONE") -> "Runa essencial"
                type.endsWith("PRECISION") -> "Precisão"
                type.endsWith("DOMINATION") -> "Dominação"
                type.endsWith("SORCERY") -> "Feitiçaria"
                type.endsWith("RESOLVE") -> "Determinação"
                else -> type
            }
            runes.add(
                Rune(
                    id, runeNames.optString(id, o.optString("name")), tree, type.endsWith("KEY_STONE"), o.optString("detailInfo"),
                    o.optString("iconPath"), type.removePrefix("RUNE_PATH_"), o.optInt("primarySlotIndex", -1)
                )
            )
        }

        val spellNames = names.getJSONObject("spells")
        val spells = ArrayList<SummonerSpell>()
        val sa = JSONObject(spellsJson).getJSONArray("skillList")
        for (i in 0 until sa.length()) {
            val o = sa.getJSONObject(i)
            val id = o.optString("skillId")
            spells.add(
                SummonerSpell(
                    id, spellNames.optString(id, o.optString("name")), o.optString("funcDesc"),
                    o.optString("iconPath"), o.optString("mode").contains("召唤师峡谷")
                )
            )
        }
        return GameDb(heroes, items, runes, spells, version)
    }

    private fun d(o: JSONObject, key: String, div: Double = 10000.0) = o.optDouble(key, 0.0) / div

    private fun parseHero(id: String, root: JSONObject, labels: JSONObject): HeroDetail {
        val h = root.getJSONObject("hero")
        val mage = h.optJSONArray("roles")?.let { r -> (0 until r.length()).any { r.optString(it) == "法师" || r.optString(it) == "辅助" } } ?: false
        val growth = DoubleArray(15) { d(h, "growthfix${it + 1}") }
        val skills = ArrayList<Skill>()
        val sp = root.optJSONArray("spells")
        if (sp != null) {
            var active = 0
            for (i in 0 until sp.length()) {
                val s = sp.getJSONObject(i)
                val slot = if (s.optString("spellKey") == "passive") 0 else ++active
                val texts = ArrayList<String>()
                s.optJSONArray("detail")?.let { a -> for (k in 0 until a.length()) texts.add(a.optString(k)) }
                val table = ArrayList<Pair<String, String>>()
                for (k in 1..20) {
                    val type = s.optString("variType$k")
                    val value = s.optString("variValue$k")
                    if (type.isEmpty() || value.isEmpty()) continue
                    if (value.split('/').all { it == "0" }) continue
                    table.add(labels.optString(type, type) to value)
                }
                skills.add(Skill(slot, s.optString("name"), texts.size, table, effects(texts, mage), texts.firstOrNull() ?: ""))
            }
        }
        return HeroDetail(
            id, h.optJSONArray("roles")?.optString(0) ?: "",
            d(h, "hp"), d(h, "hpperlevel"), d(h, "mp"), d(h, "mpperlevel"),
            d(h, "attack"), d(h, "attackperlevel"), d(h, "armor"), d(h, "armorperlevel"),
            d(h, "spellblock"), d(h, "spellblockperlevel"), d(h, "hpregen"), d(h, "hpregenperlevel"),
            d(h, "movespeed", 100.0), d(h, "attackspeed", 100.0), d(h, "attackspeedperlevel", 100.0),
            d(h, "crit", 100.0), growth, skills
        )
    }

    // ------------------------------------------------- fórmulas das habilidades

    private class Formula(val kind: String, val base: Double, val ratios: List<Pair<String, Double>>, val guessed: Set<String>, val scaled: Boolean, val raw: String?)

    /** Acha, no texto de um nível, cada "（40+40%攻击力）" e o tipo de efeito escrito logo antes. */
    private fun formulas(text: String, mage: Boolean): List<Formula> {
        val out = ArrayList<Formula>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '（' && ch != '(') {
                i++
                continue
            }
            var depth = 1
            var j = i + 1
            while (j < text.length && depth > 0) {
                val c = text[j]
                if (c == '（' || c == '(') depth++
                if (c == '）' || c == ')') depth--
                j++
            }
            val content = text.substring(i + 1, (j - 1).coerceAtLeast(i + 1)).trim()
            val prefix = text.substring((i - 16).coerceAtLeast(0), i)
            i = j
            if (!content.any { it.isDigit() } || !content.contains('%')) continue
            // vale a palavra que termina mais perto do parêntese; no empate, a mais específica (vem antes na lista)
            var kind = "Valor"
            var end = -1
            for ((cn, pt) in KINDS) {
                val p = prefix.lastIndexOf(cn)
                if (p >= 0 && p + cn.length > end) {
                    end = p + cn.length
                    kind = pt
                }
            }
            out.add(formula(kind, content, mage))
        }
        return out
    }

    private fun splitTop(s: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in s) {
            if (c == '（' || c == '(') depth++
            if (c == '）' || c == ')') depth--
            if (c == '+' && depth == 0) {
                parts.add(cur.toString().trim())
                cur.setLength(0)
            } else cur.append(c)
        }
        parts.add(cur.toString().trim())
        return parts.filter { it.isNotEmpty() }
    }

    private fun formula(kind: String, content: String, mage: Boolean): Formula {
        var base = 0.0
        val ratios = ArrayList<Pair<String, Double>>()
        val guessed = HashSet<String>()
        var scaled = false
        for (term in splitTop(content)) {
            val n = NUMBER.find(term)
            if (n != null) {
                base += n.groupValues[1].toDouble()
                continue
            }
            if (term.contains("随等级成长") || term.contains('~')) {
                scaled = true
                continue
            }
            val p = PERCENT.find(term) ?: return Formula(kind, 0.0, emptyList(), emptySet(), false, content)
            val rest = p.groupValues[2].trim()
            var stat: String? = null
            if (rest.isEmpty()) {
                // o texto oficial não diz o atributo: mago/dano mágico escala com AP, o resto com AD
                stat = if (mage || kind == "Dano mágico") "AP" else "AD"
                guessed.add(stat)
            } else {
                for ((cn, pt) in STATS) if (rest == cn) stat = pt
            }
            if (stat == null) return Formula(kind, 0.0, emptyList(), emptySet(), false, content)
            ratios.add(stat to p.groupValues[1].toDouble())
        }
        return Formula(kind, base, ratios, guessed, scaled, null)
    }

    /** Junta as fórmulas de todos os níveis da habilidade: "40/80/120/160 + 40% AD". */
    private fun effects(texts: List<String>, mage: Boolean): List<Effect> {
        if (texts.isEmpty()) return emptyList()
        val perRank = texts.map { formulas(it, mage) }
        val first = perRank[0]
        val aligned = perRank.all { r -> r.size == first.size && r.indices.all { r[it].kind == first[it].kind && r[it].ratios.map { p -> p.first } == first[it].ratios.map { p -> p.first } } }
        val out = ArrayList<Effect>()
        for (k in first.indices) {
            val f = first[k]
            if (f.raw != null) {
                out.add(Effect(f.kind, emptyList(), emptyList(), false, f.raw))
                continue
            }
            val ranks = if (aligned) perRank.map { it[k] } else listOf(f)
            if (ranks.any { it.raw != null }) {
                out.add(Effect(f.kind, emptyList(), emptyList(), false, f.raw ?: ranks.first { it.raw != null }.raw))
                continue
            }
            val ratios = f.ratios.indices.map { r -> Ratio(f.ratios[r].first, ranks.map { it.ratios[r].second }, f.ratios[r].first in f.guessed) }
            out.add(Effect(f.kind, ranks.map { it.base }, ratios, f.scaled, null))
        }
        return out
    }

    // -------------------------------------------------------------------- itens

    /** nome do atributo na base -> (chave interna usada nas contas, rótulo em português) */
    private val ITEM_STATS = mapOf(
        "攻击力" to ("ad" to "AD"), "法术强度" to ("ap" to "AP"),
        "最大生命值" to ("hp" to "Vida"), "最大法力值" to ("mana" to "Mana"),
        "护甲" to ("armor" to "Armadura"), "魔法抗性" to ("mr" to "Resistência mágica"),
        "攻击速度" to ("as" to "Velocidade de ataque"), "暴击率" to ("crit" to "Chance de crítico"),
        "技能急速" to ("haste" to "Aceleração de habilidade"), "移动速度" to ("ms" to "Velocidade de movimento"),
        "护甲穿透" to ("armorPen" to "Penetração de armadura"), "护甲穿透率" to ("armorPen" to "Penetração de armadura"),
        "法术穿透" to ("magicPen" to "Penetração mágica"),
        "基础生命回复" to ("hpRegen" to "Regeneração de vida base"), "基础法力回复" to ("manaRegen" to "Regeneração de mana base"),
        "韧性" to ("tenacity" to "Tenacidade")
    )

    private val STAT_LINE = Regex("\\+\\s*(\\d+(?:\\.\\d+)?)(%?)\\s*(.+)$")

    private fun parseItem(o: JSONObject, names: JSONObject): Item {
        val id = o.optString("equipId")
        val full = StringBuilder()
        o.optJSONArray("description")?.let { a -> for (i in 0 until a.length()) full.append(a.optString(i)) }

        // A descrição começa com as linhas de atributos ("攻击力 +75 攻击力"), exatamente como o jogo
        // mostra, e depois de uma linha em branco vêm as passivas. Os atributos saem daqui porque
        // alguns (como aceleração de habilidade) não têm campo próprio na base.
        val stats = ArrayList<Pair<String, String>>()
        val values = HashMap<String, Double>()
        val lines = full.toString().split('\n')
        var n = 0
        while (n < lines.size) {
            val t = lines[n].trim()
            if (t.isEmpty()) break
            val m = STAT_LINE.find(t) ?: break
            val v = m.groupValues[1].toDouble()
            val pct = m.groupValues[2] == "%"
            val cn = m.groupValues[3].trim()
            val known = ITEM_STATS[cn]
            // a mesma chave com "%" no fim quando o valor é porcentagem ("ms" = +45, "ms%" = +4%)
            val key = (known?.first ?: cn) + if (pct) "%" else ""
            values[key] = (values[key] ?: 0.0) + v
            stats.add((known?.second ?: cn) to (GameDb.num(v) + if (pct) "%" else ""))
            n++
        }
        val text = StringBuilder(lines.drop(n).joinToString("\n"))

        val from = ArrayList<String>()
        o.optJSONArray("from")?.let { a -> for (i in 0 until a.length()) from.add(a.optString(i)) }
        val tier = when (o.optString("level")) {
            "初级" -> 1
            "中级" -> 2
            else -> 3
        }
        val type = when (o.optString("type")) {
            "Attack" -> "Ataque"
            "Spell" -> "Magia"
            "Defence" -> "Defesa"
            "Support" -> "Suporte"
            "Active" -> "Botas e ativos"
            else -> "Outros"
        }
        return Item(id, names.optString(id, o.optString("name")), o.optString("name"), o.optInt("price", 0), tier, type, stats, values, from, text.toString().trim(), o.optString("iconPath"))
    }
}
