package com.riftcoach.app

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Uma linha de texto lida da tela. Posições em fração da tela (0 = esquerda/topo, 1 = direita/base).
 * `gold` indica texto dourado: é assim que o jogo destaca a linha do próprio jogador.
 */
class TextLine(
    val text: String,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val gold: Boolean = false
) {
    val cx get() = (x0 + x1) / 2
    val cy get() = (y0 + y1) / 2
}

/** Retângulo em fração da tela (mesma escala das [TextLine]). Usado para as áreas cobertas pela nossa janela/bolha. */
class Area(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    /** O centro da linha está dentro da área? */
    fun contains(l: TextLine) = l.cx >= x0 && l.cx <= x1 && l.cy >= y0 && l.cy <= y1

    fun overlaps(ax0: Float, ay0: Float, ax1: Float, ay1: Float) = x0 < ax1 && ax0 < x1 && y0 < ay1 && ay0 < y1
}

enum class Phase { NONE, BAN, BAN_REVEAL, PICK, PREP, LOADING }

/** Linha de um aliado: ou mostra a rota (ainda não escolheu) ou o campeão (já confirmou). */
class AllyRow(val lane: Lane?, val champ: String?, val nick: String?, val gold: Boolean)

/** Linha de um inimigo. `champ` nulo = ainda não confirmou. `exact` = o nome foi lido sem nenhum erro. */
class EnemyRow(val slot: Int, val champ: String?, val exact: Boolean = false)

class ScreenRead(
    val phase: Phase,
    val allies: List<AllyRow>,
    val enemies: List<EnemyRow>,
    val bans: List<String>,
    /** Tela de carregamento: campeões da fileira de cima e da de baixo. */
    val loadTop: List<String> = emptyList(),
    val loadBottom: List<String> = emptyList(),
    /** Partes da tela escondidas pela nossa janela ou pela bolha nesta captura (o que está ali não foi lido). */
    val covered: List<Area> = emptyList()
) {
    /** A tela parece ser a seleção de campeões (ou o carregamento logo depois dela)? */
    val isDraft: Boolean
        get() = phase != Phase.NONE || allies.size >= 2 || enemies.size >= 2

    /** Algum campeão confirmado num dos times? (nas telas de ban ainda não há nenhum) */
    val hasChamps: Boolean
        get() = allies.any { it.champ != null } || enemies.any { it.champ != null }

    /** Algum pedaço deste retângulo estava escondido pela nossa janela/bolha? */
    fun hidden(x0: Float, y0: Float, x1: Float, y1: Float) = covered.any { it.overlaps(x0, y0, x1, y1) }
}

/** Reconhece nomes de campeões e de rotas em texto lido da tela (tolerando pequenos erros de leitura). */
class NameIndex(champs: Collection<Champion>) {
    private val byName = HashMap<String, String>()

    init {
        val byKey = champs.associate { it.key to it.id }
        for (c in champs) {
            byName[norm(c.name)] = c.id
            byName[norm(c.key)] = c.id
        }
        // variações de nome que o jogo em pt-BR pode mostrar
        val aliases = listOf(
            "NUNU" to "Nunu",
            "NUNUEWILLUMP" to "Nunu",
            "NUNUWILLUMP" to "Nunu",
            "WUKONG" to "MonkeyKing",
            "MESTREYI" to "MasterYi",
            "DRMUNDO" to "DrMundo",
            "MUNDO" to "DrMundo",
            "JARVAN" to "JarvanIV",
            "JARVANLV" to "JarvanIV"
        )
        for ((n, k) in aliases) byKey[k]?.let { byName[n] = it }
    }

