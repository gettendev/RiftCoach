package com.riftcoach.app

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Suggestion(
    val champ: Champion,
    val lane: Lane,
    val score: Int,
    val stat: LaneStat,
    val reasons: List<String>
)

class BanAdvice(val lane: List<Suggestion>, val global: List<Suggestion>)

class TeamRead(val ad: Double, val ap: Double, val plan: List<String>)

class Analysis(
    val ally: TeamRead?,
    val enemy: TeamRead?,
    val compare: List<String>,
    val tips: List<String>
)

/**
 * O "cérebro" do app. Não depende do Android, só dos dados.
 * Tudo aqui é pontuação por regras: meta (dados da Tencent) + composição dos dois times.
 */
class Engine(private val data: GameData) {

    /** Faixa de elo dos dados: 0 todos, 1 Diamante+, 2 Mestre+, 3 Desafiante, 4 Pico do Rift. */
    var tier: String = "1"

    fun champ(id: String?): Champion? = if (id == null) null else data.champs[id]

    fun allChamps(): Collection<Champion> = data.champs.values

    fun laneStats(lane: Lane): Map<String, LaneStat> =
        data.stats[tier]?.get(lane) ?: data.stats["0"]?.get(lane) ?: emptyMap()

    // ------------------------------------------------------------------ BANS

    fun suggestBans(d: Draft, nLane: Int = 6, nGlobal: Int = 6): BanAdvice {
        val taken = d.taken()
        val own = ArrayList<Suggestion>()
        val others = HashMap<String, Suggestion>()
        for (lane in Lane.entries) {
            for ((id, st) in laneStats(lane)) {
                if (id in taken || st.pr < MIN_PR) continue
                val c = champ(id) ?: continue
                val s = 50 + (trustedWr(st) - 50) * 3.0 + st.br * 0.35 + st.pr * 0.45 + (4 - st.level) * 2.0
                val reasons = ArrayList<String>()
                if (st.wr >= 52) reasons.add("winrate alto")
                if (st.br >= 10) reasons.add("muito banido")
                if (st.pr >= 10) reasons.add("muito escolhido")
                if (st.level <= 1) reasons.add("tier T${st.level}")
                if (reasons.isEmpty()) reasons.add("forte na rota")
                val sug = Suggestion(c, lane, s.roundToInt(), st, reasons)
                if (lane == d.myLane) {
                    own.add(sug)
                } else {
                    val prev = others[id]
                    if (prev == null || prev.score < sug.score) others[id] = sug
                }
            }
        }
        own.sortByDescending { it.score }
        val ownIds = own.take(nLane).map { it.champ.id }.toSet()
        val global = others.values.filter { it.champ.id !in ownIds }.sortedByDescending { it.score }
        return BanAdvice(own.take(nLane), global.take(nGlobal))
    }

    // ------------------------------------------------- ROTAS DO TIME INIMIGO

    private fun laneWeight(id: String, lane: Lane): Double {
        val st = laneStats(lane)[id] ?: data.stats["0"]?.get(lane)?.get(id)
        if (st != null) return max(st.pr, 0.05)
        val c = champ(id) ?: return 0.01
        return if (lane in c.lanes) 0.3 else 0.01
    }

    /** Deduz a rota mais provável de cada inimigo, sem repetir rota. */
    fun deduceEnemyLanes(enemies: List<String>): Map<String, Lane> {
        if (enemies.isEmpty()) return emptyMap()
        val lanes = Lane.entries
        val list = enemies.take(5)
        var best: List<Lane> = emptyList()
        var bestScore = Double.NEGATIVE_INFINITY
        val acc = ArrayList<Lane>()

        fun rec(i: Int, used: Int, score: Double) {
            if (i == list.size) {
                if (score > bestScore) {
                    bestScore = score
                    best = ArrayList(acc)
                }
                return
            }
            for (l in lanes) {
                val bit = 1 shl l.ordinal
                if (used and bit != 0) continue
                acc.add(l)
                rec(i + 1, used or bit, score + ln(laneWeight(list[i], l)))
                acc.removeAt(acc.size - 1)
            }
        }
        rec(0, 0, 0.0)
        val out = LinkedHashMap<String, Lane>()
        for (i in list.indices) out[list[i]] = best[i]
        return out
    }

