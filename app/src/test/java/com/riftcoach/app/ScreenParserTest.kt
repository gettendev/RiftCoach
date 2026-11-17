package com.riftcoach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Testa a interpretação da tela com linhas de texto montadas a partir dos prints reais do jogo. */
class ScreenParserTest {

    private val data: GameData by lazy {
        val dir = File("src/main/assets")
        DataParser.parse(
            File(dir, "hero_list.json").readText(Charsets.UTF_8),
            File(dir, "rank.json").readText(Charsets.UTF_8),
            File(dir, "champions.json").readText(Charsets.UTF_8)
        )
    }
    private val names by lazy { NameIndex(data.champs.values) }
    private val parser by lazy { ScreenParser(names) }

    private fun id(key: String) = data.champs.values.first { it.key == key }.id

    // posições dos prints (1356 x 610)
    private fun line(text: String, x: Int, y: Int, gold: Boolean = false): TextLine {
        val w = text.length * 9
        return TextLine(text, x / 1356f, (y - 8) / 610f, (x + w) / 1356f, (y + 8) / 610f, gold)
    }

    private fun ally(row: Int, title: String, nick: String?, gold: Boolean = false, picked: Boolean = true): List<TextLine> {
        val x = if (picked) 206 else 165
        val y = 104 + row * 81
        val out = arrayListOf(line(title, x, y, gold))
        if (nick != null) out.add(line(nick, x, y + 22, gold))
        return out
    }

    private fun enemy(row: Int, champ: String?): List<TextLine> {
        val y = 104 + row * 81
        return if (champ != null) listOf(line(champ, 1150, y), line("Jogador ${row + 1}", 1128, y + 28))
        else listOf(line("Jogador ${row + 1}", 1128, y + 10))
    }

    private fun banPhase(): List<TextLine> =
        listOf(line("FASE DE BANIMENTO", 595, 18), line("13", 665, 48)) +
            ally(0, "ROTA DO MEIO", "Abyss", picked = false) +
            ally(1, "SUPORTE", "ixpanka ixposa", gold = true, picked = false) +
            ally(2, "SELVA", "Siombie", picked = false) +
            ally(3, "ROTA DO BARÃO", null, picked = false) +
            ally(4, "ROTA DO DRAGÃO", "Cudeburro", picked = false) +
            (0..4).flatMap { enemy(it, null) } +
            // a grade de campeões no centro não pode virar ban nem pick
            listOf(line("Ashe", 468, 206), line("Sylas", 565, 206), line("Master Yi", 655, 206), line("BANIR", 650, 560))

    @Test
    fun reconheceNomes() {
        assertEquals(id("Kogmaw"), names.champion("KOG'MAW"))
        assertEquals(id("Ksante"), names.champion("K'SANTE"))
        assertEquals(id("MasterYi"), names.champion("Master Yi"))
        assertEquals(id("Lissandra"), names.champion("LISSANORA")) // erro de leitura de 1 letra
        assertEquals(id("Jinx"), names.champion("JINX"))
        assertNull(names.champion("JINK")) // nome curto: só aceita exato
        assertNull(names.champion("Jogador 3"))
        assertEquals(Lane.BARON, NameIndex.lane("ROTA DO BARÃO"))
        assertEquals(Lane.ADC, NameIndex.lane("ROTA DO DRAGAO"))
        assertEquals(Lane.JUNGLE, NameIndex.lane("SELVA"))
        assertNull(NameIndex.lane("Siombie"))
    }

    @Test
    fun faseDeBanimento() {
        val r = parser.parse(banPhase())
        assertEquals(Phase.BAN, r.phase)
        assertEquals(5, r.allies.size)
        assertEquals(listOf(Lane.MID, Lane.SUP, Lane.JUNGLE, Lane.BARON, Lane.ADC), r.allies.map { it.lane })
        assertTrue(r.allies[1].gold)
        assertEquals(5, r.enemies.size)
        assertTrue(r.enemies.all { it.champ == null })
        assertTrue(r.bans.isEmpty())
    }

    @Test
    fun telaDeBanimentosRevelados() {
        val lines = listOf(
            line("BANIMENTOS", 625, 18),
            line("CAMPEÕES BANIDOS — ALIADOS", 552, 134),
            line("Kassadin Ashe", 458, 266), line("Volibear Mordekaiser", 748, 266),
            line("BANIMENTOS", 607, 304),
            line("Lulu", 470, 442), line("Mel", 570, 442), line("Sylas", 662, 442), line("Cho'Gath", 750, 442),
            line("BANIMENTOS DOS OPONENTES", 557, 476)
        ) + ally(0, "ROTA DO MEIO", "Abyss", picked = false)
        val r = parser.parse(lines)
        assertEquals(Phase.BAN_REVEAL, r.phase)
        assertEquals(
            listOf("Kassadin", "Ashe", "Volibear", "Mordekaiser", "Lulu", "Mel", "Sylas", "Chogath").map { id(it) }.toSet(),
            r.bans.toSet()
        )
    }