    /** Devolve o id do campeão, ou null se o texto não for um nome de campeão. */
    fun champion(text: String, fuzzy: Boolean = true): String? {
        val n = norm(text)
        if (n.length < 2) return null
        byName[n]?.let { return it }
        if (!fuzzy || n.length < 5) return null
        val maxDist = if (n.length >= 9) 2 else 1
        var best: String? = null
        var bestD = maxDist + 1
        var tie = false
        for ((name, id) in byName) {
            if (abs(name.length - n.length) > maxDist) continue
            val d = editDistance(n, name, maxDist)
            if (d < bestD) {
                bestD = d
                best = id
                tie = false
            } else if (d == bestD && best != id) {
                tie = true
            }
        }
        return if (bestD <= maxDist && !tie) best else null
    }

    companion object {
        /** Maiúsculas, sem acento, só letras. Dígitos que o leitor confunde com letras viram letras. */
        fun norm(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            for (ch in d) {
                val c = ch.uppercaseChar()
                when {
                    c in 'A'..'Z' -> sb.append(c)
                    c == '0' -> sb.append('O')
                    c == '1' -> sb.append('I')
                }
            }
            return sb.toString()
        }

        private val LANES = listOf(
            "ROTADOMEIO" to Lane.MID,
            "SUPORTE" to Lane.SUP,
            "SELVA" to Lane.JUNGLE,
            "ROTADOBARAO" to Lane.BARON,
            "ROTADODRAGAO" to Lane.ADC
        )

        /** "ROTA DO MEIO", "SUPORTE", "SELVA", "ROTA DO BARÃO", "ROTA DO DRAGÃO". */
        fun lane(text: String): Lane? {
            val n = norm(text)
            for ((name, lane) in LANES) if (n == name) return lane
            if (n.length < 6) return null
            var best: Lane? = null
            var bestD = 3
            for ((name, lane) in LANES) {
                val d = editDistance(n, name, 2)
                if (d < bestD) {
                    bestD = d
                    best = lane
                }
            }
            return best
        }

        /**
         * O texto está em maiúsculas? Os títulos do jogo são; apelidos de jogador quase nunca.
         * Tolera uma ou outra letra que o leitor troque para minúscula ("ROTA Do DRAGÃO").
         */
        fun isUpper(s: String): Boolean {
            var letters = 0
            var upper = 0
            for (ch in s) {
                if (ch.isLetter()) {
                    letters++
                    if (!ch.isLowerCase()) upper++
                }
            }
            return letters >= 2 && upper * 10 >= letters * 7
        }

        /** Distância de edição com limite: devolve limite+1 se passar. */
        fun editDistance(a: String, b: String, limit: Int): Int {
            if (abs(a.length - b.length) > limit) return limit + 1
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (cur[j] < rowMin) rowMin = cur[j]
                }
                if (rowMin > limit) return limit + 1
                val t = prev
                prev = cur
                cur = t
            }
            return prev[b.length]
        }
    }
}

/** Transforma as linhas de texto de uma captura de tela numa leitura do draft. */
class ScreenParser(private val names: NameIndex) {

    /**
     * `covered` = áreas da captura que estavam embaixo da nossa janela ou da bolha. O texto dentro delas
     * é descartado (é o nosso, ou o do jogo misturado com o nosso), e a leitura fica sabendo que não viu ali.
     */
    fun parse(all: List<TextLine>, covered: List<Area> = emptyList()): ScreenRead {
        val lines = if (covered.isEmpty()) all else all.filter { l -> covered.none { it.contains(l) } }
        val phase = detectPhase(lines)
        val allies = parseAllies(lines)
        val enemies = parseEnemies(lines)
        val bans = if (phase == Phase.BAN_REVEAL) parseBans(lines) else emptyList()
        if (phase == Phase.PREP) {
            // fase de preparação: os 5 campeões do time aparecem com o nome numa faixa perto da base
            return ScreenRead(phase, allies, enemies, bans, loadingRow(lines, PREP_Y0, PREP_Y1), covered = covered)
        }
        if (phase == Phase.NONE && allies.isEmpty() && enemies.isEmpty()) {
            val top = loadingRow(lines, 0.275f, 0.342f)
            val bottom = loadingRow(lines, 0.780f, 0.842f)
            if (top.size >= 3 && bottom.size >= 3) {
                return ScreenRead(Phase.LOADING, allies, enemies, bans, top, bottom, covered)
            }
        }
        return ScreenRead(phase, allies, enemies, bans, covered = covered)
    }