    // ----------------------------------------------------------------- PICKS

    private class R(val text: String, val w: Double)

    fun suggestPicks(d: Draft, n: Int = 10): List<Suggestion> {
        val taken = HashSet<String>(d.bans).apply { addAll(d.allies.values); addAll(d.enemies) }
        val allies = d.allies.values.mapNotNull { champ(it) }
        val enemies = d.enemies.mapNotNull { champ(it) }
        val enemyLanes = deduceEnemyLanes(d.enemies)
        val opp = enemies.firstOrNull { enemyLanes[it.id] == d.myLane }
        val lane = d.myLane

        // parceiro de rota (dupla do dragão)
        val partner = when (lane) {
            Lane.ADC -> champ(d.allies[Lane.SUP])
            Lane.SUP -> champ(d.allies[Lane.ADC])
            else -> null
        }

        val allyAd = dmgCount(allies, Dmg.AD)
        val allyAp = dmgCount(allies, Dmg.AP)
        val enAd = dmgCount(enemies, Dmg.AD)
        val enAp = dmgCount(enemies, Dmg.AP)
        val known = allies.size / 4.0 // quanto do meu time já escolheu

        val eThreat = enemies.count { it.hasAny("engage", "dive", "assassino") }
        val eThreatNames = names(enemies.filter { it.hasAny("engage", "dive", "assassino") })
        val eTanks = enemies.filter { it.has("tank") }
        val eImmobile = enemies.filter { it.has("imovel") }
        val ePoke = enemies.count { it.has("poke") }
        val eMobile = enemies.count { it.has("mobil") }
        val eCc = enemies.count { it.has("cc") }

        val out = ArrayList<Suggestion>()
        for ((id, st) in laneStats(lane)) {
            if (id in taken || st.pr < MIN_PR) continue
            val c = champ(id) ?: continue
            val rs = ArrayList<R>()

            // 1) META: winrate, tier da Tencent e popularidade
            var s = 50 + (trustedWr(st) - 50) * 2.5 + (4 - st.level) * 2.0 + min(st.pr, 15.0) * 0.25

            // 2) EQUILÍBRIO DE DANO do meu time (dano misto fica neutro)
            if (allies.size >= 2) {
                val diff = allyAp - allyAd
                if (diff >= 1.5) {
                    if (c.dmg == Dmg.AD) rs.add(R("seu time tem muito dano mágico: traz dano físico", 7.0))
                    if (c.dmg == Dmg.AP) rs.add(R("seu time já tem dano mágico demais", -7.0))
                } else if (diff <= -1.5) {
                    if (c.dmg == Dmg.AP) rs.add(R("seu time tem muito dano físico: traz dano mágico", 7.0))
                    if (c.dmg == Dmg.AD) rs.add(R("seu time já tem dano físico demais", -7.0))
                }
            }

            // 3) O QUE FALTA NO MEU TIME
            if (allies.size >= 2) {
                if (allies.none { it.has("tank") } && lane != Lane.ADC && lane != Lane.MID && c.has("tank"))
                    rs.add(R("seu time não tem linha de frente", 5.0 * known + 1))
                if (allies.none { it.has("engage") } && c.has("engage"))
                    rs.add(R("falta iniciação no seu time", 4.0 * known + 1))
                if (allies.count { it.has("cc") } == 0 && c.has("cc"))
                    rs.add(R("seu time está sem controle de grupo", 3.0 * known))
            }
            if (lane == Lane.SUP || lane == Lane.JUNGLE || lane == Lane.BARON) {
                val carry = allies.firstOrNull { it.has("hyper") }
                if (carry != null && c.hasAny("peel", "encant") && carry !== partner)
                    rs.add(R("protege o carry do time (${carry.name})", 2.5))
            }

            // 4) RESPOSTA AO TIME INIMIGO
            if (eThreat >= 2) {
                if (c.has("mobil")) rs.add(R("inimigo com muito engage/dive ($eThreatNames): tem fuga", 4.0))
                if (c.has("deseng")) rs.add(R("desengage contra $eThreatNames", 4.5))
                else if (c.has("peel")) rs.add(R("protege o time contra $eThreatNames", 3.5))
                if (c.has("imovel")) rs.add(R("sem mobilidade contra $eThreatNames", -4.5))
            }
            if (eTanks.size >= 2 && c.has("antitank"))
                rs.add(R("derrete tanques (${names(eTanks)})", 5.0))
            if (eImmobile.size >= 2 && c.hasAny("assassino", "dive", "engage"))
                rs.add(R("inimigos sem fuga (${names(eImmobile)}): fáceis de alcançar", 3.5))
            if (ePoke >= 2) {
                if (c.hasAny("engage", "dive")) rs.add(R("fecha a distância contra o poke inimigo", 2.5))
                else if (c.has("cura")) rs.add(R("sustain contra o poke inimigo", 2.0))
            }
            if (enemies.size >= 3) {
                if (enAd - enAp >= 2 && c.key in ANTI_AD) rs.add(R("time inimigo quase todo físico: ótimo contra AD", 5.0))
                if (enAp - enAd >= 2 && c.key in ANTI_AP) rs.add(R("time inimigo quase todo mágico: ótimo contra AP", 5.0))
                if (eMobile >= 3 && c.key in ANTI_DASH) rs.add(R("pune os avanços do time inimigo", 4.0))
                if (eCc >= 3 && c.key in ANTI_CC) rs.add(R("anula o controle de grupo inimigo", 3.5))
            }

            // 5) CONFRONTO DIRETO NA ROTA
            if (opp != null) {
                val o = opp.name
                if (opp.has("assassino")) {
                    if (c.hasAny("cc", "tank", "escudo", "peel")) rs.add(R("segura o assassino $o na rota", 3.0))
                    else if (c.has("imovel")) rs.add(R("vulnerável ao $o na rota", -3.5))
                }
                if (opp.has("imovel") && c.hasAny("assassino", "dive")) rs.add(R("$o não tem fuga na rota", 3.0))
                if (opp.has("tank") && c.has("antitank")) rs.add(R("bom contra o tanque $o na rota", 3.0))
                if (opp.has("hyper") && !opp.has("early") && c.has("early")) rs.add(R("pune o começo fraco de $o", 3.5))
                if (opp.has("early") && c.has("hyper") && !c.has("early")) rs.add(R("começo de rota difícil contra $o", -2.5))
                if (opp.hasAny("poke", "alcance") && c.hasAny("cura", "mobil") && !c.has("imovel"))
                    rs.add(R("aguenta o poke de $o", 2.0))
                if (opp.hasAny("dive", "engage") && c.hasAny("deseng", "peel")) rs.add(R("neutraliza a entrada de $o", 2.5))
            }

            // 6) SINERGIA COM ALIADOS
            val yas = allies.firstOrNull { it.key == "Yasuo" || it.key == "Yone" }
            if (yas != null && yas.key == "Yasuo" && c.has("knockup"))
                rs.add(R("arremesso para o ultimate do Yasuo", 5.0))
            if (c.key == "Yasuo") {
                val k = allies.filter { it.has("knockup") }
                if (k.isNotEmpty()) rs.add(R("aliados com arremesso (${names(k)}) ativam seu ultimate", min(6.0, 3.0 * k.size)))
            }
            if (partner != null) {
                val p = partner.name
                if (c.has("hyper") && partner.hasAny("encant", "peel")) rs.add(R("$p protege você enquanto escala", 4.0))
                if (c.hasAny("encant", "peel") && partner.has("hyper")) rs.add(R("protege o hyper carry $p", 4.5))
                if (c.has("early") && partner.has("engage")) rs.add(R("rota agressiva com o engage de $p", 3.5))
                if (c.has("engage") && partner.has("early")) rs.add(R("rota agressiva com $p", 3.5))
                if (c.hasAny("poke", "alcance") && partner.has("poke")) rs.add(R("rota de poke com $p", 2.5))
            }
            if (c.has("aoe") && allies.any { it.has("engage") && it.has("aoe") })
                rs.add(R("combo em área com ${names(allies.filter { it.has("engage") && it.has("aoe") })}", 3.0))
            if (c.has("engage") && allies.count { it.has("aoe") } >= 2)
                rs.add(R("inicia para o dano em área do seu time", 3.0))
            for (a in allies) {
                val t = PAIRS[pairKey(c.key, a.key)] ?: continue
                rs.add(R("sinergia com ${a.name}: $t", 5.5))
            }

            s += rs.sumOf { it.w }
            val reasons = rs.sortedByDescending { kotlin.math.abs(it.w) }.map { (if (it.w < 0) "⚠ " else "") + it.text }
            out.add(Suggestion(c, lane, s.roundToInt().coerceIn(0, 99), st, reasons))
        }
        out.sortByDescending { it.score }
        return out.take(n)
    }