    @Test
    fun draftCompletoComOrdemTrocadaEApelidoIlegivel() {
        val draft = Draft().apply { myLane = Lane.ADC }
        val t = DraftTracker(draft)
        var now = 1_000L
        fun feed(lines: List<TextLine>, times: Int = 2) {
            repeat(times) {
                t.apply(parser.parse(lines), now)
                now += 800
            }
        }

        feed(banPhase())
        assertEquals("a linha dourada define a rota do jogador", Lane.SUP, draft.myLane)

        // print 7: inimigo escolheu Veigar; Lissandra e Janna confirmadas; resto ainda mostra a rota
        feed(
            listOf(line("OPONENTES SELECIONANDO", 568, 18)) +
                ally(0, "LISSANDRA", "Abyss") +
                ally(1, "JANNA", "ixpanka ixposa", gold = true) +
                ally(2, "SELVA", "Siombie") +
                ally(3, "ROTA DO BARÃO", null, picked = false) +
                ally(4, "ROTA DO DRAGÃO", "Cudeburro", picked = false) +
                enemy(0, "VEIGAR") + (1..4).flatMap { enemy(it, null) }
        )
        assertEquals(id("Lissandra"), draft.allies[Lane.MID])
        assertEquals(id("Janna"), draft.myPick)
        assertEquals(listOf(id("Veigar")), draft.enemies)

        // print 1: a ordem das linhas mudou (Kog'Maw subiu) e o apelido do Barão é ilegível
        feed(
            listOf(line("EQUIPE SELECIONANDO", 586, 18)) +
                ally(0, "LISSANDRA", "Abyss") +
                ally(1, "JANNA", "ixpanka ixposa", gold = true) +
                ally(2, "KOG'MAW", "Cudeburro") +
                ally(3, "DARIUS", null) +
                ally(4, "SELVA", "Siombie") +
                enemy(0, "VEIGAR") + enemy(1, "AURORA") + enemy(2, "JINX") + enemy(3, "KAYN") + enemy(4, "K'SANTE")
        )
        assertEquals(id("Kogmaw"), draft.allies[Lane.ADC])
        assertEquals("apelido ilegível resolvido por eliminação", id("Darius"), draft.allies[Lane.BARON])
        assertNull(draft.allies[Lane.JUNGLE])
        assertEquals(listOf("Veigar", "Aurora", "Jinx", "Kayn", "Ksante").map { id(it) }, draft.enemies)

        // correção manual: o usuário tira um inimigo lido errado e a leitura não coloca de volta
        t.userRemoved(id("Kayn"))
        draft.enemies.remove(id("Kayn"))
        feed(
            listOf(line("EQUIPE SELECIONANDO", 586, 18)) +
                enemy(0, "VEIGAR") + enemy(1, "AURORA") + enemy(2, "JINX") + enemy(3, "KAYN") + enemy(4, "K'SANTE")
        )
        assertTrue(id("Kayn") !in draft.enemies)

        // partida nova: minutos depois, fase de ban de novo -> começa do zero
        now += 20 * 60_000L
        feed(banPhase(), 1)
        assertTrue(draft.enemies.isEmpty() && draft.allies.isEmpty() && draft.myPick == null)
    }

    @Test
    fun linhaDeAliadoPintadaDeDouradoNaoViraMeuPick() {
        // caso real do teste no emulador: algo por cima da tela trocou o apelido da Lissandra
        // por um texto dourado; o app não pode concluir que a Lissandra é o pick do jogador
        val draft = Draft().apply { myLane = Lane.ADC }
        val t = DraftTracker(draft)
        var now = 1_000L
        fun feed(lines: List<TextLine>, times: Int) {
            repeat(times) {
                t.apply(parser.parse(lines), now)
                now += 800
            }
        }
        feed(banPhase(), 2)
        val enemies = enemy(0, "VEIGAR") + (1..4).flatMap { enemy(it, null) }
        val header = listOf(line("OPONENTES SELECIONANDO", 568, 18))
        val rest = ally(2, "KOG'MAW", "Cudeburro") + ally(3, "DARIUS", null) + ally(4, "SELVA", "Siombie")
        feed(header + ally(0, "LISSANDRA", "Abyss") + ally(1, "JANNA", "ixpanka ixposa", gold = true) + rest + enemies, 3)
        assertEquals(id("Janna"), draft.myPick)
        assertEquals(id("Lissandra"), draft.allies[Lane.MID])

        feed(header + ally(0, "LISSANDRA", "bed", gold = true) + ally(1, "JANNA", "ixpanka ixposa", gold = true) + rest + enemies, 4)
        assertEquals("continua sendo a Janna", id("Janna"), draft.myPick)
        assertEquals(id("Lissandra"), draft.allies[Lane.MID])
        assertEquals(id("Kogmaw"), draft.allies[Lane.ADC])
    }