    /**
     * Tela de carregamento: cada carta mostra "Campeão Nome-da-skin" ("Kayn Emissário da Escuridão").
     * Procura um nome de campeão em cada linha dentro da faixa de altura dos nomes.
     */
    private fun loadingRow(lines: List<TextLine>, yMin: Float, yMax: Float): List<String> {
        val out = LinkedHashSet<String>()
        for (l in lines.sortedBy { it.x0 }) {
            if (l.cy < yMin || l.cy > yMax || l.cx < ROW_X0 || l.cx > ROW_X1) continue
            val words = l.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            var found: String? = null
            search@ for (start in words.indices) {
                for (n in 3 downTo 1) {
                    if (start + n > words.size) continue
                    found = names.champion(words.subList(start, start + n).joinToString(" "), fuzzy = false)
                    if (found != null) break@search
                }
            }
            if (found != null) out.add(found)
        }
        return out.toList()
    }

    private fun detectPhase(lines: List<TextLine>): Phase {
        var header = Phase.NONE
        var reveal = false
        for (l in lines) {
            val n = NameIndex.norm(l.text)
            if (n.contains("CAMPEOESBANIDOS") || n.contains("BANIMENTOSDOSOPONENTES")) reveal = true
            if (l.cy < 0.09f && l.cx > 0.33f && l.cx < 0.67f) {
                when {
                    n.contains("FASEDEBANIMENTO") -> header = Phase.BAN
                    n == "BANIMENTOS" -> header = Phase.BAN_REVEAL
                    n.contains("SELECIONANDO") -> header = Phase.PICK
                    n.contains("PREPARACAO") -> header = Phase.PREP
                }
            }
        }
        // O título do topo dizendo escolha/preparação vale mais que um texto solto de "banidos" no meio
        // da tela (chat, aviso): a revelação dos bans nunca vem depois dos picks no mesmo draft.
        return if (reveal && header != Phase.PICK && header != Phase.PREP) Phase.BAN_REVEAL else header
    }

    /** Junta cada título com a linha logo abaixo (apelido). Devolve pares (título, apelido ou null). */
    private fun rows(lines: List<TextLine>): List<Pair<TextLine, TextLine?>> {
        val sorted = lines.sortedBy { it.cy }
        val out = ArrayList<Pair<TextLine, TextLine?>>()
        var i = 0
        while (i < sorted.size) {
            val a = sorted[i]
            val b = sorted.getOrNull(i + 1)
            if (b != null && b.cy - a.cy > 0.012f && b.cy - a.cy < 0.062f) {
                out.add(a to b)
                i += 2
            } else {
                out.add(a to null)
                i++
            }
        }
        return out
    }

    private fun usable(l: TextLine): Boolean = l.text.count { it.isLetter() } >= 2

    private fun parseAllies(lines: List<TextLine>): List<AllyRow> {
        val col = lines.filter { it.cx < 0.30f && it.cy > 0.12f && it.cy < 0.78f && usable(it) }
        val out = ArrayList<AllyRow>()
        for ((title, sub) in rows(col)) {
            val gold = title.gold || (sub?.gold ?: false)
            val lane = if (NameIndex.isUpper(title.text)) NameIndex.lane(title.text) else null
            if (lane != null) {
                out.add(AllyRow(lane, null, sub?.text, gold))
                continue
            }
            if (!NameIndex.isUpper(title.text)) continue
            val champ = names.champion(title.text) ?: continue
            out.add(AllyRow(null, champ, sub?.text, gold))
        }
        return out
    }

    private val jogador = Regex("(?i)jogador\\s*([1-5])")