    // --------------------------------------------------------------- ANÁLISE

    fun analyze(d: Draft): Analysis {
        val allyList = ArrayList(d.allies.values.mapNotNull { champ(it) })
        champ(d.myPick)?.let { allyList.add(it) }
        val enemyList = d.enemies.mapNotNull { champ(it) }

        val ally = if (allyList.size >= 2) readTeam(allyList, mine = true) else null
        val enemy = if (enemyList.size >= 2) readTeam(enemyList, mine = false) else null

        val compare = ArrayList<String>()
        if (allyList.size >= 3 && enemyList.size >= 3) {
            val aLate = allyList.count { it.has("hyper") }
            val eLate = enemyList.count { it.has("hyper") }
            val aEarly = allyList.count { it.has("early") }
            val eEarly = enemyList.count { it.has("early") }
            if (aLate > eLate && eEarly >= aEarly)
                compare.add("Seu time escala melhor. Não force lutas cedo: farme, troque objetivos e lute com itens.")
            else if (eLate > aLate && aEarly >= eEarly)
                compare.add("O inimigo escala melhor. Você precisa de vantagem cedo: invada, force dragões e feche o jogo rápido.")
            val aFront = allyList.count { it.has("tank") }
            val eFront = enemyList.count { it.has("tank") }
            if (aFront == 0 && eFront >= 1)
                compare.add("Seu time não tem linha de frente. Evite luta 5v5 de frente: busque abates isolados e emboscadas.")
            val aEng = allyList.count { it.has("engage") }
            val eEng = enemyList.count { it.has("engage") }
            if (aEng == 0 && eEng >= 2)
                compare.add("Só o inimigo tem iniciação. Mantenha visão e não ande agrupado em corredor estreito.")
            if (aEng >= 2 && enemyList.count { it.has("imovel") } >= 2)
                compare.add("Você tem iniciação e eles têm alvos sem fuga: procure a luta sempre que o engage estiver disponível.")
        }

        val tips = ArrayList<String>()
        if (enemyList.size >= 3) {
            val ad = dmgCount(enemyList, Dmg.AD)
            val ap = dmgCount(enemyList, Dmg.AP)
            when {
                ad - ap >= 2 -> tips.add("Inimigo quase todo dano físico: armadura rende muito (botas e itens de armadura).")
                ap - ad >= 2 -> tips.add("Inimigo quase todo dano mágico: resistência mágica rende muito (botas e itens de RM).")
                else -> tips.add("Dano inimigo misto: equilibre armadura e resistência mágica.")
            }
            val healers = enemyList.filter { it.has("cura") }
            if (healers.isNotEmpty())
                tips.add("Cura inimiga (${names(healers)}): compre anti-cura cedo.")
            val tanks = enemyList.filter { it.has("tank") }
            if (tanks.size >= 2)
                tips.add("Vários tanques (${names(tanks)}): priorize penetração de armadura ou mágica e dano por % de vida.")
            val ccs = enemyList.filter { it.has("cc") }
            if (ccs.size >= 3)
                tips.add("Muito controle de grupo (${names(ccs)}): tenacidade e o encantamento de purificação valem a pena.")
            val assassins = enemyList.filter { it.has("assassino") }
            if (assassins.isNotEmpty())
                tips.add("Assassinos (${names(assassins)}): se você for carry, considere um item defensivo (estase ou reviver) e não ande sozinho.")
            val shields = enemyList.filter { it.has("escudo") }
            if (shields.size >= 2)
                tips.add("Muitos escudos (${names(shields)}): itens que reduzem escudo ajudam.")
        }
        return Analysis(ally, enemy, compare, tips)
    }

