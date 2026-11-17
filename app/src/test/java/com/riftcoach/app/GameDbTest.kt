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

    // ---------------------------------------------- padrões de fórmula (etapa 5)

    private fun fx(key: String, slot: Int) = hero(key).skills.first { it.slot == slot }.effects

    @Test
    fun faixaDeDanoMinimoEMaximo() {
        // ultimate da Jinx: "25至250（25+12%额外攻击力至250+120%额外攻击力）": base = mínimo, upTo = máximo
        val r = fx("Jinx", 4)[0]
        println("Jinx R: " + fx("Jinx", 4).joinToString(" | ") { it.describe() })
        assertEquals("Dano físico", r.kind)
        assertEquals(listOf(25.0, 35.0, 45.0), r.base)
        assertEquals("AD bônus", r.ratios[0].stat)
        assertEquals(12.0, r.ratios[0].pct[0], 0.01)
        assertEquals(listOf(250.0, 350.0, 450.0), r.upTo!!.base)
        assertEquals(120.0, r.upTo!!.ratios[0].pct[0], 0.01)
        // Q2 do Lee Sin, com "到": "60到120（60+90%额外攻击力到120+180%额外攻击力）"
        val q = fx("LeeSin", 1).first { it.upTo != null }
        assertEquals("Dano físico: 60/100/140/180 + 90% AD bônus até 120/200/280/360 + 180% AD bônus", q.describe())
    }

    @Test
    fun extraAntesDaPorcentagemEEspacos() {
        // "30+额外25%攻击力" = 25% do AD bônus
        assertEquals("Dano verdadeiro: 30/35/40/45 + 25% AD bônus", fx("MasterYi", 3).first { it.kind == "Dano verdadeiro" }.describe())
        assertEquals("Dano físico: 20/40/60/80 + 50% AD bônus", fx("Samira", 2)[0].describe())
        // "70+60%额外 攻击力" (espaço no meio)
        assertEquals("Dano físico: 70/100/130/160 + 60/65/70/75% AD bônus", fx("Kayn", 1)[0].describe())
    }

    @Test
    fun armourRatioEArmadura() {
        assertEquals("Dano mágico: 60/110/160/210 + 45% AP + 40% armadura", fx("Malphite", 3)[0].describe())
        // "10+10%额外armour_ratio" e "10+10%额外魔法抗性加成"
        assertEquals(listOf("Valor: 10/15/20/25 + 10/12/14/16% armadura bônus", "Valor: 10/15/20/25 + 10/12/14/16% RM bônus"), fx("Braum", 2).map { it.describe() })
        // "所受物理伤害减少1（…）" é redução de dano, não dano
        assertEquals("Redução de dano: 1/3/5/7 + 4% armadura bônus + 4% RM bônus", fx("Amumu", 3)[0].describe())
    }

    @Test
    fun atributoSemSubstantivoFicaDeduzido() {
        // "（65+20%额外）": vida bônus, marcada como deduzida
        val w = fx("Garen", 2)[0]
        assertEquals("Escudo: 65/95/125/155 + 20% vida bônus*", w.describe())
        assertTrue(w.ratios[0].guessed)
        // "可吸收146伤害（70+11%最大）": escudo de vida máxima
        assertEquals("Escudo: 70/80/90/100 + 11/12/13/14% vida máx.*", fx("Nautilus", 2)[0].describe())
        // "15%目标最大" = vida máx. do alvo
        assertEquals("Dano físico: 150/250/350 + 100% AD bônus + 15/17.5/20% vida máx. do alvo*", fx("Reksai", 4)[0].describe())
    }

    @Test
    fun danoForaDosParentesesComGatilho() {
        // W da Vayne: "连续三次的普通攻击…额外造成相当于其6%最大生命值的真实伤害"
        val w = fx("Vayne", 2)
        println("Vayne W: " + w.joinToString(" | ") { it.describe() })
        assertEquals(1, w.size)
        assertEquals("Dano verdadeiro", w[0].kind)
        assertEquals("vida máx. do alvo", w[0].ratios[0].stat)
        assertEquals(listOf(6.0, 7.0, 8.0, 9.0), w[0].ratios[0].pct)
        assertTrue(w[0].base.all { it == 0.0 })
        assertEquals("acertos", w[0].trigger!!.type)
        assertEquals(3.0, w[0].trigger!!.n, 0.01)
        // passiva do Zed: só em alvo com menos de 50% da vida
        val z = fx("Zed", 0)[0]
        assertEquals("Dano mágico: 7% vida máx. do alvo + valor que cresce com o nível (alvo abaixo de 50% da vida)", z.describe())
        // valor fixo escrito antes ("相当于30加上其当前生命值的15%的魔法伤害")
        assertEquals("Dano mágico: 30 + 15/20/25% vida atual do alvo", fx("Camille", 4)[0].describe())
        // escudo escrito fora dos parênteses ("吸收100+5%最大生命值伤害的护盾") e o aumento por inimigo ("增加10（1.6%最大）")
        assertEquals(
            listOf("Escudo: 100/140/180/220 + 5% vida máx.*", "Escudo: 1.6/1.9/2.2/2.5% vida máx.*"),
            fx("JarvanIV", 2).map { it.describe() }
        )
        // "总伤害为20加上目标9%最大生命值" (Yone W)
        assertEquals("Dano: 20/35/50/65 + 9/10/11/12% vida máx. do alvo", fx("Yone", 2)[0].describe())
        // "相当于他们10%最大生命值加上0(100%额外攻击力)的魔法伤害" (Jayce E)
        assertEquals("Dano mágico", fx("Jayce", 3)[0].kind)
        assertEquals(listOf("vida máx. do alvo", "AD bônus"), fx("Jayce", 3)[0].ratios.map { it.stat })
        // "（7% - 22%随等级成长）": cresce com o nível até 22% (Ornn W)
        val ornn = fx("Ornn", 2)
        assertEquals(2, ornn.size)
        assertEquals("Dano mágico: 7% vida máx. do alvo* + valor que cresce com o nível até 22% vida máx. do alvo*", ornn[1].describe())
        // "治疗自身最大生命值的9% ~ 18%（随等级成长）" (Rek'Sai P)
        assertEquals("Cura: 9% vida máx. + valor que cresce com o nível até 18% vida máx.", fx("Reksai", 0).single().describe())
    }

    @Test
    fun gatilhos() {
        // "每完成6次普攻" (Caitlyn P): não é "no mesmo alvo"
        val cait = fx("Caitlyn", 0)[0].trigger!!
        assertEquals("acertos", cait.type)
        assertEquals(6.0, cait.n, 0.01)
        assertTrue(!cait.sameTarget)
        // "对同个目标每使用三次普攻…" (Ekko P), "对同一个目标造成3次伤害" (Aurora P)
        assertEquals("Dano mágico: 30 + 80% AP (a cada 3 acertos no mesmo alvo)", fx("Ekko", 0)[0].describe())
        assertEquals("acertos", fx("Aurora", 0)[0].trigger!!.type)
        // "连续挥击3次" = 3 golpes de uma vez, não "a cada 3 acertos" (Pantheon E)
        assertEquals("Dano físico: 135% AD", fx("Pantheon", 2)[1].describe())
        // "每过12秒就会强化…" = recarga; "每隔0.75秒会扩散出一道音波…造成" = tique
        assertEquals("intervalo", fx("Nocturne", 0)[0].trigger!!.type)
        assertEquals("Dano mágico: 35/60/85 + 15% AP (a cada 0.75 s, por tique)", fx("Sona", 4)[0].describe())
        assertEquals("vidaBaixa", fx("Kogmaw", 4)[1].trigger!!.type)
    }

    @Test
    fun m1EscudoEReducaoComOutrasPalavras() {
        // "可吸收70魔法伤害（…）" (Morgana E)
        assertEquals("Escudo: 70/150/230/310 + 50% AP", fx("Morgana", 3).single().describe())
        // "使其免受25伤害（…）" e "免受额外40伤害（…）" (Lux W)
        assertEquals(listOf("Escudo", "Escudo"), fx("Lux", 2).map { it.kind })
        assertEquals("Escudo", fx("Orianna", 3)[0].kind)
        assertEquals("Escudo", fx("Seraphine", 2)[0].kind)
        // "使受到的伤害减免60%（60+7%法术强度）" (Irelia W)
        assertEquals("Redução de dano: 60 + 7% AP", fx("Irelia", 2)[0].describe())
        // "护盾可吸收的伤害提升至50（…）" (Viktor Q evoluído)
        assertEquals("Escudo: 50/90/130/170 + 25% AP", fx("Viktor", 1)[3].describe())
        // "吸收132物理/魔法伤害（20%）" (Camille P): escudo só com "%", sem dizer de quê
        assertEquals("Escudo", fx("Camille", 0)[0].kind)
        assertEquals("20%", fx("Camille", 0)[0].raw)
    }

    @Test
    fun m2DanoSoEmMonstrosOuTropas() {
        // "· 对小兵和野怪造成1200真实伤害（…）" (Cho'Gath R)
        val r = fx("Chogath", 4)
        assertEquals("Dano verdadeiro: 300/450/600 + 50% AP + 10% vida bônus*", r[0].describe())
        assertEquals(GameDbParser.MONSTERS, r[1].kind)
        // "如果目标是野怪，会对其造成额外300魔法伤害（…）" (Evelynn E)
        assertEquals(GameDbParser.MONSTERS, fx("Evelynn", 2).single().kind)
        // limite contra monstros no meio de uma habilidade (Skarner Q, 5º efeito)
        assertEquals(GameDbParser.MONSTERS, fx("Skarner", 1)[4].kind)
    }

    @Test
    fun m3VidaSoECuraComVerboDeCura() {
        // "获得250额外生命值（…）" (Lulu R) e "有30生命值（…）的荆棘喷射者" (Zyra P): vida, não cura
        assertEquals("Valor: 250/400/550 + 50% AP", fx("Lulu", 4).single().describe())
        assertEquals("Valor: 30 + 22% AP", fx("Zyra", 0)[0].describe())
        // "进行50生命值（…）的治疗" (Soraka W): cura, com o verbo depois
        assertEquals("Cura: 50/85/120/155 + 55% AP", fx("Soraka", 2)[0].describe())
        // "回复效果将提升至100（…）" (Bard W)
        assertEquals("Cura: 100/150/200/250 + 60% AP", fx("Bard", 2).last().describe())
    }

    @Test
    fun m4FaixaEmDoisParenteses() {
        // "150（150+40%额外攻击力）至400（400+80%额外攻击力）" (Sion R): um efeito só, com mínimo e máximo
        assertEquals("Dano físico: 150/300/450 + 40% AD bônus até 400/800/1200 + 80% AD bônus", fx("Sion", 4).single().describe())
        assertEquals("Dano físico", fx("Sion", 1).single().kind)
        assertEquals(1, fx("Riven", 4).size)
        assertEquals(1, fx("Diana", 4).size)
        assertEquals("Dano mágico: 48/83/118/153 + 60% AP até 120/207.5/295/382.5 + 150% AP", fx("Zoe", 1).single().describe())
        // "…最多提升至回复60生命值（…）" (Sylas W)
        assertEquals("Cura: 30/60/90/120 + 30% AP + 5% vida bônus* até 60/120/180/240 + 60% AP + 10% vida bônus*", fx("Sylas", 2)[1].describe())
    }

    @Test
    fun m5RyzeEscalaComManaBonus() {
        // o texto do Ryze fala em "额外法力值": o "额外" sem substantivo é mana bônus
        assertEquals("Dano mágico: 70/95/120/145 + 45% AP + 2% mana bônus*", fx("Ryze", 1)[0].describe())
        assertTrue(fx("Ryze", 2)[0].ratios.any { it.stat == "mana bônus" } && fx("Ryze", 3)[0].ratios.any { it.stat == "mana bônus" })
        // para os outros continua vida bônus
        assertEquals("vida bônus", fx("Garen", 2)[0].ratios[0].stat)
    }

    @Test
    fun m6PorcentagemSemAtributo() {
        // "（75+40%法术强度+10%）" (Sion W): % sem nome ao lado de atributo nomeado não é AD/AP
        assertEquals("Escudo: 75/100/125/150 + 40% AP + 10/12/14/16% vida máx.*", fx("Sion", 2)[0].describe())
        // escudo sem atributo numa habilidade de dano mágico (Diana W): AP
        assertEquals(listOf("Escudo: 50/70/90/110 + 40% AP*", "Escudo: 50/70/90/110 + 40% AP*"), fx("Diana", 2).drop(1).map { it.describe() })
        // "护盾（8%）" (Galio W passiva) e "6%额外护盾与治疗强度（6%+0.01%法术强度）" (Yuumi E): sem interpretar
        assertEquals("8%", fx("Galio", 2)[0].raw)
        assertEquals("6%+0.01%法术强度", fx("Yuumi", 2).single().raw)
    }

    @Test
    fun m7OutroAtributoEntreOTipoEOParentese() {
        // "获得5%（5+4%法术强度）%攻击速度" (Volibear P), "额外提供10攻击力（…）" (Janna E), "伤害储存10%（…）" (Pyke P)
        assertEquals("Valor: 5 + 4% AP", fx("Volibear", 0)[0].describe())
        assertEquals("Valor: 10/20/30/40 + 10% AP", fx("Janna", 3)[1].describe())
        assertEquals("Valor", fx("Pyke", 0)[0].kind)
    }

    @Test
    fun recomendadasDoInspetor() {
        // "50+8%（8%+…）目标最大生命值" (K'Sante W): a base 50 entra
        assertEquals("Dano físico: 50/80/110/140 + 8% vida máx. do alvo [% cresce +0.02% armadura bônus +0.02% RM bônus]", fx("Ksante", 2)[0].describe())
        // "伤害值相当于64（100%攻击力）" (Zed R): o tipo é o "物理伤害" de antes
        assertEquals("Dano físico: 100% AD", fx("Zed", 4).single().describe())
        // "造成30（…）外加2%…的物理伤害" (Ambessa Q)
        assertEquals("Dano físico: 30/40/50/60 + 30% AD bônus", fx("Ambessa", 1)[0].describe())
        // "30%法力加成" (Blitzcrank P)
        assertEquals("Escudo", fx("Blitzcrank", 0)[0].kind)
        assertEquals("mana máx.", fx("Blitzcrank", 0)[0].ratios[0].stat)
        // "120+50%法术强度+每层黑雾可提供2" (Senna R): sem o termo dos acúmulos
        assertEquals("Escudo: 120/160/200 + 50% AP", fx("Senna", 4)[0].describe())
        // 3 casas decimais no texto ("0.055")
        assertEquals("Dano verdadeiro: 4% vida máx. do alvo [% cresce +0.055% AD bônus]", fx("Fiora", 0)[0].describe())
    }

    @Test
    fun porcentagemDeAtributoEscritoForaDoParentese() {
        // ultimate do Garen: "150加上目标已损失生命值15%（15%+0.12%额外攻击力）的真实伤害"
        val r = fx("Garen", 4)[0]
        assertEquals("Dano verdadeiro: 150/250/350 + 15/20/25% vida perdida do alvo [% cresce +0.12% AD bônus]", r.describe())
        assertEquals("AD bônus", r.ratios[0].grow[0].stat)
        assertEquals(0.12, r.ratios[0].grow[0].pct[0], 0.0001)
        // Q do Shen: "12 加上相当于目标最大生命值3%（3+1.5%法术强度）的魔法伤害"
        assertEquals("Dano mágico: 12 + 3/3.5/4/4.5% vida máx. do alvo [% cresce +1.5% AP]", fx("Shen", 1)[0].describe())
        // atributo depois do parêntese: "相当于目标（14%+5%攻击力）已损失生命值的物理伤害"
        assertEquals("Dano físico: 14/17/20/23% vida perdida do alvo [% cresce +5% AD]", fx("Viego", 4)[1].describe())
        // porcentagem de algo que não é atributo ("豪意消耗25%（25%+…）"): fica sem interpretar
        assertTrue(fx("Sett", 2).any { it.raw == "25%+0.275%额外攻击力" })
    }

    @Test
    fun tipoDoEfeitoPeloContexto() {
        // "可吸收70伤害（70+150%法术强度）" = escudo
        assertEquals(listOf("Dano mágico: 3% vida perdida do alvo* [% cresce +0.025% AP]", "Escudo: 70/100/130/160 + 150% AP"), fx("Ekko", 2).map { it.describe() })
        // tipo escrito depois do parêntese: "造成118（8+176%攻击力+10.4%额外）物理伤害"
        assertEquals("Dano físico: 8/48/88/128 + 176% AD + 10.4% vida bônus*", fx("Volibear", 2)[1].describe())
        // limite de dano em quem tem "英雄" na frase ("对同一个敌方英雄或野怪最多可造成524…"): "Limite", não dano
        assertEquals("Limite", fx("Heimerdinger", 4)[3].kind)
        // limite de execução ("低于250（250+80%额外攻击力+250%）生命值的敌方英雄"): não é cura e fica sem interpretar
        val pyke = fx("Pyke", 4).single()
        assertEquals("Valor", pyke.kind)
        assertEquals("250+80%额外攻击力+250%", pyke.raw)
    }

    @Test
    fun coberturaNaoPiora() {
        val all = db.heroes.values.flatMap { h -> h.skills.flatMap { it.effects } }
        val brutos = all.count { it.raw != null }
        val deduzidos = all.count { e -> e.ratios.any { it.guessed } }
        val danoSemTipo = all.count { it.raw == null && it.kind == "Dano" }
        println(
            "efeitos ${all.size}, não interpretados $brutos, deduzidos $deduzidos, dano sem tipo $danoSemTipo, " +
                "com faixa ${all.count { it.upTo != null }}, com gatilho ${all.count { it.trigger != null }}"
        )
        // antes da etapa 5: 879 efeitos, 118 não interpretados, 60 deduzidos
        assertTrue("efeitos: ${all.size}", all.size >= 900)
        assertTrue("não interpretados: $brutos", brutos <= 56)
        assertTrue("deduzidos: $deduzidos", deduzidos <= 68)
        assertTrue("dano sem tipo: $danoSemTipo", danoSemTipo <= 10)
        // cada efeito tem um número por nível da habilidade (ou um só, quando os níveis não se alinham)
        for (h in db.heroes.values) for (s in h.skills) for (e in s.effects) {
            if (e.raw != null) continue
            assertTrue("${h.id} ${s.slotLabel}: ${e.describe()}", e.base.size == s.ranks || e.base.size == 1)
            assertTrue("${h.id} ${s.slotLabel}: ${e.describe()}", e.ratios.all { it.pct.size == e.base.size })
        }
        // nenhuma fórmula de uma frase que só fala de tropas/monstros ("对小兵和野怪造成1200…") virou dano
        val clause = Regex("[^。；;·\\n]*")
        for (h in db.heroes.values) for (s in h.skills) {
            for (c in clause.findAll(s.textCn).map { it.value }) {
                val f = Regex("[（(](\\d+(?:\\.\\d+)?)\\+[^）)]*%").find(c) ?: continue
                // o que vem antes da fórmula diz em quem ela bate
                val before = c.substring(0, f.range.first)
                if (!(before.contains("野怪") || before.contains("小兵")) || before.contains("英雄")) continue
                val n = f.groupValues[1].toDouble()
                val bad = s.effects.filter { it.raw == null && it.kind.startsWith("Dano") && it.base.firstOrNull() == n }
                assertTrue("${h.id} ${s.slotLabel}: \"$c\" virou ${bad.map { it.describe() }}", bad.isEmpty())
            }
        }
    }
}