    private fun parseEnemies(lines: List<TextLine>): List<EnemyRow> {
        val col = lines.filter { it.cx > 0.72f && it.cy > 0.12f && it.cy < 0.78f && usable(it) }
        val out = ArrayList<EnemyRow>()
        for ((title, sub) in rows(col)) {
            val mTitle = jogador.find(title.text)
            if (mTitle != null && sub == null) {
                // só "Jogador N": ainda não escolheu
                out.add(EnemyRow(mTitle.groupValues[1].toInt() - 1, null))
                continue
            }
            if (!NameIndex.isUpper(title.text)) continue
            val exact = names.champion(title.text, fuzzy = false)
            val champ = exact ?: names.champion(title.text) ?: continue
            val mSub = sub?.let { jogador.find(it.text) }
            val slot = if (mSub != null) mSub.groupValues[1].toInt() - 1
            else ((title.cy - FIRST_TITLE_Y) / ROW_PITCH).roundToInt().coerceIn(0, 4)
            out.add(EnemyRow(slot, champ, exact != null))
        }
        return out
    }

    /** Na tela "BANIMENTOS" os nomes dos banidos aparecem escritos embaixo de cada ícone. */
    private fun parseBans(lines: List<TextLine>): List<String> {
        val out = LinkedHashSet<String>()
        for (l in lines) {
            if (l.cx < 0.28f || l.cx > 0.72f || l.cy < 0.18f || l.cy > 0.82f) continue
            val words = l.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            var i = 0
            while (i < words.size) {
                var matched = false
                for (n in 3 downTo 1) {
                    if (i + n > words.size) continue
                    val candidate = words.subList(i, i + n).joinToString(" ")
                    val id = names.champion(candidate, fuzzy = NameIndex.norm(candidate).length >= 6)
                    if (id != null) {
                        out.add(id)
                        i += n
                        matched = true
                        break
                    }
                }
                if (!matched) i++
            }
        }
        return out.toList()
    }

    companion object {
        /** Posição vertical do nome do 1º jogador e distância entre as linhas (frações da altura). */
        const val FIRST_TITLE_Y = 0.170f
        const val ROW_PITCH = 0.1333f

        /** Faixa (altura e largura) dos nomes do time na fase de preparação. */
        const val PREP_Y0 = 0.685f
        const val PREP_Y1 = 0.735f
        const val ROW_X0 = 0.15f
        const val ROW_X1 = 0.85f
    }
}

/**
 * Acompanha o draft ao longo das capturas: guarda qual apelido está em qual rota,
 * só aceita uma leitura quando ela se repete, e respeita as correções feitas à mão.
 */
class DraftTracker(private val draft: Draft) {

    var phase = Phase.NONE
        private set

    private val nickLane = HashMap<String, Lane>()
    private val enemySlots = HashMap<Int, String>()
    private val pending = HashMap<String, Pair<String, Int>>()
    private val rejected = HashSet<String>()
    private val manualLanes = HashSet<Lane>()
    private var manualMyLane = false
    private var lastDraftAt = 0L

    /** Campeões que a leitura automática colocou no draft (para saber quando o usuário está corrigindo um erro dela). */
    private val readerSet = HashSet<String>()

    /** Bans marcados à mão depois da revelação dos bans (zerado junto com o draft). */
    private val kept = HashSet<String>()

    /**
     * Chamado pela janela quando o usuário tira um campeão à mão.
     * Se foi a leitura que colocou, é uma correção: ela não coloca de novo neste draft.
     */
    fun userRemoved(champId: String?) {
        if (champId == null) return
        kept.remove(champId)
        if (champId in readerSet) rejected.add(champId)
    }

    /**
     * O usuário marcou um ban à mão (cartão de sugestão ou "+"): se a leitura tinha rejeitado, volta a valer.
     * Depois da revelação dos bans, a marcação é guardada como "mantida": nunca conta como extra, então as
     * leituras seguintes não a apagam. Toques feitos na fase de ban continuam saindo na revelação.
     */
    fun userBanned(champId: String) {
        rejected.remove(champId)
        if (phase.ordinal >= Phase.BAN_REVEAL.ordinal) kept.add(champId)
    }

