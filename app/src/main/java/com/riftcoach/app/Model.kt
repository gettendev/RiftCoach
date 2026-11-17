package com.riftcoach.app

import org.json.JSONObject

/** Rotas do Wild Rift. `pos` é o código usado pelos dados da Tencent; `cn` é o nome na lista de heróis. */
enum class Lane(val pos: String, val label: String, val short: String, val cn: String) {
    BARON("2", "Barão", "BAR", "单人路"),
    JUNGLE("5", "Selva", "SEL", "打野"),
    MID("1", "Meio", "MEI", "中路"),
    ADC("3", "Dragão (ADC)", "ADC", "射手"),
    SUP("4", "Suporte", "SUP", "辅助");

    companion object {
        fun fromPos(p: String): Lane? = entries.firstOrNull { it.pos == p }
        fun fromCn(s: String): Lane? = entries.firstOrNull { it.cn == s }
        fun fromName(s: String?): Lane? = entries.firstOrNull { it.name == s }
    }
}

enum class Dmg { AD, AP, MX }

class Champion(
    val id: String,
    val key: String,
    val name: String,
    val dmg: Dmg,
    val tags: Set<String>,
    val lanes: List<Lane>,
    val avatar: String
) {
    fun has(tag: String) = tag in tags
    fun hasAny(vararg t: String) = t.any { it in tags }
}

/** Estatísticas de um campeão numa rota, em porcentagem. `level` é o tier da Tencent (0 = melhor). */
class LaneStat(val wr: Double, val pr: Double, val br: Double, val rank: Int, val level: Int)

class GameData(
    val champs: Map<String, Champion>,
    /** tier -> rota -> id do campeão -> estatística */
    val stats: Map<String, Map<Lane, Map<String, LaneStat>>>,
    /** data dos dados, formato AAAAMMDD */
    val date: String
) {
    fun dateLabel(): String =
        if (date.length == 8) "${date.substring(6, 8)}/${date.substring(4, 6)}/${date.substring(0, 4)}" else "?"
}

/** Estado do draft atual. */
class Draft {
    var myLane: Lane = Lane.ADC
    var myPick: String? = null
    val bans = LinkedHashSet<String>()
    val allies = LinkedHashMap<Lane, String>()
    val enemies = ArrayList<String>()

    fun taken(): Set<String> {
        val s = HashSet<String>(bans)
        s.addAll(allies.values)
        s.addAll(enemies)
        myPick?.let { s.add(it) }
        return s
    }

    fun reset() {
        myPick = null
        bans.clear()
        allies.clear()
        enemies.clear()
    }

    fun isComplete() = myPick != null && allies.size == 4 && enemies.size == 5
}

object DataParser {

    private val posterSuffix = Regex("_\\d+\\.jpg$")

    fun parse(heroJson: String, rankJson: String, metaJson: String): GameData {
        val meta = JSONObject(metaJson)
        val heroList = JSONObject(heroJson).getJSONObject("heroList")
        val champs = HashMap<String, Champion>()

        for (id in heroList.keys()) {
            val h = heroList.getJSONObject(id)
            var key = h.optString("poster").substringAfterLast('/').replace(posterSuffix, "")
            if (key.isBlank()) key = h.optString("alias", id)

            val roles = ArrayList<String>()
            h.optJSONArray("roles")?.let { arr -> for (i in 0 until arr.length()) roles.add(arr.optString(i)) }
            val lanes = h.optString("lane").split(';').mapNotNull { Lane.fromCn(it.trim()) }

            val m = meta.optJSONObject(key)
            val name: String
            val dmg: Dmg
            val tags: Set<String>
            if (m != null) {
                name = m.optString("n", key)
                dmg = runCatching { Dmg.valueOf(m.optString("d", "AD")) }.getOrDefault(Dmg.AD)
                tags = m.optString("t").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } else {
                // Campeão novo que ainda não está na nossa tabela: deduz o básico pela classe.
                name = key
                val cls = roles.firstOrNull() ?: ""
                dmg = when (cls) {
                    "法师", "辅助", "坦克" -> Dmg.AP
                    else -> Dmg.AD
                }
                tags = when (cls) {
                    "法师" -> setOf("poke")
                    "刺客" -> setOf("assassino", "mobil", "dive")
                    "坦克" -> setOf("tank", "cc")
                    "射手" -> setOf("hyper")
                    "辅助" -> setOf("encant", "peel")
                    else -> setOf("dive")
                }
            }
            champs[id] = Champion(id, key, name, dmg, tags, lanes, h.optString("avatar"))
        }

        val stats = HashMap<String, Map<Lane, Map<String, LaneStat>>>()
        var date = ""
        val data = JSONObject(rankJson).getJSONObject("data")
        for (tier in data.keys()) {
            val tierObj = data.optJSONObject(tier) ?: continue
            val byLane = HashMap<Lane, Map<String, LaneStat>>()
            for (pos in tierObj.keys()) {
                val lane = Lane.fromPos(pos) ?: continue
                val arr = tierObj.optJSONArray(pos) ?: continue
                val map = HashMap<String, LaneStat>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (date.isEmpty()) date = o.optString("dtstatdate")
                    map[o.optString("hero_id")] = LaneStat(
                        wr = o.optDouble("win_rate", 0.5) * 100,
                        pr = o.optDouble("appear_rate", 0.0) * 100,
                        br = o.optDouble("forbid_rate", 0.0) * 100,
                        rank = o.optInt("strength", 99),
                        level = o.optInt("strength_level", 4)
                    )
                }
                byLane[lane] = map
            }
            stats[tier] = byLane
        }
        return GameData(champs, stats, date)
    }
}
