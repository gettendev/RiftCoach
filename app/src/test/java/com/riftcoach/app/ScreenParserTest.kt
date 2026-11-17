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

    /** print 5: revelação dos bans, nomes no centro da tela */
    private fun revealScreen(): List<TextLine> = listOf(
        line("BANIMENTOS", 625, 18),
        line("CAMPEÕES BANIDOS — ALIADOS", 552, 134),
        line("Kassadin Ashe", 458, 266), line("Volibear Mordekaiser", 748, 266),
        line("BANIMENTOS", 607, 304),
        line("Lulu", 470, 442), line("Mel", 570, 442), line("Sylas", 662, 442), line("Cho'Gath", 750, 442),
        line("BANIMENTOS DOS OPONENTES", 557, 476)
    ) + ally(0, "ROTA DO MEIO", "Abyss", picked = false) +
        ally(1, "SUPORTE", "ixpanka ixposa", gold = true, picked = false) +
        ally(2, "SELVA", "Siombie", picked = false)

    private val revealBans by lazy {
        listOf("Kassadin", "Ashe", "Volibear", "Mordekaiser", "Lulu", "Mel", "Sylas", "Chogath").map { id(it) }.toSet()
    }

    /** prints 1/7: fase de escolha com o time quase completo */
    private fun pickScreen(): List<TextLine> =
        listOf(line("EQUIPE SELECIONANDO", 586, 18)) +
            ally(0, "LISSANDRA", "Abyss") + ally(1, "JANNA", "ixpanka ixposa", gold = true) +
            ally(2, "KOG'MAW", "Cudeburro") + ally(3, "DARIUS", null) + ally(4, "SELVA", "Siombie") +
            enemy(0, "VEIGAR") + enemy(1, "AURORA") + enemy(2, "JINX") + enemy(3, "KAYN") + enemy(4, "K'SANTE")

    /** print 2: fase de preparação, nomes do time numa faixa embaixo */
    private fun prepScreen(): List<TextLine> = listOf(
        line("FASE DE PREPARAÇÃO", 594, 18),
        line("Shyvana", 273, 434), line("Lissandra", 464, 432), line("Janna Guardiã Est...", 624, 432),
        line("ixpanka ixposa", 644, 459), line("Kog'Maw", 865, 432), line("Cudeburro", 865, 457),
        line("Darius", 1070, 432)
    )

    /**
     * A janela aberta no centro, como o "Como usar" recomenda (medida no emulador: de 27% a 73% da largura,
     * de 11,5% da altura até embaixo), com o próprio texto dela por cima do jogo.
     */
    private val panelArea = Area(0.27f, 0.115f, 0.73f, 0.99f)

    private fun panelText(): List<TextLine> = listOf(
        line("SUP ▾", 440, 100), line("Bans", 520, 100), line("Picks", 590, 100), line("Novo", 950, 100),
        line("Hwei", 500, 160), line("Yuumi", 800, 160), line("Nocturne · SEL", 500, 270), line("Mordekaiser · BAR", 800, 270)
    )

    @Test
    fun telaDeBanimentosRevelados() {
        val r = parser.parse(revealScreen())
        assertEquals(Phase.BAN_REVEAL, r.phase)
        assertEquals(revealBans, r.bans.toSet())
    }

    @Test
    fun revelacaoDosBansComAJanelaPorCima() {
        // sem saber onde a janela está, os nomes dela (Hwei, Yuumi...) virariam bans
        val semFiltro = parser.parse(revealScreen() + panelText())
        assertTrue(id("Hwei") in semFiltro.bans)

        // com a área da janela: a fase continua reconhecida pelo título do topo, mas nada dali vira ban
        val coberta = parser.parse(revealScreen() + panelText(), listOf(panelArea))
        assertEquals(Phase.BAN_REVEAL, coberta.phase)
        assertTrue(coberta.bans.isEmpty())
        assertTrue(coberta.hidden(0.28f, 0.18f, 0.72f, 0.82f))
        assertEquals("a coluna dos aliados não está coberta", 3, coberta.allies.size)

        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        t.apply(coberta, 1000)
        assertTrue(draft.bans.isEmpty())
        // a janela saiu da frente: a leitura limpa pega os 8 bans
        t.apply(parser.parse(revealScreen()), 1600)
        assertEquals(revealBans, draft.bans)
        // a janela voltou: leitura coberta não apaga o que já foi lido
        t.apply(coberta, 2200)
        assertEquals(revealBans, draft.bans)
    }

    @Test
    fun quadroSoValeComAJanelaParadaHaUmTempo() {
        // janela mudou (abriu/fechou/arrastou) 500 ms antes do quadro: já aparece na captura
        assertTrue(OwnWindows.usable(shotAt = 10_500, changedAt = 10_000))
        // mudou logo antes: o quadro pode ainda ser o de antes da mudança
        assertTrue(!OwnWindows.usable(shotAt = 10_100, changedAt = 10_000))
        // mudou depois do quadro (ex.: a janela voltou enquanto o leitor de texto trabalhava)
        assertTrue(!OwnWindows.usable(shotAt = 10_000, changedAt = 10_400))
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

    /** Draft acompanhado até a preparação (só falta o caçador, que nunca apareceu confirmado). */
    private fun trackedUntilPick(t: DraftTracker, start: Long): Long {
        var now = start
        repeat(2) { t.apply(parser.parse(banPhase()), now); now += 800 }
        repeat(2) { t.apply(parser.parse(pickScreen()), now); now += 800 }
        return now
    }

    @Test
    fun preparacaoComAJanelaPorCimaCompletaOUltimoAliado() {
        // caso do testador: a janela no centro cobre Lissandra, Janna e Kog'Maw na faixa de nomes;
        // sobram Shyvana e Darius visíveis
        val draft = Draft().apply { myLane = Lane.ADC }
        val t = DraftTracker(draft)
        var now = trackedUntilPick(t, 1_000)
        assertNull(draft.allies[Lane.JUNGLE])

        val r = parser.parse(prepScreen() + panelText(), listOf(panelArea))
        assertEquals(Phase.PREP, r.phase)
        assertEquals(listOf(id("Shyvana"), id("Darius")), r.loadTop)
        repeat(2) { t.apply(r, now); now += 800 }
        assertEquals(id("Shyvana"), draft.allies[Lane.JUNGLE])
        assertEquals("tudo o que já estava continua", id("Kogmaw"), draft.allies[Lane.ADC])
        assertEquals(id("Janna"), draft.myPick)
    }

    @Test
    fun preparacaoComPoucosNomesSoCompletaComProvaDeQueEOTimeCerto() {
        val draft = Draft().apply { myLane = Lane.ADC }
        val t = DraftTracker(draft)
        var now = trackedUntilPick(t, 1_000)
        val header = line("FASE DE PREPARAÇÃO", 594, 18)
        // coberta, mas só um nome visível (e nenhum conhecido): não dá para saber que é a nossa fileira
        val soShyvana = parser.parse(listOf(header, line("Shyvana", 273, 434)), listOf(panelArea))
        repeat(3) { t.apply(soShyvana, now); now += 800 }
        assertNull(draft.allies[Lane.JUNGLE])
        // sem a janela, 2 nomes não bastam (o leitor falhou nos outros: pode ter lido errado)
        val semJanela = parser.parse(listOf(header, line("Shyvana", 273, 434), line("Darius", 1070, 432)))
        repeat(3) { t.apply(semJanela, now); now += 800 }
        assertNull(draft.allies[Lane.JUNGLE])
    }

    @Test
    fun draftNovoComecaQuandoAparecemOsBansDepoisDosPicks() {
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        var now = trackedUntilPick(t, 1_000)
        repeat(2) { t.apply(parser.parse(prepScreen()), now); now += 800 }
        assertEquals(id("Janna"), draft.myPick)
        assertEquals(id("Shyvana"), draft.allies[Lane.JUNGLE])
        assertEquals(5, draft.enemies.size)

        // uma leitura isolada da tela de bans (erro de leitura) não apaga nada...
        t.apply(parser.parse(revealScreen()), now); now += 800
        t.apply(parser.parse(pickScreen()), now); now += 800
        assertEquals(id("Janna"), draft.myPick)
        assertEquals(5, draft.enemies.size)
        assertTrue("e não mistura os bans do outro draft", draft.bans.isEmpty())

        // ...mas a revelação dos bans repetida (print 5, logo depois do draft anterior) começa um draft novo
        repeat(2) { t.apply(parser.parse(revealScreen()), now); now += 800 }
        assertNull(draft.myPick)
        assertTrue(draft.allies.isEmpty() && draft.enemies.isEmpty())
        assertEquals("os bans do draft novo entram", revealBans, draft.bans)
        assertEquals(Phase.BAN_REVEAL, t.phase)

        // correção à mão durante os bans do draft novo: continua valendo nas leituras seguintes
        draft.myPick = id("Thresh")
        t.userPicked(id("Thresh"))
        draft.allies[Lane.MID] = id("Ahri")
        t.userSetAlly(Lane.MID)
        repeat(3) { t.apply(parser.parse(revealScreen()), now); now += 800 }
        repeat(3) { t.apply(parser.parse(banPhase()), now); now += 800 }
        assertEquals(id("Thresh"), draft.myPick)
        assertEquals(id("Ahri"), draft.allies[Lane.MID])
        assertEquals(revealBans, draft.bans)
    }

    /** Outra revelação de bans (outro draft), com os nomes nas mesmas posições do print 5. */
    private fun revealScreen2(): List<TextLine> = listOf(
        line("BANIMENTOS", 625, 18),
        line("CAMPEÕES BANIDOS — ALIADOS", 552, 134),
        line("Garen Zed", 458, 266), line("Yasuo Teemo", 748, 266),
        line("BANIMENTOS", 607, 304),
        line("Ahri", 470, 442), line("Lux", 570, 442), line("Jax", 662, 442), line("Vayne", 750, 442),
        line("BANIMENTOS DOS OPONENTES", 557, 476)
    ) + ally(0, "ROTA DO MEIO", "Abyss", picked = false) +
        ally(1, "SUPORTE", "ixpanka ixposa", gold = true, picked = false) +
        ally(2, "SELVA", "Siombie", picked = false)

    @Test
    fun banidoNuncaCompletaOTime() {
        // caso do inspetor: a Lulu (banida) aparece escrita na faixa da preparação com a janela cobrindo
        // o centro; com o Darius (do time) visível, a fileira parecia ser a nossa e a Lulu virava a caçadora
        val draft = Draft().apply { myLane = Lane.ADC }
        val t = DraftTracker(draft)
        var now = 1_000L
        repeat(2) { t.apply(parser.parse(banPhase()), now); now += 800 }
        repeat(2) { t.apply(parser.parse(revealScreen()), now); now += 800 }
        assertTrue(id("Lulu") in draft.bans)
        repeat(2) { t.apply(parser.parse(pickScreen()), now); now += 800 }
        assertNull(draft.allies[Lane.JUNGLE])

        val prep = parser.parse(
            listOf(line("FASE DE PREPARAÇÃO", 594, 18), line("Lulu", 273, 434), line("Darius", 1070, 432)) + panelText(),
            listOf(panelArea)
        )
        assertEquals(listOf(id("Lulu"), id("Darius")), prep.loadTop)
        repeat(4) { t.apply(prep, now); now += 800 }
        assertNull("banido não entra no time", draft.allies[Lane.JUNGLE])
        assertTrue(id("Lulu") !in draft.allies.values && draft.myPick != id("Lulu"))
    }

    @Test
    fun textoDeBanidosNaTelaDeEscolhaNaoZeraODraft() {
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        var now = trackedUntilPick(t, 1_000)
        assertEquals(id("Janna"), draft.myPick)
        val bansAntes = draft.bans.toSet()
        // um aviso/chat com "campeões banidos" no meio da tela de escolha
        val aviso = line("CAMPEÕES BANIDOS — ALIADOS", 552, 134)
        assertEquals("o título do topo vale mais", Phase.PICK, parser.parse(pickScreen() + aviso).phase)

        // sem o título (coberto pela bolha, ou o leitor não pegou): a leitura diz "ban", mas tem campeões
        val semTitulo = parser.parse(pickScreen().drop(1) + aviso)
        assertEquals(Phase.BAN_REVEAL, semTitulo.phase)
        repeat(4) { t.apply(semTitulo, now); now += 800 }
        assertEquals(id("Janna"), draft.myPick)
        assertEquals(id("Lissandra"), draft.allies[Lane.MID])
        assertEquals(5, draft.enemies.size)
        assertEquals(bansAntes, draft.bans.toSet())
        assertEquals("a fase não vira revelação dos bans", Phase.PICK, t.phase)
    }

    @Test
    fun partidaNovaJaNaRevelacaoDosBansAplicaNaPrimeiraLeitura() {
        // regras 1 e 2 juntas: >90 s sem draft, e a primeira tela vista é a revelação dos bans
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        var now = trackedUntilPick(t, 1_000)
        assertEquals(id("Janna"), draft.myPick)
        now += 20 * 60_000L
        assertTrue("a leitura muda o draft", t.apply(parser.parse(revealScreen()), now))
        assertNull("os picks velhos saem já nesta leitura", draft.myPick)
        assertTrue(draft.allies.isEmpty() && draft.enemies.isEmpty())
        assertEquals(revealBans, draft.bans)
        assertEquals(Phase.BAN_REVEAL, t.phase)
    }

    @Test
    fun revelacaoCompletaDosBansDescartaBansManuaisQueNaoEstaoNela() {
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        var now = 1_000L
        repeat(2) { t.apply(parser.parse(banPhase()), now); now += 800 }
        // antes da revelação: o usuário toca em dois cartões de sugestão (um deles é banido de verdade)
        // e corrige à mão o próprio pick e um aliado
        draft.bans.add(id("Ahri"))
        draft.bans.add(id("Kassadin"))
        draft.myPick = id("Thresh")
        t.userPicked(id("Thresh"))
        draft.allies[Lane.MID] = id("Hwei")
        t.userSetAlly(Lane.MID)

        repeat(2) { t.apply(parser.parse(revealScreen()), now); now += 800 }
        assertEquals("os 8 do jogo, sem somar os manuais", revealBans, draft.bans)
        assertEquals(id("Thresh"), draft.myPick)
        assertEquals(id("Hwei"), draft.allies[Lane.MID])

        // leitura parcial (janela por cima) não mexe; um ban lido que o usuário tirou não volta
        t.apply(parser.parse(revealScreen() + panelText(), listOf(panelArea)), now); now += 800
        assertEquals(revealBans, draft.bans)
        draft.bans.remove(id("Mel"))
        t.userRemoved(id("Mel"))
        repeat(3) { t.apply(parser.parse(revealScreen()), now); now += 800 }
        assertEquals(revealBans - id("Mel"), draft.bans)
        assertEquals(id("Thresh"), draft.myPick)
    }

    @Test
    fun revelacaoComOutrosBansLidosEDraftNovo() {
        // alguém desistiu da fila depois da revelação: em menos de 90 s vem outra revelação, com outros bans
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        var now = 1_000L
        repeat(2) { t.apply(parser.parse(banPhase()), now); now += 800 }
        repeat(2) { t.apply(parser.parse(revealScreen()), now); now += 800 }
        assertEquals(revealBans, draft.bans)
        draft.myPick = id("Thresh") // pick marcado à mão no draft que caiu
        t.userPicked(id("Thresh"))

        val novos = listOf("Garen", "Zed", "Yasuo", "Teemo", "Ahri", "Lux", "Jax", "Vayne").map { id(it) }.toSet()
        t.apply(parser.parse(revealScreen2()), now); now += 800
        // os bans novos entram já na 1ª leitura; os velhos só saem (com o draft) na 2ª
        assertEquals("a 1ª leitura acrescenta e não tira nada", revealBans + novos, draft.bans)
        assertEquals(id("Thresh"), draft.myPick)
        t.apply(parser.parse(revealScreen2()), now); now += 800
        assertEquals("os bans velhos não se misturam com os novos", novos, draft.bans)
        assertNull(draft.myPick)
        assertEquals(Phase.BAN_REVEAL, t.phase)
    }

    @Test
    fun textoDaJanelaNoQuadroComAJanelaFechada() {
        assertTrue(OwnWindows.panelTextSeen(panelText() + line("Análise", 660, 100)))
        assertTrue("uma aba sozinha pode ser texto do jogo", !OwnWindows.panelTextSeen(panelText()))
        assertTrue(!OwnWindows.panelTextSeen(revealScreen()))
    }

    @Test
    fun draftNovoDepoisDaPartidaMesmoComOAppLigadoNoMeioDoDraft() {
        // draft acompanhado, depois ~20 min de partida sem tela de draft; a bolha é ligada de novo
        // já com os picks do draft novo acontecendo (perdeu a fase de bans)
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        var now = trackedUntilPick(t, 1_000)
        assertEquals(id("Janna"), draft.myPick)
        now += 20 * 60_000L
        val novo = listOf(line("OPONENTES SELECIONANDO", 568, 18)) +
            ally(0, "AHRI", "Abyss") + ally(1, "SUPORTE", "ixpanka ixposa", gold = true, picked = false) +
            enemy(0, "GAREN") + (1..4).flatMap { enemy(it, null) }
        repeat(2) { t.apply(parser.parse(novo), now); now += 800 }
        assertNull("o pick do draft anterior sumiu", draft.myPick)
        // (limitação conhecida: sem ter visto a fase de bans, o app não sabe a rota do apelido da Ahri)
        assertTrue("nenhum aliado do draft anterior ficou", draft.allies.isEmpty())
        assertEquals(listOf(id("Garen")), draft.enemies)
    }

    // ---- regra dos bans na revelação (E5-02 r3) ----

    private val bansLidos = listOf("Kassadin", "Ashe", "Volibear", "Mordekaiser", "Lulu", "Mel", "Sylas", "Cho'Gath")

    private fun ids(vararg nomes: String) = nomes.map { names.champion(it)!! }.toSet()

    /** Revelação dos bans com estes nomes (um por linha, dentro da faixa central); `titulo` = o título do topo visível. */
    private fun revealOf(nomes: List<String>, titulo: Boolean = true): List<TextLine> =
        listOfNotNull(
            if (titulo) line("BANIMENTOS", 625, 18) else null,
            line("CAMPEÕES BANIDOS — ALIADOS", 552, 134)
        ) + nomes.mapIndexed { i, n -> line(n, 470 + (i % 4) * 95, 200 + (i / 4) * 40) }

    private fun trackerNosBans(): Pair<Draft, DraftTracker> {
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        repeat(2) { t.apply(parser.parse(banPhase()), 1_000L + it * 800) }
        return draft to t
    }

    @Test
    fun primeiraLeituraCompletaJaTrazOsBansReaisEDescartaOManualSobrando() {
        val (draft, t) = trackerNosBans()
        draft.bans.add(id("Ahri")) // toque num cartão de sugestão antes da revelação
        draft.myPick = id("Thresh")
        t.userPicked(id("Thresh"))
        t.apply(parser.parse(revealScreen()), 3_000)
        // sem esperar a 2ª leitura (a janela some por pouco tempo)
        assertEquals(revealBans, draft.bans)
        assertEquals(id("Thresh"), draft.myPick)
        assertEquals(Phase.BAN_REVEAL, t.phase)
    }

    @Test
    fun falsoBanDeLeituraParcialSaeNaSegundaCompletaSemApagarODraft() {
        val (draft, t) = trackerNosBans()
        draft.myPick = id("Thresh")
        t.userPicked(id("Thresh"))
        draft.allies[Lane.MID] = id("Hwei")
        t.userSetAlly(Lane.MID)
        // leitura parcial com dois nomes que não são bans (texto solto)
        t.apply(parser.parse(revealOf(listOf("Kassadin", "Ashe", "Volibear", "Zed", "Teemo"))), 3_000)
        assertEquals(ids("Kassadin", "Ashe", "Volibear", "Zed", "Teemo"), draft.bans)

        val completa = parser.parse(revealScreen())
        t.apply(completa, 3_800)
        assertEquals("1ª completa: os 8 reais entram na hora, os falsos esperam", revealBans + ids("Zed", "Teemo"), draft.bans)
        t.apply(completa, 4_600)
        assertEquals("2ª completa: só os extras saem", revealBans, draft.bans)
        assertEquals("sem draft novo: pick e aliado corrigidos à mão ficam", id("Thresh"), draft.myPick)
        assertEquals(id("Hwei"), draft.allies[Lane.MID])
        assertEquals(Phase.BAN_REVEAL, t.phase)
    }

    @Test
    fun trocaDeBansNaRevelacaoSoRecomecaODraftComTresOuMaisDiferentes() {
        fun rodada(novos: List<String>): Triple<Set<String>, Set<String>, Draft> {
            val (draft, t) = trackerNosBans()
            val velha = parser.parse(revealOf(bansLidos))
            t.apply(velha, 3_000)
            t.apply(velha, 3_800)
            assertEquals(revealBans, draft.bans)
            draft.myPick = id("Thresh")
            t.userPicked(id("Thresh"))
            val nova = parser.parse(revealOf(novos))
            t.apply(nova, 4_600)
            val depoisDa1 = draft.bans.toSet()
            t.apply(nova, 5_400)
            return Triple(depoisDa1, draft.bans.toSet(), draft)
        }
        // 6 diferentes (desistência da fila): 1ª leitura mostra velhos + novos, a 2ª recomeça o draft
        val seis = listOf("Kassadin", "Ashe", "Garen", "Zed", "Yasuo", "Teemo", "Ahri", "Lux")
        val (a1, a2, da) = rodada(seis)
        assertEquals(revealBans + ids(*seis.toTypedArray()), a1)
        assertEquals(ids(*seis.toTypedArray()), a2)
        assertNull(da.myPick)
        // 3 diferentes: ainda é draft novo
        val tres = listOf("Kassadin", "Ashe", "Volibear", "Mordekaiser", "Garen", "Zed", "Yasuo", "Mel")
        val (b1, b2, db) = rodada(tres)
        assertEquals(11, b1.size)
        assertEquals(ids(*tres.toTypedArray()), b2)
        assertNull(db.myPick)
        // 2 diferentes: pode ser ruído de leitura; só troca os bans, o resto do draft fica
        val dois = listOf("Kassadin", "Ashe", "Volibear", "Mordekaiser", "Lulu", "Garen", "Zed", "Cho'Gath")
        val (c1, c2, dc) = rodada(dois)
        assertEquals(10, c1.size)
        assertEquals(ids(*dois.toTypedArray()), c2)
        assertEquals(id("Thresh"), dc.myPick)
    }

    /** Nomes (ordenados) dos bans do draft, para os registros das provas. */
    private fun seq(d: Draft) = d.bans.map { data.champs[it]!!.name }.sorted().joinToString(",")

    @Test
    fun leiturasDeNoveEDezNomesSaoCompletasELegitimas() {
        // E5-02 r4 (P6): o draft tem 10 lugares de ban (pode haver vazio); 8 a 10 nomes = leitura completa.
        // Antes, 9 nomes sozinhos deixavam bans=0 até o fim do draft.
        val nove = bansLidos + "Zed"
        val dez = nove + "Garen"
        assertEquals(9, parser.parse(revealOf(nove)).bans.size)
        assertEquals(10, parser.parse(revealOf(dez)).bans.size)

        // 9 e 10 nomes numa única leitura: entram todos na hora
        for ((lista, esperado) in listOf(nove to 9, dez to 10)) {
            val (d, t) = trackerNosBans()
            t.apply(parser.parse(revealOf(lista)), 3_000)
            println("[P6] após 1 leitura de $esperado nomes: " + seq(d))
            assertEquals(ids(*lista.toTypedArray()), d.bans)
        }

        // e valem como leitura completa: o ban manual sai já na 1ª leitura
        for (lista in listOf(nove, dez)) {
            val (d, t) = trackerNosBans()
            d.bans.add(id("Ahri"))
            t.apply(parser.parse(revealOf(lista)), 3_000)
            assertEquals(ids(*lista.toTypedArray()), d.bans)
        }

        // um lugar que ficou vazio na releitura (8 nomes) tira o ban lido a mais depois de 2 leituras iguais
        val (d0, t0) = trackerNosBans()
        t0.apply(parser.parse(revealOf(nove)), 3_000)
        val completa = parser.parse(revealOf(bansLidos))
        t0.apply(completa, 3_800)
        assertEquals(revealBans + id("Zed"), d0.bans)
        t0.apply(completa, 4_600)
        println("[P6] após 2 leituras de 8 nomes depois de uma de 9: " + seq(d0))
        assertEquals(revealBans, d0.bans)
    }

    @Test
    fun leituraComMaisDeDezNomesAcrescentaNaHoraSemRemoverNemRecomecar() {
        // mais de 10 nomes é impossível (texto solto virou nome): só acrescenta, como a parcial
        val onze = bansLidos + listOf("Zed", "Teemo", "Garen")
        assertEquals(11, parser.parse(revealOf(onze)).bans.size)

        val (d1, t1) = trackerNosBans()
        d1.bans.add(id("Ahri")) // ban manual: só uma leitura completa (8 a 10) o tiraria
        t1.apply(parser.parse(revealOf(onze)), 3_000)
        assertEquals(setOf(id("Ahri")) + ids(*onze.toTypedArray()), d1.bans)
        t1.apply(parser.parse(revealOf(onze)), 3_800)
        assertEquals(setOf(id("Ahri")) + ids(*onze.toTypedArray()), d1.bans)

        // os falsos (3, postos pela leitura) saem numa lista completa de 8, depois de 2 leituras iguais
        val completa = parser.parse(revealOf(bansLidos))
        t1.apply(completa, 4_600)
        assertTrue("1ª completa: só o manual sai, os lidos esperam", id("Ahri") !in d1.bans && id("Zed") in d1.bans)
        t1.apply(completa, 5_400)
        assertEquals(revealBans, d1.bans)
    }

    @Test
    fun banManualSaiNaPrimeiraLeituraCompletaENaoContaParaODraftNovo() {
        // E5-02 r4 (P1): 2 toques em cartões (Ahri, Zed) + 1 falso de leitura parcial (Teemo) somavam 3
        // extras e apagavam o draft (eu, rota marcada à mão)
        val (draft, t) = trackerNosBans()
        draft.bans.add(id("Ahri")); t.userBanned(id("Ahri")) // fase de ban: não é "mantido"
        draft.bans.add(id("Zed")); t.userBanned(id("Zed"))
        draft.myPick = id("Thresh")
        t.userPicked(id("Thresh"))
        draft.allies[Lane.MID] = id("Hwei")
        t.userSetAlly(Lane.MID)
        println("[P1] antes das leituras: " + seq(draft))
        t.apply(parser.parse(revealOf(listOf("Kassadin", "Ashe", "Volibear", "Teemo"))), 3_000)
        println("[P1] após parcial com Teemo falso: " + seq(draft))
        assertEquals(ids("Ahri", "Zed", "Kassadin", "Ashe", "Volibear", "Teemo"), draft.bans)

        val completa = parser.parse(revealOf(bansLidos))
        t.apply(completa, 3_800)
        println("[P1] após 1ª completa: " + seq(draft))
        assertEquals("manuais já saíram; o falso lido espera", revealBans + id("Teemo"), draft.bans)
        t.apply(completa, 4_600)
        println("[P1] após 2ª completa: " + seq(draft))
        assertEquals(revealBans, draft.bans)
        assertEquals("sem draft novo", id("Thresh"), draft.myPick)
        assertEquals(id("Hwei"), draft.allies[Lane.MID])
        assertEquals(Phase.BAN_REVEAL, t.phase)
    }

    @Test
    fun banMarcadoAMaoDepoisDaRevelacaoNaoEApagadoPelasLeituras() {
        val novaLista = bansLidos.map { if (it == "Mel") "Zed" else it }
        // P2: leitura completa troca Mel por Zed; o usuário tira o Zed e marca a Mel pelo "+"
        val (d2, t2) = trackerNosBans()
        t2.apply(parser.parse(revealOf(bansLidos)), 3_000)
        println("[P2] após leitura A: " + seq(d2))
        val b = parser.parse(revealOf(novaLista))
        t2.apply(b, 3_800)
        println("[P2] após leitura B (Mel->Zed): " + seq(d2))
        assertEquals(revealBans + id("Zed"), d2.bans)
        d2.bans.remove(id("Zed")); t2.userRemoved(id("Zed"))
        d2.bans.add(id("Mel")); t2.userBanned(id("Mel"))
        println("[P2] após ajuste manual: " + seq(d2))
        t2.apply(b, 4_600)
        t2.apply(b, 5_400)
        println("[P2] após 2 leituras B seguintes: " + seq(d2))
        assertEquals("a Mel marcada à mão fica; o Zed tirado não volta", revealBans, d2.bans)

        // P5: tira a Mel sem querer e põe de volta pelo "+"
        val (d5, t5) = trackerNosBans()
        val a = parser.parse(revealOf(bansLidos))
        t5.apply(a, 3_000); t5.apply(a, 3_800)
        println("[P5] após 2 leituras A: " + seq(d5))
        d5.bans.remove(id("Mel")); t5.userRemoved(id("Mel"))
        d5.bans.add(id("Mel")); t5.userBanned(id("Mel"))
        t5.apply(a, 4_600); t5.apply(a, 5_400); t5.apply(a, 6_200)
        println("[P5] após Mel tirada/posta e 3 leituras: " + seq(d5))
        assertEquals(revealBans, d5.bans)

        // o "mantido" some junto com o draft (draft novo: 3 extras lidos)
        val seis = listOf("Garen", "Zed", "Yasuo", "Teemo", "Ahri", "Lux", "Jax", "Vayne")
        val nova = parser.parse(revealOf(seis))
        t5.apply(nova, 7_000); t5.apply(nova, 7_800)
        assertEquals(ids(*seis.toTypedArray()), d5.bans)
        // a Mel não é mais "mantida" (zerou com o draft): como ban manual comum, a próxima leitura completa a tira
        d5.bans.add(id("Mel"))
        t5.apply(nova, 8_600)
        assertEquals(ids(*seis.toTypedArray()), d5.bans)
    }

    @Test
    fun desistenciaNaEscolhaSemPicksLidosZeraOsBansVelhos() {
        // E5-02 r4 (P3): revelação velha -> escolha sem nenhum pick lido -> nova fase de ban -> nova revelação
        val (draft, t) = trackerNosBans()
        val velha = parser.parse(revealOf(bansLidos))
        t.apply(velha, 3_000); t.apply(velha, 3_800)
        println("[P3] após revelação velha: " + seq(draft))
        assertEquals(revealBans, draft.bans)
        val escolha = parser.parse(listOf(line("EQUIPE SELECIONANDO", 586, 18)) + ally(0, "ROTA DO MEIO", "Abyss", picked = false))
        assertEquals(Phase.PICK, escolha.phase)
        var now = 4_600L
        repeat(2) { t.apply(escolha, now); now += 800 }
        assertEquals(Phase.PICK, t.phase)
        assertEquals(revealBans, draft.bans)
        // nova fase de ban: a 1ª leitura ainda espera, a 2ª zera
        t.apply(parser.parse(banPhase()), now); now += 800
        println("[P3] após 1ª leitura da nova fase de ban: " + seq(draft))
        assertEquals(revealBans, draft.bans)
        t.apply(parser.parse(banPhase()), now); now += 800
        println("[P3] após 2ª leitura da nova fase de ban: " + seq(draft))
        assertTrue(draft.bans.isEmpty())
        t.apply(parser.parse(banPhase()), now); now += 800
        val novos = listOf("Garen", "Zed", "Yasuo", "Teemo", "Ahri", "Lux", "Jax", "Vayne")
        t.apply(parser.parse(revealOf(novos)), now)
        println("[P3] após 1 leitura da nova revelação: " + seq(draft))
        assertEquals("sem mistura de 16 bans", ids(*novos.toTypedArray()), draft.bans)
        assertEquals(Phase.BAN_REVEAL, t.phase)
    }

    @Test
    fun tituloCobertoComBanidosEOSemCampeaoConfirmadoAindaELeituraDeBans() {
        // a bolha cobre o título "BANIMENTOS" do topo, mas o texto "CAMPEÕES BANIDOS" aparece e nenhum
        // campeão está confirmado nos times: é a revelação dos bans
        val semTitulo = parser.parse(revealScreen().drop(1))
        assertEquals(Phase.BAN_REVEAL, semTitulo.phase)
        assertTrue(!semTitulo.hasChamps)
        val (draft, t) = trackerNosBans()
        t.apply(semTitulo, 3_000)
        assertEquals(Phase.BAN_REVEAL, t.phase)
        assertEquals(revealBans, draft.bans)
    }

    @Test
    fun telaDeCarregamentoNaoCompletaInimigosComCampeaoBanido() {
        val draft = Draft().apply { myLane = Lane.SUP }
        val t = DraftTracker(draft)
        val pick = listOf(line("OPONENTES SELECIONANDO", 568, 18)) +
            ally(0, "LISSANDRA", "Abyss") + ally(1, "JANNA", "ixpanka ixposa", gold = true) +
            enemy(0, "AURORA") + enemy(1, "JINX") + (2..4).flatMap { enemy(it, null) }
        t.apply(parser.parse(pick), 1000)
        t.apply(parser.parse(pick), 1600)
        assertEquals(2, draft.enemies.size)
        draft.bans.add(id("Veigar")) // banido: não pode estar na fileira inimiga

        val loading = listOf(
            line("Darius Mestre da", 277, 179), line("Shyvana Dragão de", 443, 179),
            line("Lissandra de", 637, 179), line("Kog'Maw", 819, 196),
            line("Janna Guardiã Estelar", 960, 197),
            line("K'Sante", 302, 503), line("Kayn Emissário da", 447, 486),
            line("Aurora", 652, 503), line("Jinx Felina de Batalha", 786, 502),
            line("Veigar Noel Macabro", 960, 504)
        )
        t.apply(parser.parse(loading), 30_000)
        assertTrue(id("Veigar") !in draft.enemies)
        assertEquals(ids("Aurora", "Jinx", "K'Sante", "Kayn"), draft.enemies.toSet())
    }
}