    /** O usuário definiu à mão o aliado desta rota: a leitura não mexe mais nela. */
    fun userSetAlly(lane: Lane) {
        manualLanes.add(lane)
    }

    fun userSetLane() {
        manualMyLane = true
    }

    fun userPicked(champId: String) {
        rejected.remove(champId)
    }

    fun reset() {
        nickLane.clear()
        enemySlots.clear()
        pending.clear()
        rejected.clear()
        readerSet.clear()
        kept.clear()
        manualLanes.clear()
        manualMyLane = false
        phase = Phase.NONE
    }

    private fun confirm(key: String, value: String, need: Int = 2): Boolean {
        val p = pending[key]
        val count = if (p != null && p.first == value) p.second + 1 else 1
        pending[key] = value to count
        return count >= need
    }

    private fun nickKey(nick: String?): String? {
        if (nick == null) return null
        val k = nick.lowercase().filter { it.isLetterOrDigit() }
        return if (k.length >= 3) k else null
    }

    private fun laneOfNick(nick: String?): Lane? {
        val k = nickKey(nick) ?: return null
        nickLane[k]?.let { return it }
        val limit = if (k.length >= 8) 2 else 1
        var best: Lane? = null
        var bestD = limit + 1
        for ((known, lane) in nickLane) {
            val d = NameIndex.editDistance(k, known, limit)
            if (d < bestD) {
                bestD = d
                best = lane
            }
        }
        return best
    }