    private fun readTeam(team: List<Champion>, mine: Boolean): TeamRead {
        val who = if (mine) "Seu time" else "O inimigo"
        val themes = ArrayList<Pair<Double, String>>()
        val hyper = team.filter { it.has("hyper") }
        val early = team.filter { it.has("early") }
        val engage = team.filter { it.has("engage") }
        val aoe = team.filter { it.has("aoe") }
        val poke = team.filter { it.has("poke") }
        val assassins = team.filter { it.has("assassino") }
        val split = team.filter { it.has("split") }
        val peel = team.filter { it.hasAny("peel", "encant") }

        if (hyper.isNotEmpty())
            themes.add(hyper.size * 1.5 + (if (peel.size >= 2) 1.0 else 0.0) to
                "$who ganha no late game com ${names(hyper)}." +
                (if (peel.size >= 2) " A composição é de proteger o carry (${names(peel)})." else ""))
        if (early.size >= 2)
            themes.add(early.size * 1.2 to "$who é forte no começo (${names(early)}): quer lutas e objetivos cedo.")
        if (engage.isNotEmpty() && aoe.size >= 2)
            themes.add(1.0 + aoe.size to "$who quer luta 5v5: inicia com ${names(engage)} e segue com dano em área (${names(aoe)}).")
        if (poke.size >= 2)
            themes.add(poke.size * 1.1 to "$who desgasta de longe (${names(poke)}) antes dos objetivos; perde força se for engajado.")
        if (assassins.size >= 2)
            themes.add(assassins.size * 1.1 to "$who procura abates em alvos isolados (${names(assassins)}).")
        if (split.isNotEmpty())
            themes.add(split.size * 0.9 to "$who pode pressionar rota lateral com ${names(split)}.")
        if (themes.isEmpty())
            themes.add(0.0 to "$who não tem um plano de jogo marcante: depende de execução e de objetivos.")

        val plan = themes.sortedByDescending { it.first }.take(2).map { it.second }
        return TeamRead(dmgCount(team, Dmg.AD), dmgCount(team, Dmg.AP), plan)
    }

