package com.riftcoach.app

import org.json.JSONObject

/*
 * A "enciclopédia": todos os números do jogo, lidos da base oficial (lolm.qq.com).
 * Nada aqui depende do Android. Os textos originais vêm em chinês; os números são extraídos deles.
 */

/**
 * Um multiplicador: "+45% AP". `guessed` = o texto oficial não diz de qual atributo (deduzido).
 * `grow` = atributos que aumentam o próprio percentual, como em "15% (15%+0,12% AD bônus) da vida
 * perdida do alvo". O número de `grow` fica exatamente como está no texto: a base mistura "por ponto"
 * (0,12%) e "por 100 pontos" (5%), e não diz qual é qual.
 */
class Ratio(val stat: String, val pct: List<Double>, val guessed: Boolean, val grow: List<Ratio> = emptyList())

/**
 * Condição escrita no texto para o efeito acontecer:
 * - "acertos": a cada `n` acertos (`sameTarget` = no mesmo alvo);
 * - "intervalo": a cada `n` segundos, como uma recarga ("每隔24秒强化下一次普攻");
 * - "tique": repete a cada `n` segundos enquanto dura ("每隔0.75秒…造成", "每秒造成");
 * - "vidaBaixa": alvo abaixo de `n`% da vida.
 * O BuildEngine ainda não usa.
 */
class Trigger(val type: String, val n: Double, val sameTarget: Boolean = false) {
    fun describe(): String = when (type) {
        "acertos" -> "a cada ${GameDb.num(n)} acertos" + if (sameTarget) " no mesmo alvo" else ""
        "intervalo" -> "a cada ${GameDb.num(n)} s, como recarga"
        "tique" -> "a cada ${GameDb.num(n)} s, por tique"
        "vidaBaixa" -> "alvo abaixo de ${GameDb.num(n)}% da vida"
        else -> type
    }
}