    /** Aplica uma leitura ao draft. Devolve true se algo mudou. */
    fun apply(read: ScreenRead, nowMs: Long): Boolean {
        if (!read.isDraft) return false
        if (read.phase == Phase.LOADING) return applyLoading(read, nowMs)
        var changed = false

        // --- draft novo? (1) passou muito tempo sem tela de draft (a partida acabou de ser jogada)
        fun hasPicks() = draft.myPick != null || draft.allies.isNotEmpty() || draft.enemies.isNotEmpty()
        val stale = lastDraftAt != 0L && nowMs - lastDraftAt > NEW_DRAFT_GAP_MS
        if (stale && (hasPicks() || draft.bans.isNotEmpty())) {
            draft.reset()
            reset()
            changed = true
        }
        lastDraftAt = nowMs

        // Uma tela de ban (fase de banimento ou revelação dos bans) nunca tem campeão confirmado nos
        // times. Se a leitura diz "ban" mas tem campeão, o "banidos" veio de um texto solto (chat, aviso)
        // numa tela de escolha: não conta como tela de ban (nem muda a fase, nem traz bans).
        val banPhase = read.phase == Phase.BAN || read.phase == Phase.BAN_REVEAL
        val banScreen = banPhase && !read.hasChamps

        // (2) apareceu uma tela de ban depois de uma fase de escolha/preparação/carregamento, ou antes de
        // qualquer fase, com picks no draft: num draft de verdade os bans vêm antes dos picks, então esses
        // picks são do draft anterior.
        // Também conta quando a fase anterior era de escolha/preparação/carregamento e o draft ainda tem bans:
        // alguém desistiu da fila antes de qualquer pick ser lido (a fase de ban dura ~30 s, as 2 leituras sobram).
        // Só a passagem para a fase de ban conta: correções feitas à mão durante os bans do draft novo ficam.
        // Precisa de duas leituras seguidas, para uma leitura errada não apagar o draft todo.
        val inBans = phase == Phase.BAN || phase == Phase.BAN_REVEAL
        val afterBans = phase == Phase.PICK || phase == Phase.PREP || phase == Phase.LOADING
        if (banScreen && !inBans && (hasPicks() || (afterBans && draft.bans.isNotEmpty()))) {
            if (!confirm(NEW_DRAFT_KEY, "ban")) return changed // ainda não aplica nada desta leitura
            draft.reset()
            reset()
            changed = true
        } else {
            pending.remove(NEW_DRAFT_KEY)
        }

        if (read.phase != Phase.NONE && read.phase != phase && (banScreen || !banPhase)) {
            phase = read.phase
            changed = true
        }

        // --- bans (antes das rotas: se a revelação mostrar que é um draft novo, o que esta leitura
        // ensina sobre as rotas já fica no draft novo)
        if (read.phase == Phase.BAN_REVEAL && banScreen && applyBans(read.bans)) changed = true

        // --- rotas: enquanto a linha mostra a rota, aprendemos de quem ela é
        for (r in read.allies) {
            val lane = r.lane ?: continue
            nickKey(r.nick)?.let { nickLane[it] = lane }
            if (r.gold && !manualMyLane && lane != draft.myLane && confirm("mylane", lane.name)) {
                draft.myLane = lane
                draft.allies.remove(lane)
                changed = true
            }
        }

        // --- picks dos aliados
        val laneRows = read.allies.mapNotNull { it.lane }.toHashSet()
        val resolved = HashMap<AllyRow, Lane>()
        val unresolved = ArrayList<AllyRow>()
        val goldCount = read.allies.count { it.gold }
        for (r in read.allies) {
            if (r.champ == null) continue
            // O apelido é a pista mais confiável de quem é a linha. A cor dourada só decide quando
            // o apelido não é conhecido, e só se exatamente uma linha estiver dourada (um aviso do
            // sistema ou um brilho por cima da tela pode "pintar" outra linha por alguns instantes).
            val lane = laneOfNick(r.nick) ?: if (r.gold && goldCount == 1) draft.myLane else null
            if (lane != null) resolved[r] = lane else unresolved.add(r)
        }
        // duas linhas apontando para a mesma rota: leitura duvidosa, ignora as duas nesta captura
        val twice = resolved.values.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (twice.isNotEmpty()) resolved.entries.removeAll { it.value in twice }
        // um aliado com apelido ilegível: se só sobrou uma rota, é a dele
        if (twice.isEmpty() && unresolved.size == 1 && read.allies.size == 5) {
            val free = Lane.entries.filter { it !in laneRows && it !in resolved.values && it != draft.myLane }
            val meSeen = read.allies.any { it.gold } || laneRows.contains(draft.myLane) || resolved.values.contains(draft.myLane)
            if (free.size == 1 && meSeen) resolved[unresolved[0]] = free[0]
        }
        for ((r, lane) in resolved) {
            val champ = r.champ ?: continue
            if (champ in rejected) continue
            // Um campeão não pode estar em duas rotas do mesmo time. Se a leitura quer mover um
            // campeão que já está em outro lugar, só aceita depois de se repetir várias vezes.
            if (lane == draft.myLane) {
                val conflict = champ in draft.allies.values
                if (draft.myPick != champ && confirm("me", champ, if (conflict) MOVE_CONFIRM else 2)) {
                    if (conflict) draft.allies.values.remove(champ)
                    draft.myPick = champ
                    readerSet.add(champ)
                    changed = true
                }
            } else if (lane !in manualLanes) {
                val conflict = draft.myPick == champ || draft.allies.any { it.key != lane && it.value == champ }
                if (draft.allies[lane] != champ && confirm("A:" + lane.name, champ, if (conflict) MOVE_CONFIRM else 2)) {
                    if (conflict) {
                        if (draft.myPick == champ) draft.myPick = null
                        draft.allies.values.remove(champ)
                    }
                    draft.allies[lane] = champ
                    readerSet.add(champ)
                    changed = true
                }
            }
        }

        // --- picks dos inimigos (por posição na lista)
        for (e in read.enemies) {
            val champ = e.champ ?: continue
            if (enemySlots[e.slot] == champ) continue
            // nome lido sem erro vale na primeira leitura: o último pick inimigo fica pouco tempo na tela
            if (!confirm("E" + e.slot, champ, if (e.exact) 1 else 2)) continue
            val prev = enemySlots.put(e.slot, champ)
            if (prev != null && draft.enemies.remove(prev)) changed = true
            if (champ !in rejected && champ !in draft.enemies && draft.enemies.size < 5) {
                draft.enemies.add(champ)
                readerSet.add(champ)
                changed = true
            }
        }


        if (read.phase == Phase.PREP) {
            val partial = read.hidden(ScreenParser.ROW_X0, ScreenParser.PREP_Y0, ScreenParser.ROW_X1, ScreenParser.PREP_Y1)
            if (completeTeam(read.loadTop, partial)) changed = true
        }
        return changed
    }