    @Test
    fun nomeComErroDeLeituraPrecisaDeDuasLeituras() {
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        val header = listOf(line("OPONENTES SELECIONANDO", 568, 18))
        // "VEIGAH": o leitor trocou uma letra -> só vale se repetir
        t.apply(parser.parse(header + enemy(0, "VEIGAH") + enemy(1, null)), 1000)
        assertTrue(draft.enemies.isEmpty())
        t.apply(parser.parse(header + enemy(0, "VEIGAH") + enemy(1, null)), 1600)
        assertEquals(listOf(id("Veigar")), draft.enemies)
        // nome lido sem erro vale na primeira leitura
        t.apply(parser.parse(header + enemy(0, "VEIGAH") + enemy(1, "AURORA")), 2200)
        assertEquals(listOf(id("Veigar"), id("Aurora")), draft.enemies)
    }

    @Test
    fun telaDeCarregamentoCompletaOsInimigos() {
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        val pick = listOf(line("OPONENTES SELECIONANDO", 568, 18)) +
            ally(0, "LISSANDRA", "Abyss") + ally(1, "JANNA", "ixpanka ixposa", gold = true) +
            enemy(0, "VEIGAR") + enemy(1, "AURORA") + enemy(2, "JINX") + enemy(3, null) + enemy(4, null)
        t.apply(parser.parse(pick), 1000)
        t.apply(parser.parse(pick), 1600)
        assertEquals(3, draft.enemies.size)

        // print 3: aliados em cima, inimigos embaixo, nomes com a skin junto
        val loading = listOf(
            line("Darius Mestre da", 277, 179), line("Enterrada", 297, 196),
            line("Shyvana Dragão de", 443, 179), line("Gelo", 484, 196),
            line("Lissandra de", 637, 179), line("Porcelana", 644, 196),
            line("Kog'Maw", 819, 196), line("Cudeburro", 818, 217),
            line("Janna Guardiã Estelar", 960, 197), line("ixpanka ixposa", 980, 218),
            line("K'Sante", 302, 503), line("Conan", 291, 522),
            line("Kayn Emissário da", 447, 486), line("Escuridão", 470, 503),
            line("Aurora", 652, 503), line("Unstoppable", 639, 523),
            line("Jinx Felina de Batalha", 786, 502), line("Olsen", 823, 523),
            line("Veigar Noel Macabro", 960, 504), line("Yasouls", 998, 523)
        )
        val r = parser.parse(loading)
        assertEquals(Phase.LOADING, r.phase)
        assertEquals(5, r.loadTop.size)
        assertEquals(5, r.loadBottom.size)
        t.apply(r, 30_000)
        assertEquals(listOf("Veigar", "Aurora", "Jinx", "Ksante", "Kayn").map { id(it) }.toSet(), draft.enemies.toSet())
        assertEquals("aliados não entram como inimigos", 5, draft.enemies.size)
    }

    @Test
    fun telaDePreparacaoCompletaOUltimoAliado() {
        val draft = Draft().apply { myLane = Lane.ADC }
        val t = DraftTracker(draft)
        var now = 1_000L
        fun feed(lines: List<TextLine>, times: Int = 2) {
            repeat(times) {
                t.apply(parser.parse(lines), now)
                now += 800
            }
        }
        feed(banPhase())
        // todos confirmaram menos o caçador (Siombie), que ainda mostra "SELVA"
        feed(
            listOf(line("EQUIPE SELECIONANDO", 586, 18)) +
                ally(0, "LISSANDRA", "Abyss") + ally(1, "JANNA", "ixpanka ixposa", gold = true) +
                ally(2, "KOG'MAW", "Cudeburro") + ally(3, "DARIUS", null) + ally(4, "SELVA", "Siombie") +
                (0..4).flatMap { enemy(it, null) }
        )
        assertNull(draft.allies[Lane.JUNGLE])

        // print 2: fase de preparação, nomes do time numa faixa embaixo
        val prep = listOf(
            line("FASE DE PREPARAÇÃO", 594, 18),
            line("Shyvana", 273, 434), line("Lissandra", 464, 432), line("Janna Guardiã Est...", 624, 432),
            line("ixpanka ixposa", 644, 459), line("Kog'Maw", 865, 432), line("Cudeburro", 865, 457),
            line("Darius", 1070, 432)
        )
        val r = parser.parse(prep)
        assertEquals(Phase.PREP, r.phase)
        assertEquals(5, r.loadTop.size)
        feed(prep)
        assertEquals("o campeão que faltava vai para a rota que sobrou", id("Shyvana"), draft.allies[Lane.JUNGLE])
        assertEquals(id("Janna"), draft.myPick)
    }
}