/** Um efeito numérico de uma habilidade: dano, cura ou escudo, com valor base por nível e multiplicadores. */
class Effect(
    val kind: String,
    val base: List<Double>,
    val ratios: List<Ratio>,
    val levelScaled: Boolean,
    /** fórmula original, quando não deu para interpretar por completo */
    val raw: String?,
    /**
     * valor máximo, quando o texto dá uma faixa ("25 a 250 conforme a distância"); `base`/`ratios` ficam
     * com o mínimo. O BuildEngine ainda não usa.
     */
    val upTo: Effect? = null,
    /** condição para o efeito acontecer (ver [Trigger]); o BuildEngine ainda não usa */
    val trigger: Trigger? = null
) {
    fun describe(): String {
        val sb = StringBuilder(kind).append(": ")
        if (raw != null) return sb.append(raw).toString()
        sb.append(body())
        upTo?.let { sb.append(" até ").append(it.body()) }
        trigger?.let { sb.append(" (").append(it.describe()).append(")") }
        return sb.toString()
    }

    private fun body(): String {
        val parts = ArrayList<String>()
        if (base.any { it != 0.0 }) parts.add(GameDb.join(base))
        for (r in ratios) {
            var s = GameDb.join(r.pct) + "% " + r.stat + if (r.guessed) "*" else ""
            if (r.grow.isNotEmpty()) s += " [% cresce " + r.grow.joinToString(" ") { "+" + GameDb.join(it.pct) + "% " + it.stat + if (it.guessed) "*" else "" } + "]"
            parts.add(s)
        }
        if (levelScaled) parts.add("valor que cresce com o nível")
        return parts.joinToString(" + ")
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
            if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

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
        // herói cujo texto fala em "额外法力值" (Ryze): o "额外" sem substantivo é mana bônus, não vida bônus
        val bonusMana = sp != null && (0 until sp.length()).any { i ->
            sp.getJSONObject(i).optJSONArray("detail")?.let { a -> (0 until a.length()).any { a.optString(it).contains("额外法力") } } ?: false
        }
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
                skills.add(Skill(slot, s.optString("name"), texts.size, table, effects(texts, mage, bonusMana), texts.firstOrNull() ?: ""))
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

    /** Um termo "40% AD". `grow` = atributos que aumentam o próprio percentual (ver [Ratio]). */
    private class Term(val stat: String, val pct: Double, val guessed: Boolean, val grow: List<Term> = emptyList())

    private class Formula(
        /** posição no texto, para manter a ordem em que os efeitos aparecem */
        val pos: Int,
        val kind: String, val base: Double, val terms: List<Term>, val scaled: Boolean, val raw: String?,
        val upTo: Formula? = null, val trigger: Trigger? = null,
        /** começo e fim do parêntese da fórmula (-1 = escrita fora de parênteses) */
        val open: Int = -1, val close: Int = -1
    ) {
        fun copy(kind: String = this.kind, terms: List<Term> = this.terms, upTo: Formula? = this.upTo, trigger: Trigger? = this.trigger, open: Int = this.open, close: Int = this.close) =
            Formula(pos, kind, base, terms, scaled, raw, upTo, trigger, open, close)

        /** só "随等级成长" ou "0% ~ 60%", sem número que dê para usar */
        val empty: Boolean get() = raw == null && base == 0.0 && terms.isEmpty()

        /** o que tem que ser igual em todos os níveis da habilidade para juntar os números */
        val shape: String
            get() = kind + "|" + terms.joinToString(",") { t -> t.stat + t.grow.joinToString("") { "+" + it.stat } } +
                "|" + (upTo?.shape ?: "") + "|" + (trigger?.type ?: "")
    }

    /** dados do herói e do texto que mudam a leitura de um termo sem atributo */
    private class Ctx(val mage: Boolean, val bonusMana: Boolean, val apText: Boolean)

    private val SPACES = Regex("\\s")
    private val PCT = Regex("(\\d+(?:\\.\\d+)?)%")
    /** primeiro termo de uma fórmula que é só uma porcentagem ("15%") ou só um número ("8") */
    private val PLAIN = Regex("^(\\d+(?:\\.\\d+)?)%?$")
    /** "额外25%攻击力" é o mesmo que "25%额外攻击力" */
    private val EXTRA_FIRST = Regex("^额外(\\d+(?:\\.\\d+)?%)(.+)$")

    // de quem é a vida: do alvo (目标, 其 = "dele", 对方, 他们) ou do próprio campeão (自身)
    private const val QUAL = "(目标|其|对方|被压制目标|他们|自身)?"
    private const val HP = "(最大生命值|已损失生命值|损失生命值|当前生命值|额外生命值)"
    private const val LVL = "([（(]随等级成长[)）])?"
    private const val DMG = "的?(?:额外)?(物理伤害|魔法伤害|真实伤害|伤害)"

    /** atributo escrito antes da porcentagem: "目标已损失生命值15%" */
    private val STAT_BEFORE = Regex(QUAL + "的?" + HP + "的?$")
    /** atributo escrito depois da porcentagem: "15%目标最大生命值" */
    private val STAT_AFTER = Regex("^的?" + QUAL + "的?" + HP)
    private val QUAL_END = Regex(QUAL + "的?$")
    /** valor fixo somado antes: "150加上目标已损失生命值15%", "12外加1%目标最大生命值", "50+8%（…）目标最大生命值" */
    private val BASE_BEFORE = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:加上|外加|\\+)(?:相当于)?$")
    /** "相当于他们10%最大生命值加上0(100%额外攻击力)的魔法伤害": % de vida escrito antes de uma fórmula */
    private val HP_PLUS = Regex(QUAL + "的?(\\d+(?:\\.\\d+)?)%的?" + HP + "加上\\s*[\\d.]*\\s*$")
    /** "（7% - 22%随等级成长）": porcentagem que cresce com o nível, do nível 1 ao máximo */
    private val LVL_RANGE = Regex("^(\\d+(?:\\.\\d+)?)%[-~](\\d+(?:\\.\\d+)?)%随等级成长$")
    /** "A（…）至B（…）": o segundo parêntese é o máximo da faixa */
    private val RANGE_GAP = Regex("^\\s*(?:至|到|~)\\s*[\\d.]*\\s*的?(?:物理伤害|魔法伤害|真实伤害|伤害)?\\s*$")
    /** "…，最多提升至回复60生命值（…）" */
    private val MAX_GAP = Regex("最多提升至[^，。；（(]*$")
    /** "30（…）外加2%目标最大生命值（…）": a segunda parte é do mesmo golpe */
    private val PLUS_GAP = Regex("^\\s*(?:外加|加上)\\s*$")
    /** "总伤害为20加上目标9%最大生命值" */
    private val OUT_TOTAL = Regex("伤害为\\s*(\\d+(?:\\.\\d+)?)\\s*加上" + QUAL + "的?(\\d+(?:\\.\\d+)?)%的?" + HP)
    /** "治疗自身最大生命值的9% ~ 18%（随等级成长）" */
    private val OUT_HEAL_LVL = Regex("(?:治疗|回复)" + QUAL + "的?" + HP + "的?(\\d+(?:\\.\\d+)?)%\\s*[~-]\\s*(\\d+(?:\\.\\d+)?)%\\s*[（(]随等级成长[)）]")
    private val PARENS = Regex("[（(][^（()）]*[)）]")

    /** dano escrito fora dos parênteses: "相当于目标最大生命值8%的魔法伤害" */
    private val OUT_STAT_PCT = Regex(QUAL + "的?" + HP + "的?" + "(\\d+(?:\\.\\d+)?)%" + LVL + DMG)
    /** ... e na outra ordem: "相当于其6%最大生命值的真实伤害" */
    private val OUT_PCT_STAT = Regex(QUAL + "的?" + "(\\d+(?:\\.\\d+)?)%" + LVL + "的?" + QUAL + "的?" + HP + LVL + DMG)
    private val DMG_PT = mapOf("物理伤害" to "Dano físico", "魔法伤害" to "Dano mágico", "真实伤害" to "Dano verdadeiro", "伤害" to "Dano")

    /** "可吸收70伤害（…）", "可吸收70魔法伤害（…）", "免受25伤害（…）", "可吸收的伤害提升至50（…）" = escudo */
    private val SHIELD = Regex("(吸收|抵挡|免受)(?:额外)?的?[\\d.]*(?:物理/魔法|物理|魔法|真实)?伤害(?:增加[\\d.]*|提升至[\\d.]*)?\\s*$")
    /** "护盾值会在第三颗法球引爆时增加50（…）" = escudo */
    private val SHIELD_MORE = Regex("护盾值?[^，。（(]*(?:增加|提升至)\\s*[\\d.]*\\s*$")
    /** "所受魔法伤害减少25%（…）", "受到的伤害减免60%（…）" = redução de dano, não dano */
    private val REDUCTION = Regex("伤害(?:减少|减免)\\s*[\\d.]*%?\\s*$")
    /** "回复效果将提升至100（…）" = cura */
    private val HEAL_MORE = Regex("(?:回复|治疗)[^，。（(]*提升至\\s*[\\d.]*\\s*$")
    /** "生命值" só é cura com um verbo de cura ("回复80生命值"); "获得250额外生命值" é vida a mais */
    private val HEAL_VERB = Regex("回复|治疗|恢复")
    /** entre a palavra de tipo e o parêntese há outra coisa ("额外提供10攻击力（…）", "伤害储存10%（…）", "，获得5%（…）") */
    private val BUFF = Regex("攻击力|攻击速度|移速|移动速度|储存|护甲|魔法抗性|法力|韧性|获得")
    private val CAP = Regex("上限|最多|最高")
    /** tipo de efeito que não entra na luta contra campeões */
    const val MONSTERS = "Limite vs. monstros"
    /** "（…）的生命值。" = cura; "（…）生命值的敌方英雄" (limite de execução) não */
    private val AFTER_HEAL = Regex("^\\s*的?(生命值|治疗)(?!的)")
    private val AFTER_KIND = Regex("物理伤害|魔法伤害|真实伤害|伤害的护盾|护盾|伤害(?!值|等同)")

    // gatilhos ("a cada 3 acertos" etc.)
    /**
     * "每第三次普攻", "连续进行的第3次普通攻击", "连续三次", "每完成6次普攻", "每命中同一敌人3次",
     * "对同一个目标造成3次伤害" (não "连续挥击3次" = 3 golpes de uma vez, nem "第二次打击")
     */
    private val HITS = Regex(
        "第\\s*([2-9二两三四五六])\\s*次(?:普攻|普通攻击|攻击|命中)|连续(?:进行的)?\\s*([2-9二两三四五六])\\s*次" +
            "|每(?:完成|使用|命中)[^，。；]{0,6}?([2-9二三四五六])\\s*次|同一个?目标造成\\s*([2-9二三四五六])\\s*次"
    )
    private val SAME_TARGET = Regex("同一|同个|相同|连续")
    private val LOW_HP = Regex("生命值低于\\s*(\\d+(?:\\.\\d+)?)%")
    /** "每隔24秒强化下一次普攻" (recarga) × "每隔0.75秒会扩散出一道音波" (tique) */
    private val EVERY = Regex("每(?:隔|过)\\s*(\\d+(?:\\.\\d+)?)\\s*秒(.{0,8})")
    private val PER_SECOND = Regex("每秒(?:对[^，。]{0,10})?(?:造成|回复|为)")
    private val CN_DIGIT = mapOf('二' to 2.0, '两' to 2.0, '三' to 3.0, '四' to 4.0, '五' to 5.0, '六' to 6.0)

    /**
     * Acha, no texto de um nível, cada "（40+40%攻击力）" e o tipo de efeito escrito perto dele, e também
     * o dano escrito fora dos parênteses ("相当于其6%最大生命值的真实伤害").
     */
    private fun formulas(text0: String, mage: Boolean, bonusMana: Boolean): List<Formula> {
        val text = text0.replace('％', '%')
        val ctx = Ctx(mage, bonusMana, text.contains("法术强度") || text.contains("魔法伤害"))
        val out = ArrayList<Formula>()
        val inside = BooleanArray(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '（' && ch != '(') {
                i++
                continue
            }
            val j = close(text, i)
            for (k in i until j) inside[k] = true
            val content = text.substring(i + 1, (j - 1).coerceAtLeast(i + 1)).trim()
            val open = i
            i = j
            if (!content.any { it.isDigit() } || !content.contains('%')) continue
            out.add(parenFormula(text, open, j, content, ctx))
        }
        joinParens(text, out)
        outside(text, inside, out)
        // parêntese só com "随等级成長"/"0% ~ 60%": não tem número que sirva
        out.removeAll { it.empty }
        out.sortBy { it.pos }
        return out
    }

    /**
     * Junta parênteses vizinhos que são o mesmo golpe: "79（…）至187（…）" e "…最多提升至回复60生命值（…）"
     * viram um efeito com mínimo e máximo; em "30（…）外加2%目标最大生命值（…）" a segunda parte herda o tipo.
     */
    private fun joinParens(text: String, list: MutableList<Formula>) {
        var k = 1
        while (k < list.size) {
            val a = list[k - 1]
            val b = list[k]
            if (a.close < 0 || b.open < a.close || a.raw != null || b.raw != null) {
                k++
                continue
            }
            val gap = text.substring(a.close, b.open)
            if (a.upTo == null && b.upTo == null && a.terms.map { it.stat } == b.terms.map { it.stat } &&
                (RANGE_GAP.matches(gap) || (MAX_GAP.containsMatchIn(gap) && a.kind == b.kind))
            ) {
                list[k - 1] = a.copy(kind = if (a.kind == "Valor") b.kind else a.kind, upTo = b)
                list.removeAt(k)
                continue
            }
            if (b.kind == "Valor" && a.kind != "Valor" && b.pos >= a.close && PLUS_GAP.matches(text.substring(a.close, b.pos)))
                list[k] = b.copy(kind = a.kind)
            k++
        }
    }

    /** posição logo depois do parêntese que fecha o que abre em `i` */
    private fun close(text: String, i: Int): Int {
        var depth = 1
        var j = i + 1
        while (j < text.length && depth > 0) {
            val c = text[j]
            if (c == '（' || c == '(') depth++
            if (c == '）' || c == ')') depth--
            j++
        }
        return j
    }

    /** começo da frase em que está a posição `i` (depois do último "。", "；", "：" ou "·") */
    private fun clauseStart(text: String, i: Int): Int {
        var k = i - 1
        while (k >= 0 && text[k] !in "。；;：:\n·") k--
        return k + 1
    }

    /**
     * A palavra de tipo que termina mais perto do fim de `s` (tipo, onde termina, palavra em chinês); no
     * empate, a mais específica (vem antes na lista). "伤害值相当于…"/"伤害等同于…" não dizem o tipo.
     */
    private fun closestKind(s: String): Triple<String, Int, String>? {
        var best: Triple<String, Int, String>? = null
        for ((cn, pt) in KINDS) {
            var p = s.lastIndexOf(cn)
            while (p >= 0 && cn.endsWith("伤害") && (s.startsWith("值", p + cn.length) || s.startsWith("等同", p + cn.length)))
                p = if (p == 0) -1 else s.lastIndexOf(cn, p - 1)
            if (p >= 0 && p + cn.length > (best?.second ?: -1)) best = Triple(pt, p + cn.length, cn)
        }
        return best
    }

    /** o tipo achado, mas "生命值" só é cura com verbo de cura na frase ("回复80生命值"; não "获得250额外生命值") */
    private fun named(c: Triple<String, Int, String>, clause: String): String =
        if (c.third == "生命值" && !HEAL_VERB.containsMatchIn(clause)) "Valor" else c.first

    /** tipo escrito depois do parêntese, na mesma frase: "（15%+0.12%额外攻击力）的真实伤害" */
    private fun kindAfter(text: String, from: Int, clause: String): String? {
        var end = from
        while (end < text.length && end - from < 50 && text[end] !in "。；;，,") end++
        val after = text.substring(from.coerceAtMost(end), end)
        if (AFTER_HEAL.containsMatchIn(after))
            return if (HEAL_VERB.containsMatchIn(clause) || after.trimStart().removePrefix("的").startsWith("治疗")) "Cura" else "Valor"
        return when (AFTER_KIND.find(after)?.value ?: return null) {
            "物理伤害" -> "Dano físico"
            "魔法伤害" -> "Dano mágico"
            "真实伤害" -> "Dano verdadeiro"
            "伤害" -> "Dano"
            else -> "Escudo"
        }
    }

    /**
     * Tipo do efeito de uma fórmula. `phraseStart` = onde começa a fórmula (o parêntese, ou o atributo
     * escrito antes dele); `between` = texto entre o atributo e o parêntese; `after` = logo depois do parêntese.
     */
    private fun kindOf(text: String, clauseStart: Int, phraseStart: Int, between: String, after: Int): String {
        val pre = text.substring(maxOf(clauseStart, phraseStart - 16), phraseStart)
        val clause = text.substring(clauseStart, phraseStart)
        // limite de dano escrito no texto ("对野怪……上限为200（…）"): não é dano em campeão
        if (clause.contains("野怪") && CAP.containsMatchIn(pre)) return if (clause.contains("英雄")) "Limite" else MONSTERS
        val k = baseKind(text, pre, clause, between, after)
        // dano só em tropas/monstros ("对小兵和野怪造成1200真实伤害（…）", "如果目标是野怪，会对其造成…")
        if (k.startsWith("Dano") && monstersOnly(clause)) return MONSTERS
        return k
    }

    private fun monstersOnly(clause: String) = (clause.contains("野怪") || clause.contains("小兵")) && !clause.contains("英雄")

    private fun baseKind(text: String, pre: String, clause: String, between: String, after: Int): String {
        if (REDUCTION.containsMatchIn(pre)) return "Redução de dano"
        if (SHIELD.containsMatchIn(pre) || SHIELD_MORE.containsMatchIn(clause)) return "Escudo"
        if (HEAL_MORE.containsMatchIn(clause)) return "Cura"
        closestKind(between)?.let { return named(it, clause) }
        // o tipo escrito logo antes do parêntese ("造成40物理伤害（…）") vale; se ele está longe, o tipo
        // costuma vir depois ("造成118（…）物理伤害")
        val c = closestKind(pre)
        val a = kindAfter(text, after, clause)
        if (c != null && pre.substring(c.second).all { it.isDigit() || it.isWhitespace() || it in ".%至到-" }) {
            val k = named(c, clause)
            // "进行每秒50生命值（…）的治疗": o verbo de cura vem depois
            return if (k == "Valor" && a == "Cura") a else k
        }
        if (a != null && a != "Dano") return a
        // o tipo de antes, longe, só vale se não há outro atributo no meio ("护盾，并为目标额外提供10攻击力（…）")
        if (c != null && !BUFF.containsMatchIn(pre.substring(c.second))) return named(c, clause)
        return a ?: "Valor"
    }

    private fun trigger(clause0: String): Trigger? {
        // o que está entre parênteses é detalhe de outra coisa ("（若生命值低于12%则无消耗）")
        val clause = clause0.replace(PARENS, "")
        HITS.find(clause)?.let { m ->
            val c = m.groupValues.drop(1).joinToString("").firstOrNull()
            val n = c?.let { if (it.isDigit()) it.digitToInt().toDouble() else CN_DIGIT[it] }
            if (n != null) return Trigger("acertos", n, SAME_TARGET.containsMatchIn(clause))
        }
        LOW_HP.find(clause)?.let { return Trigger("vidaBaixa", it.groupValues[1].toDouble()) }
        EVERY.find(clause)?.let { m ->
            val next = m.groupValues[2]
            return Trigger(if (next.contains("强化") || next.contains("下一次")) "intervalo" else "tique", m.groupValues[1].toDouble())
        }
        if (PER_SECOND.containsMatchIn(clause)) return Trigger("tique", 1.0)
        return null
    }

    /** vida de quem: do alvo, se o texto diz (目标/其…) ou, sem dizer, quando é dano (deduzido) */
    private fun hpStat(hp: String, qual: String, kind: String): Pair<String, Boolean> {
        val target = when (qual) {
            "目标", "其", "对方", "被压制目标", "他们" -> true
            "自身" -> false
            else -> kind.startsWith("Dano")
        }
        val pt = when (hp) {
            "最大生命值" -> "vida máx."
            "已损失生命值", "损失生命值" -> "vida perdida"
            "当前生命值" -> "vida atual"
            else -> "vida bônus"
        }
        return (if (target) "$pt do alvo" else pt) to qual.isEmpty()
    }

    /** "A至B" / "A到B" no nível de cima do parêntese: dano mínimo e máximo */
    private fun splitRange(content: String): Pair<String, String>? {
        var depth = 0
        for ((k, c) in content.withIndex()) {
            if (c == '（' || c == '(') depth++
            if (c == '）' || c == ')') depth--
            if (depth == 0 && (c == '至' || c == '到')) {
                val a = content.substring(0, k).trim()
                val b = content.substring(k + 1).trim()
                return if (a.isNotEmpty() && b.isNotEmpty()) a to b else null
            }
        }
        return null
    }

    /** A porcentagem do parêntese é de um atributo escrito fora dele ("目标已损失生命值15%（15%+…）"). */
    private class Nest(val start: Int, val end: Int, val hp: String, val qual: String, val base: Double)

    private fun nest(text: String, clauseStart: Int, i: Int, j: Int, content: String): Nest? {
        val first = splitTop(content).firstOrNull()?.let { normTerm(it) } ?: return null
        val x = PLAIN.find(first)?.groupValues?.get(1)?.toDouble() ?: return null
        val from = maxOf(clauseStart, i - 28)
        val window = text.substring(from, i)
        val pcts = PCT.findAll(window).toList()
        // a mesma porcentagem escrita fora; ou a que está colada no parêntese (o texto às vezes mostra o
        // valor já calculado: "目标1.6%（1%+0.01%攻击力）最大生命值")
        val m = pcts.lastOrNull { it.groupValues[1].toDouble() == x }
            ?: pcts.lastOrNull()?.takeIf { it.range.last == window.trimEnd().length - 1 }
        if (m == null) {
            // só o atributo fora, sem porcentagem: "相当于目标（14%+5%攻击力）已损失生命值的物理伤害"
            if (!first.endsWith("%")) return null
            val c = STAT_AFTER.find(text.substring(j).trimStart()) ?: return null
            val q = QUAL_END.find(window)!!
            if (q.value.isEmpty() && c.groupValues[1].isEmpty()) return null
            return Nest(from + q.range.first, i, c.groupValues[2], c.groupValues[1].ifEmpty { q.groupValues[1] }, 0.0)
        }
        val before = window.substring(0, m.range.first)
        val after = window.substring(m.range.last + 1)
        val start: Int
        val end: Int
        val hp: String
        val qual: String
        val b = STAT_BEFORE.find(before)
        val a = STAT_AFTER.find(after)
        val q = QUAL_END.find(before)!!
        when {
            b != null -> {
                hp = b.groupValues[2]; qual = b.groupValues[1]
                start = from + b.range.first; end = from + m.range.last + 1
            }
            a != null -> {
                hp = a.groupValues[2]; qual = a.groupValues[1].ifEmpty { q.groupValues[1] }
                start = from + q.range.first; end = from + m.range.last + 1 + a.range.last + 1
            }
            after.isBlank() -> {
                // atributo escrito depois do parêntese: "200加上10% （10%+…）目标已损失生命值的物理伤害"
                val c = STAT_AFTER.find(text.substring(j).trimStart()) ?: return null
                hp = c.groupValues[2]; qual = c.groupValues[1].ifEmpty { q.groupValues[1] }
                start = from + q.range.first; end = i
            }
            else -> return null
        }
        val base = BASE_BEFORE.find(text.substring(maxOf(clauseStart, start - 12), start))?.groupValues?.get(1)?.toDouble() ?: 0.0
        return Nest(start, end, hp, qual, base)
    }

    private fun parenFormula(text: String, i: Int, j: Int, content: String, ctx: Ctx): Formula {
        val cs = clauseStart(text, i)
        val range = splitRange(content)
        val left = range?.first ?: content
        val nest = nest(text, cs, i, j, left)
        val start = nest?.start ?: i
        val between = if (nest != null && nest.end < i) text.substring(nest.end, i) else ""
        val kind = kindOf(text, cs, start, between, j)
        val raw = Formula(start, kind, 0.0, emptyList(), false, content, open = i, close = j)
        val window = text.substring(maxOf(cs, i - 28), i)
        // "相当于他们10%最大生命值加上0(100%额外攻击力)": a % de vida escrita antes entra na fórmula
        val hpPlus = if (nest == null) HP_PLUS.find(window) else null
        // "豪意消耗25%（25%+…）", "获得6%额外护盾与治疗强度（6%+…）": a porcentagem é de algo escrito fora que
        // não é atributo conhecido; não dá para adivinhar AD/AP
        if (nest == null && hpPlus == null) {
            val first = splitTop(left).firstOrNull()?.let { normTerm(it) } ?: ""
            val x = PLAIN.find(first)?.groupValues?.get(1)?.toDouble()
            if (x != null && first.endsWith("%") && PCT.findAll(window).any { it.groupValues[1].toDouble() == x }) return raw
        }
        fun one(part: String) = if (nest != null) nested(start, kind, nest, part, ctx) else formula(start, kind, part, ctx)
        var f = one(left)
        val upTo = range?.let { one(it.second) }
        if (f.raw != null || (upTo != null && (upTo.raw != null || upTo.terms.map { it.stat } != f.terms.map { it.stat }))) return raw
        if (hpPlus != null) {
            val (stat, guessed) = hpStat(hpPlus.groupValues[3], hpPlus.groupValues[1], kind)
            f = f.copy(terms = listOf(Term(stat, hpPlus.groupValues[2].toDouble(), guessed)) + f.terms)
        }
        return f.copy(upTo = upTo, trigger = trigger(text.substring(cs, start)), open = i, close = j)
    }

    /** dano escrito sem parênteses: "额外造成相当于其6%最大生命值的真实伤害" */
    private fun outside(text: String, inside: BooleanArray, out: MutableList<Formula>) {
        for (re in listOf(OUT_STAT_PCT, OUT_PCT_STAT)) for (m in re.findAll(text)) {
            val p = m.range.first
            if (inside[p] || out.any { it.pos == p }) continue
            val g = m.groupValues
            val qual: String
            val num: Double
            val lvl: String
            val hp: String
            val dmg: String
            if (re === OUT_STAT_PCT) {
                qual = g[1]; hp = g[2]; num = g[3].toDouble(); lvl = g[4]; dmg = g[5]
            } else {
                qual = g[1].ifEmpty { g[4] }; num = g[2].toDouble(); lvl = g[3] + g[6]; hp = g[5]; dmg = g[7]
            }
            // "0%" = valor que depende de acúmulos (só aparece calculado no jogo)
            if (num == 0.0) continue
            // fim de uma faixa ("6%至9%…"): não dá para saber o valor
            if (p > 0 && text[p - 1] in "至到~-") continue
            val end = m.range.last + 1
            // "吸收100+5%最大生命值伤害的护盾": escudo escrito fora dos parênteses
            val shield = dmg == "伤害" && (text.startsWith("的护盾", end) || text.startsWith("护盾", end))
            var scaled = lvl.isNotEmpty()
            var top: Double? = null
            var k = end
            while (k < text.length && text[k].isWhitespace()) k++
            if (k < text.length && (text[k] == '（' || text[k] == '(')) {
                val c = text.substring(k + 1, (close(text, k) - 1).coerceAtLeast(k + 1))
                val first = splitTop(c).firstOrNull()?.let { normTerm(it) }
                // logo depois vem um parêntese com a mesma porcentagem: quem lê é a fórmula do parêntese
                if (first != null && PLAIN.find(first)?.groupValues?.get(1)?.toDouble() == num) continue
                // "（7% - 22%随等级成长）": do nível 1 ao máximo
                LVL_RANGE.find(c.replace(SPACES, ""))?.let { r ->
                    if (r.groupValues[1].toDouble() == num) {
                        scaled = true
                        top = r.groupValues[2].toDouble()
                    }
                }
            }
            val cs = clauseStart(text, p)
            // dano só contra monstros/tropas
            val clause = text.substring(cs, p)
            if (clause.contains("野怪") || monstersOnly(clause)) continue
            val kind = if (shield) "Escudo" else DMG_PT[dmg] ?: "Dano"
            val base = BASE_BEFORE.find(text.substring(maxOf(cs, p - 12), p))?.groupValues?.get(1)?.toDouble() ?: 0.0
            val (stat, guessed) = hpStat(hp, qual, kind)
            val upTo = top?.let { Formula(p, kind, base, listOf(Term(stat, it, guessed)), false, null) }
            out.add(Formula(p, kind, base, listOf(Term(stat, num, guessed)), scaled, null, upTo, trigger(clause)))
        }
        // "总伤害为20加上目标9%最大生命值" (o tipo vem antes: "造成物理伤害和魔法伤害")
        for (m in OUT_TOTAL.findAll(text)) {
            val p = m.range.first
            if (inside[p]) continue
            val g = m.groupValues
            val (stat, guessed) = hpStat(g[4], g[2], "Dano")
            out.add(Formula(p, "Dano", g[1].toDouble(), listOf(Term(stat, g[3].toDouble(), guessed)), false, null, null, trigger(text.substring(clauseStart(text, p), p))))
        }
        // "治疗自身最大生命值的9% ~ 18%（随等级成长）"
        for (m in OUT_HEAL_LVL.findAll(text)) {
            val p = m.range.first
            if (inside[p]) continue
            val g = m.groupValues
            val (stat, guessed) = hpStat(g[2], g[1], "Cura")
            val upTo = Formula(p, "Cura", 0.0, listOf(Term(stat, g[4].toDouble(), guessed)), false, null)
            out.add(Formula(p, "Cura", 0.0, listOf(Term(stat, g[3].toDouble(), guessed)), true, null, upTo, trigger(text.substring(clauseStart(text, p), p))))
        }
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

    /** tira espaços ("60%额外 攻击力") e põe "额外" depois da porcentagem ("额外25%攻击力" -> "25%额外攻击力") */
    private fun normTerm(t: String): String {
        val s = t.replace(SPACES, "")
        val m = EXTRA_FIRST.find(s) ?: return s
        return m.groupValues[1] + "额外" + m.groupValues[2]
    }

    /** atributo de um termo ("额外攻击力" -> "AD bônus"); o Boolean diz se foi deduzido */
    private fun statOf(rest: String, ctx: Ctx): Pair<String, Boolean>? {
        // "armour_ratio" = armadura; "额外魔法抗性加成" = RM bônus
        var r = rest.replace(SPACES, "").removeSuffix("加成").replace("armour_ratio", "护甲")
        STATS[r]?.let { return it to false }
        if (r.startsWith("自身")) {
            r = r.removePrefix("自身")
            STATS[r]?.let { return it to false }
        }
        return when (r) {
            // sem substantivo: quase sempre é a vida (bônus ou máxima), mas o texto não diz (deduzido);
            // para quem escala com mana bônus (o texto do herói fala em "额外法力值"), é a mana bônus.
            // Dentro do parêntese os termos são atributos de quem usa a habilidade; os do alvo vêm com
            // "目标" ("15%目标最大"). Por isso "6%最大" é a vida máx. do próprio campeão.
            "额外" -> (if (ctx.bonusMana) "mana bônus" else "vida bônus") to true
            "最大" -> "vida máx." to true
            "目标最大" -> "vida máx. do alvo" to true
            "损失生命值" -> "vida perdida" to false
            "额外移动速度" -> "vel. de movimento bônus" to false
            // "30%法力加成" (Blitzcrank)
            "法力" -> "mana máx." to true
            else -> null
        }
    }

    private fun formula(pos: Int, kind: String, content: String, ctx: Ctx): Formula {
        val raw = Formula(pos, kind, 0.0, emptyList(), false, content)
        var base = 0.0
        val terms = ArrayList<Term>()
        var scaled = false
        val parts = splitTop(content).map { normTerm(it) }
        // algum termo diz o atributo ("40%法术强度")
        val named = parts.any { t -> PERCENT.find(t)?.groupValues?.get(2)?.let { it.isNotEmpty() && !it.startsWith("每") } == true }
        for (term in parts) {
            val n = NUMBER.find(term)
            if (n != null) {
                base += n.groupValues[1].toDouble()
                continue
            }
            if (term.contains("随等级成长") || term.contains('~')) {
                scaled = true
                continue
            }
            // "每层黑雾可提供2" (Senna R): parte que depende de acúmulos, fica de fora
            if (term.startsWith("每层")) continue
            val p = PERCENT.find(term) ?: return raw
            val rest = p.groupValues[2]
            val stat = if (rest.isNotEmpty()) {
                statOf(rest, ctx) ?: return raw
            } else when {
                // "（75+40%法术强度+10%）": % sem nome ao lado de atributo nomeado não é AD/AP;
                // em escudo é a vida máx. (deduzido), no resto fica sem interpretar
                named -> if (kind == "Escudo") "vida máx." to true else return raw
                // escudo/cura só com "（8%）": não dá para saber de quê
                (kind == "Escudo" || kind == "Cura") && parts.size == 1 -> return raw
                // escudo/cura sem atributo: AP se a habilidade fala em AP ou dano mágico
                kind == "Escudo" || kind == "Cura" -> (if (ctx.mage || ctx.apText) "AP" else "AD") to true
                // o texto oficial não diz o atributo: mago/dano mágico escala com AP, o resto com AD
                else -> (if (ctx.mage || kind == "Dano mágico") "AP" else "AD") to true
            }
            terms.add(Term(stat.first, p.groupValues[1].toDouble(), stat.second))
        }
        return Formula(pos, kind, base, terms, scaled, null)
    }

    /** "（15%+0.12%额外攻击力）" de um atributo escrito fora: 15% desse atributo, e o 15% cresce com o AD bônus */
    private fun nested(pos: Int, kind: String, nest: Nest, content: String, ctx: Ctx): Formula {
        val terms = splitTop(content).map { normTerm(it) }
        val x = PLAIN.find(terms[0])?.groupValues?.get(1)?.toDouble() ?: return Formula(pos, kind, 0.0, emptyList(), false, content)
        val grow = ArrayList<Term>()
        for (t in terms.drop(1)) {
            val p = PERCENT.find(t) ?: return Formula(pos, kind, 0.0, emptyList(), false, content)
            val st = statOf(p.groupValues[2], ctx) ?: return Formula(pos, kind, 0.0, emptyList(), false, content)
            grow.add(Term(st.first, p.groupValues[1].toDouble(), st.second))
        }
        val (stat, guessed) = hpStat(nest.hp, nest.qual, kind)
        return Formula(pos, kind, nest.base, listOf(Term(stat, x, guessed, grow)), false, null)
    }

    /** Junta as fórmulas de todos os níveis da habilidade: "40/80/120/160 + 40% AD". */
    private fun effects(texts: List<String>, mage: Boolean, bonusMana: Boolean): List<Effect> {
        if (texts.isEmpty()) return emptyList()
        val perRank = texts.map { formulas(it, mage, bonusMana) }
        val first = perRank[0]
        val aligned = perRank.all { r -> r.size == first.size && r.indices.all { r[it].shape == first[it].shape } }
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
            out.add(effect(ranks))
        }
        return out
    }

    /** um efeito com os números de cada nível (as fórmulas de `ranks` têm o mesmo formato) */
    private fun effect(ranks: List<Formula>): Effect {
        val f = ranks[0]
        val ratios = f.terms.indices.map { r ->
            val t = f.terms[r]
            val grow = t.grow.indices.map { g -> Ratio(t.grow[g].stat, ranks.map { it.terms[r].grow[g].pct }, t.grow[g].guessed) }
            Ratio(t.stat, ranks.map { it.terms[r].pct }, t.guessed, grow)
        }
        val upTo = if (f.upTo != null) effect(ranks.map { it.upTo!! }) else null
        return Effect(f.kind, ranks.map { it.base }, ratios, f.scaled, null, upTo, f.trigger)
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