    /**
     * Bans lidos na tela de revelação. O que a leitura mostra SEMPRE entra no draft já na primeira vez
     * (a janela some por pouco tempo: pode não haver segunda leitura); só tirar bans espera.
     *
     * - Leitura parcial (parte coberta, nome que o leitor não pegou): só acrescenta o que leu.
     * O total real são 10 lugares de ban (5 por time), com lugares vazios possíveis; 8 é o mínimo para
     * considerar a leitura completa.
     * - Leitura completa (de [FULL_BANS_MIN] a [MAX_BANS] nomes): é a lista de bans deste draft. Se o draft tem
     *   algum ban que não está nela ("extra"; os "mantidos" à mão depois da revelação nunca contam):
     *   - extras que não vieram da leitura (marcados à mão, por toque num cartão de sugestão antes da
     *     revelação): saem sempre já nesta leitura, porque os bans do jogo não somam com os marcados à mão;
     *   - extras que vieram da leitura (falso ban de leitura parcial, ou ban de um draft anterior): só agem
     *     depois de duas leituras iguais. Com 3 ou mais deles é um draft anterior (alguém desistiu da
     *     fila, ou partida nova em menos de 90 s): começa um draft novo, com picks e tudo. Com 1 ou 2
     *     pode ser só ruído de leitura: tira só esses e não apaga picks nem rotas.
     * - Leitura com mais de [MAX_BANS] nomes é impossível (um texto solto virou nome de campeão): só
     *   acrescenta, como a parcial; nunca remove nem recomeça (um falso ban esconde 1 campeão; perder os
     *   reais faria o app sugerir banidos).
     * As correções à mão de picks e rotas continuam valendo, e um ban que o usuário tirou não volta.
     */
    private fun applyBans(read: List<String>): Boolean {
        var changed = false
        if (read.size in FULL_BANS_MIN..MAX_BANS) {
            val seen = read.filter { it !in rejected }.toSet()
            // "mantidos" (marcados à mão depois da revelação) nunca contam como extra
            val extra = draft.bans.filter { it !in seen && it !in kept }
            // bans marcados à mão (não vieram da leitura) não somam com os do jogo: saem já nesta leitura
            val manual = extra.filter { it !in readerSet }.toSet()
            if (manual.isNotEmpty()) {
                draft.bans.removeAll(manual)
                changed = true
            }
            // só os bans postos pela leitura esperam duas leituras iguais (e só eles contam para o draft novo)
            val lidos = extra.filter { it !in manual }
            if (lidos.isEmpty()) {
                pending.remove(BANS_KEY)
            } else if (confirm(BANS_KEY, seen.sorted().joinToString(","))) {
                if (lidos.size >= NEW_DRAFT_EXTRAS) {
                    draft.reset()
                    reset()
                    phase = Phase.BAN_REVEAL
                } else {
                    draft.bans.removeAll(lidos.toSet())
                    pending.remove(BANS_KEY)
                }
                changed = true
            }
        }
        for (b in read) {
            if (b in rejected) continue
            if (draft.bans.add(b)) {
                readerSet.add(b)
                changed = true
            }
        }
        return changed
    }