    // ---------------------------------------------------------------- apoio

    /** Quanto de dano de um tipo o time tem. Tanques e encantadores contam metade: causam pouco dano. */
    private fun dmgCount(list: List<Champion>, type: Dmg): Double =
        list.sumOf {
            val share = if (it.dmg == type) 1.0 else if (it.dmg == Dmg.MX) 0.5 else 0.0
            share * (if (it.hasAny("tank", "encant")) 0.5 else 1.0)
        }

    /**
     * Winrate "confiável": campeão pouco jogado tem winrate inflado por especialistas,
     * então puxamos o número para 50% quanto menor for a taxa de escolha.
     */
    private fun trustedWr(st: LaneStat): Double = 50 + (st.wr - 50) * st.pr / (st.pr + 3.0)

    private fun names(list: List<Champion>): String = list.joinToString(", ") { it.name }

    companion object {
        /** Abaixo disso o campeão quase não é jogado na rota: o winrate não é confiável. */
        const val MIN_PR = 1.0

        val TIERS = listOf(
            "1" to "Diamante+",
            "2" to "Mestre+",
            "3" to "Desafiante",
            "4" to "Pico do Rift",
            "0" to "Todos os elos"
        )

        private val ANTI_AD = setOf("Rammus", "Malphite", "Jax", "Teemo", "Ornn", "Sett", "Nasus")
        private val ANTI_AP = setOf("Galio", "Kassadin", "Yasuo", "Mel", "DrMundo")
        private val ANTI_DASH = setOf("Poppy", "Vex", "Singed")
        private val ANTI_CC = setOf("Morgana", "Olaf", "Milio", "Sivir")

        private fun pairKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

        private val PAIRS: Map<String, String> = HashMap<String, String>().apply {
            fun p(a: String, b: String, t: String) = put(pairKey(a, b), t)
            p("Xayah", "Rakan", "dupla com habilidades reforçadas")
            p("Lucian", "Nami", "trocas curtas muito fortes")
            p("Lucian", "Braum", "ativa o atordoamento do Braum rápido")
            p("Kalista", "Thresh", "ultimate lança o Thresh para iniciar")
            p("Kalista", "Alistar", "ultimate lança o Alistar para iniciar")
            p("Kalista", "Leona", "ultimate lança a Leona para iniciar")
            p("Kalista", "Nautilus", "ultimate lança o Nautilus para iniciar")
            p("Samira", "Nautilus", "controle em cadeia para o combo da Samira")
            p("Samira", "Rell", "controle em área para o ultimate da Samira")
            p("Samira", "Leona", "controle em cadeia para o combo da Samira")
            p("Samira", "Alistar", "arremesso para o combo da Samira")
            p("MissFortune", "Amumu", "ultimates combinados em área")
            p("MissFortune", "Leona", "prende os alvos para o ultimate")
            p("MissFortune", "Seraphine", "ultimates combinados em área")
            p("Kogmaw", "Lulu", "hyper carry protegido")
            p("Twitch", "Yuumi", "emboscadas com cura e velocidade")
            p("Twitch", "Lulu", "hyper carry protegido")
            p("Zeri", "Yuumi", "velocidade e sustain")
            p("Zeri", "Lulu", "hyper carry protegido")
            p("Vayne", "Lulu", "hyper carry protegido")
            p("Jinx", "Lulu", "hyper carry protegido")
            p("Caitlyn", "Lux", "armadilha em cima da raiz")
            p("Caitlyn", "Morgana", "armadilha em cima da raiz")
            p("Ezreal", "Karma", "rota de poke muito forte")
            p("Draven", "Thresh", "ganchos viram abates cedo")
            p("Tristana", "Leona", "all-in forte a partir do nível 2")
            p("Orianna", "Malphite", "bola entra junto com o ultimate")
            p("Orianna", "JarvanIV", "bola entra junto com o engage")
            p("Orianna", "MonkeyKing", "bola entra junto com o ultimate")
            p("Orianna", "Hecarim", "bola entra junto com a carga")
            p("Orianna", "Rengar", "bola entra junto com o salto")
            p("Galio", "Camille", "ultimate acompanha o mergulho")
            p("Galio", "JarvanIV", "ultimate acompanha o engage")
            p("Yasuo", "Malphite", "ultimate em área para o Yasuo")
            p("Yasuo", "Gragas", "barril arremessa para o Yasuo")
            p("Yone", "Malphite", "engage duplo em área")
            p("MasterYi", "Yuumi", "Yi acelerado e curado")
            p("MasterYi", "Zilean", "velocidade e segunda vida")
            p("Kayle", "MasterYi", "invulnerabilidade no carry")
            p("Amumu", "Yasuo", "controle em área para o Yasuo")
            p("JarvanIV", "Rumble", "ultimate do Rumble dentro da arena")
            p("JarvanIV", "MissFortune", "arena prende para o ultimate")
        }
    }
}