    /**
     * As telas de preparação e de carregamento mostram os 5 campeões do time, mas sem a rota.
     * Se falta exatamente um campeão no nosso time e sobrou exatamente um lugar, ele vai para lá.
     * (Acontece quando o último pick é confirmado e a tela muda antes de dar tempo de ler.)
     * `partial` = parte da fileira estava embaixo da nossa janela: aí bastam 2 nomes, desde que pelo
     * menos um já seja do nosso time (prova de que a fileira lida é a nossa).
     */
    private fun completeTeam(names: List<String>, partial: Boolean = false): Boolean {
        val have = HashSet<String>(draft.allies.values)
        draft.myPick?.let { have.add(it) }
        if (names.size < 4 && !(partial && names.size >= 2 && names.any { it in have })) return false
        // um campeão banido nunca está num time (o nome pode vir de um texto da tela que não é o time)
        val missing = names.filter { it !in have && it !in draft.enemies && it !in draft.bans && it !in rejected }
        if (missing.size != 1) return false
        val champ = missing[0]
        val emptyLanes = Lane.entries.filter { it != draft.myLane && it !in draft.allies.keys && it !in manualLanes }
        val needMe = draft.myPick == null
        if (emptyLanes.size == 1 && !needMe) {
            if (!confirm("fill", champ)) return false
            draft.allies[emptyLanes[0]] = champ
        } else if (emptyLanes.isEmpty() && needMe) {
            if (!confirm("fill", champ)) return false
            draft.myPick = champ
        } else {
            return false
        }
        readerSet.add(champ)
        return true
    }

    /**
     * Tela de carregamento, logo depois do draft: mostra os 10 campeões.
     * Serve para completar picks inimigos que não deu tempo de ler na seleção.
     */
    private fun applyLoading(read: ScreenRead, nowMs: Long): Boolean {
        // só vale como continuação de um draft que acabamos de acompanhar
        if (lastDraftAt == 0L || nowMs - lastDraftAt > NEW_DRAFT_GAP_MS) return false
        var changed = false
        if (phase != Phase.LOADING) {
            phase = Phase.LOADING
            changed = true
        }
        val mine = HashSet<String>(draft.allies.values)
        draft.myPick?.let { mine.add(it) }
        val foes = draft.enemies.toSet()
        // descobre qual fileira é a do inimigo comparando com o que já sabemos
        val topIsEnemy = read.loadTop.count { it in foes } + read.loadBottom.count { it in mine } >
            read.loadBottom.count { it in foes } + read.loadTop.count { it in mine }
        val enemyRow = if (topIsEnemy) read.loadTop else read.loadBottom
        for (c in enemyRow) {
            if (c in mine || c in rejected || c in draft.bans || c in draft.enemies || draft.enemies.size >= 5) continue
            draft.enemies.add(c)
            readerSet.add(c)
            changed = true
        }
        if (completeTeam(if (topIsEnemy) read.loadBottom else read.loadTop)) changed = true
        return changed
    }

    companion object {
        /** Sem ver tela de draft por este tempo, a próxima seleção é tratada como partida nova. */
        const val NEW_DRAFT_GAP_MS = 90_000L

        /** Leituras iguais seguidas necessárias para mudar um campeão de rota dentro do time. */
        const val MOVE_CONFIRM = 5

        /**
         * O draft tem 10 lugares de ban (5 por time), e pode haver lugar vazio (ban pulado). Uma leitura da
         * revelação só vale como "completa" (lista de bans do draft, que remove manuais/extras) com pelo
         * menos [FULL_BANS_MIN] nomes; 9 e 10 são leituras legítimas. Detectar lugar vazio por imagem fica
         * para uma tarefa futura.
         */
        const val FULL_BANS_MIN = 8
        const val MAX_BANS = 10

        private const val NEW_DRAFT_KEY = "draftNovo"
        /** Extras postos pela leitura (bans no draft que a leitura completa não mostra) a partir dos quais é um draft novo. */
        const val NEW_DRAFT_EXTRAS = 3

        private const val BANS_KEY = "bans"
    }
}
