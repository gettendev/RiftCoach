# E5-03 — Plano técnico da calibração do motor de builds

Autor: Arquiteto de Software (Opus · high) · 17/11/2026 · base: commit `ce2d18f` (+ `meta_reference.json` com a correção do analista: Diadem of Songs = **2119**).
Escopo: `BuildEngine` (itens/botas), o que afeta o `RuneEngine` (as runas usam a mesma conta `score()`), e o contrato mínimo do parser (`GameDb.kt`) para isso.
Regra que vale para tudo: **ajustar o modelo, não decorar builds** (`.claude/rules/codigo-android.md`). Nenhuma dependência nova.

Legenda de certeza: **[confirmado]** visto no código/dados · **[dedução]** conclusão a partir do código, não medida · **[hipótese]** a confirmar pela medida do passo 1.

---

## 0. Diagnóstico (como está hoje)

### 0.1 Onde o motor decide
| O quê | Onde | Observação |
|---|---|---|
| Perfil (arquétipo, `autoUptime`, janela da luta) | `BuildEngine.kt:120-155` | `AUTO_UPTIME` é uma tabela **por campeão** (`:1220-1225`) |
| Contexto da partida (alvos, mistura de dano inimigo, cc/cura/escudo/mergulho, aliados) | `BuildEngine.kt:197-277` | Inimigos desconhecidos → 3 alvos genéricos, **mas `ccCount = 0`, sem curadores, sem aliados** (`:214-262`) [confirmado] |
| Dano das habilidades | `BuildEngine.kt:540-572` | Só efeitos `Dano*` sem `raw`; usos `n = min(6, 1 + (T−0,5)/recarga)`; **"a maior fórmula inteira + 0,35 × as outras"** (`:567-571`) |
| Passiva com dano | `BuildEngine.kt:574-578` | só o **1º** efeito de dano, × `min(4, usos×0,6 + ataques×0,15)` — gatilhos ignorados |
| Valor de um atributo numa fórmula | `BuildEngine.kt:453-471` | `"mana bônus"` e `"vel. de movimento bônus"` caem no `else -> 0.0` [confirmado] |
| Cura/escudo do kit (encantadores) | `BuildEngine.kt:696-716` | pega só o maior efeito por habilidade; não usa gatilho/faixa |
| Nota final | `BuildEngine.kt:780-928` | `off^a · def^b · util` (expoentes `:912-925`; peso da utilidade `:902-908`) |
| Estilos de item × campeão | `BuildEngine.kt:936-1016`, `OFF_STYLE = 0.82` (`:1186`) | tabela `STYLES` por item (`:1195-1213`) |
| Filtros | `allowed()` `:1018-1045`; candidatos `:1052` | candidatos = tier 3, preço ≥ 2000, sem `hide`, sem bota → **itens iniciais de suporte (2164/2165, preço 500, `hide`) nunca entram** [confirmado] |
| Botas | `BuildEngine.kt:1124-1140` | escolhidas pela nota no nível 15 |
| Aceleração de feitiço | `stats()` `:383-386` | só trata `haste` com `k = "ult"/"basic"`; o `k = "summ"` das **Botas Ionianas (2407)** é ignorado; regeneração de mana não existe no modelo [confirmado] |
| Runas | `RuneEngine.kt:110-181` | avaliadas com `be.score()` nos níveis 6/11/15 → **toda mudança no `fight()` mexe nas runas** |

### 0.2 Campos que o parser já marca e o motor não usa (`GameDb.kt`)
- `Ratio.grow` (`:16`), `Trigger(type, n, sameTarget)` (`:26`), `Effect.upTo` / `Effect.trigger` (`:37-50`).
- Gatilhos possíveis: `acertos`, `intervalo`, `tique`, `vidaBaixa` (`trigger()` `:548-563`).
- O que **falta** no contrato para o motor usar bem: duração do `tique`; de quem é a vida baixa; se os acertos são só ataques; se dois efeitos da mesma habilidade **somam** ou são **alternativos**; quantos golpes um uso dá; por que existe a faixa `upTo` (nível × distância/vida/carga).

### 0.3 O que os dados mostram [confirmado, saída de 17/11 em `scratchpad\prog\build_r2.txt` + `meta_reference.json`]
- `meta_reference.json`: 43 campeões, 55 rotas; por rota: 2 páginas de runas (`names` = [essencial, 3 do caminho, secundária], `primary`, `secondary`, `wr`, `pick`), 2 pares de feitiços, **3 conjuntos de 3 itens** (`cores`), 2 botas (Akali Barão sem botas). Nomes casam 100% com `itemIds`/`runeIds`/`spellIds`. Campeão ↔ `Champion.key`: `slug` sem hífen = `key` em minúsculas (ex.: `lee-sin` ↔ `LeeSin`).
- **Itens iniciais de suporte estão dentro dos conjuntos**: em todas as 12 rotas de Suporte o 1º item é Foice da Névoa Negra (2164) ou Baluarte da Montanha (2165). O motor não os oferece.
- **Formas evoluídas na referência**: Muramana 2263 (de Manamune 2262), Seraph 2154 (de Cajado do Arcanjo 2142), Diadema evoluído 2119 (de 2118). Na base, a forma evoluída tem `hide: true` no `items_model.json` e `from` = um único item tier 3 (também Fimbulwinter 2356 ← 2355). As botas melhoradas 2412-2418 estão ligadas pelo campo `up` da bota base. O campo `into` da base está vazio para todos.
- Itens mais frequentes nos conjuntos (contagem em 165 conjuntos): Rabadon 23, Espada do Rei Destruído 22, Força da Trindade 21, Foice 19, O Coletor 18, Lembrete Mortal 17, Baluarte 17, Eclipse 16, Rancor de Serylda 16… **Dilacerador Divino aparece 1 vez**, mas o motor o põe em 1º para Garen, Darius, Lee Sin e Yasuo.
- Saídas atuais vs. referência (resumo):
  - Garen: Dilacerador Divino, Quebrapassos, Dança da Morte, Sterak, Cutelo Negro + Mercúrio · real: Cutelo Negro, Quebra-cascos, Couraça do Defunto + Placas (85 %).
  - Ahri: Liandry, Orbe Infinito, Flor Sepulcral, Criafendas, Rabadon + Botas de Mana · real: Luden, Perdição do Lich, Rabadon + Mercúrio 43 % / Ionianas 32 %.
  - Lee Sin: Dilacerador, Cutelo, Sterak, Serylda, Quebrapassos + Mercúrio · real: Eclipse, Céu Dividido, Lembrete Mortal + Glutonas 84 %.
  - Malphite Barão: Rookern, Amaranto, Virtude Radiante, Gargolítica, Warmog · real: Espinhos, Manopla Glacial, Coração Congelado.
  - Soraka: Mandato, Hélia, Mikael, Eco Harmônico, Redenção + Mercúrio · real: Foice, Diadema, Turíbulo + Ionianas 88 %.

### 0.4 Causas prováveis [dedução/hipótese — o passo 1 mede]
1. **Contexto vazio quando o inimigo é desconhecido**: sem cc esperado, Passos de Mercúrio e tenacidade perdem sentido; sem curadores esperados, Lembrete Mortal (17 conjuntos) vale 0; sem atirador aliado esperado, o Turíbulo (`ally as`, depende de `allyAutoers`) vale pouco.
2. **Itens de "lâmina encantada"** (`on = "blade"`, `BuildEngine.kt:590`) contam `min(recarga, usos, ataques)`; o Dilacerador (10 % da vida máx. do alvo + cura 6 %) ganha da Força da Trindade em todo lutador; a velocidade de movimento da Trindade (passiva Bravura) não está no modelo (`items_model.json` 2244).
3. **Velocidade de movimento quase não vale** (só entra em `statValue` "vel. de movimento"): Couraça do Defunto, Quebrapassos, Força da Natureza, Trindade ficam sem o motivo real (chegar no alvo).
4. **Empurrar torre** (Quebra-cascos tem `tags: split`) não entra na nota de itens; para runas existe `utilValue("tower")`.
5. **Suportes**: item inicial fora dos candidatos; Botas Ionianas valem só 15 de aceleração (feitiços e mana ignorados); encantador tem `b = 0,3` de defesa, alto para quem fica atrás → Mercúrio.
6. **Golpes subestimados** (faixas no mínimo, golpes que somam tratados como variante, giros/tiques contados uma vez) e **condicionais superestimadas** (passivas com gatilho contadas como contínuas) — piorou as runas (Zed Selva, Lee Sin) depois do bloco das fórmulas.

---

## 1. Medida de concordância (teste novo)

### 1.1 Arquivos
- `app/src/test/java/com/riftcoach/app/MetaReference.kt` — leitor da referência e funções de pontuação **puras** (sem o motor), testáveis com JSON pequeno.
- `app/src/test/java/com/riftcoach/app/MetaReferenceTest.kt` — testes das regras de pontuação (empate, bota ausente, forma evoluída, id sem ligação).
- `app/src/test/java/com/riftcoach/app/ConcordanciaTest.kt` — roda os 55 casos, imprime o placar e grava `app/build/reports/concordancia/concordancia.txt` e `.csv` (pasta de build, não versionada).
- `app/src/test/meta/concordancia-antes.txt` — cópia do resultado do passo 1 (versionada; é o "antes").

### 1.2 Como cada caso roda
- Draft **neutro**: `myPick` = campeão, `myLane` = rota, inimigos e aliados vazios. É o mais próximo da referência, que é a média de todas as partidas. (Depois do passo 5, "vazio" passa a significar "time médio do meta" — ver §3.1.)
- Motor completo: `BuildEngine(db, data, itemModels, runeData).build(d)` (itens + botas + runas + feitiços), medindo o tempo de cada caso.
- Os 4 testes de draft específico que já existem (`RuneEngineTest.comparaComOMeta`, `BuildEngineTest.buildSeAdaptaAoTimeInimigo`) continuam como estão (continuidade e "a build muda com o inimigo").

### 1.3 Normalização de ids (resolve o par Diadema 2118 ↔ 2119)
`canonical(id)`, derivado dos dados, sem lista fixa:
1. se `items_model[id].hide == true` e `db.item(id).from` tem **um** item de tier 3 → devolve esse item (2119→2118, 2263→2262, 2154→2142, 2356→2355);
2. se `id` é o `up` de alguma bota → devolve a bota base (2412-2418 → 2403-2410);
3. senão, o próprio id (2164/2165 continuam como estão: são o item inicial que o motor vai passar a sugerir).
Aplicar `canonical` dos **dois lados** (referência e motor). O teste imprime a tabela de equivalências encontrada (para o inspetor ver que é só isso).

### 1.4 Ids sem ligação
- Nome sem entrada no mapa, ou id que não existe em `db.items`/`db.runes`/`db.spells` → o item sai do denominador daquele conjunto e conta em "sem ligação" (impresso por caso e no total). Não falha o teste.
- Teste de sanidade separado (este sim falha): todos os 43 campeões acham `Champion` e `HeroDetail`; nenhum id do mapa repetido; o caminho principal derivado dos ids das 3 runas do caminho (`Rune.path`) bate com o campo `primary` (`"Resolve"` ↔ `"RESOLVE"`).
- O plano não depende de ids específicos: o analista pode corrigir os mapas sem mexer no teste.

### 1.5 Como pontuar (por caso)
Notação: `p_k` = pickrate da opção k da referência; `p_1` = da mais popular. "Crédito ponderado" de uma opção = `min(1, p_k / p_1)`; se `p_k ≥ 0,85·p_1` conta como **empate** (crédito 1 também no estrito).

| Componente | Estrito (legível) | Ponderado (0–1) |
|---|---|---|
| Itens | `|S ∩ C_1|` de 3 (S = 5 itens do motor + inicial de suporte, canônicos) | `Σ_k p_k · |S∩C_k|/|C_k| / Σ_k p_k` (os 3 conjuntos) |
| Botas | igual à popular? | crédito ponderado da bota do motor (0 se não listada); caso sem botas → fora |
| Essencial | igual à da página popular? | maior crédito entre as 2 páginas que têm essa essencial |
| Caminho | igual? | idem |
| Runas do caminho | quantas das 3 | `max_k crédito_k · acertos_k/3` |
| Secundária | igual? | idem |
| Feitiços | par igual ao popular? | `max_k crédito_k · |S∩F_k|/2` |

Nota única (para o CEO, 0–100): `100 × (0,40·itens + 0,15·botas + 0,30·runas + 0,15·feitiços)`, com runas = média dos 4 componentes ponderados. Feitiços também com total "sem a selva" (Golpear+Flash é regra fixa).

### 1.6 Amostras pequenas
A referência não traz número de partidas, só porcentagens. Regra: caso **"referência fraca"** quando o conjunto de itens popular tem `pick < 12 %` **ou** a página de runas popular tem `pick < 20 %` (ex.: Garen 10,3 %, Yasuo Barão 9,9 %, Vayne Barão 8,5 %, Amumu 10,0 %). Totais impressos **com e sem** os casos fracos. Winrate **não** entra na pontuação (ruidoso); só é impresso.

### 1.7 Diagnóstico "por que errou" (o que guia a calibração)
Para cada item do conjunto popular que o motor deixou de fora, uma razão:
- `bloqueado` — não é candidato ou `allowed()` recusou (diz qual regra);
- `estilo` — `styleFit < 1` (diz o fator);
- `contas −X %` — troca pelo item do motor que menos perde: `value(S − pior + item) / value(S) − 1`.
Exige expor o `value(set)` hoje local em `build()` (`BuildEngine.kt:1057-1061`) como `internal fun setValue(p, set, ctx, styles)` — **refatoração sem mudança de resultado** (provar: as 55 saídas idênticas antes/depois).
Mesmo diagnóstico, mais simples, para botas e essencial (diferença de nota entre a escolhida e a popular).

### 1.8 Saída
Uma linha por caso: `campeão rota grupo [T/V] [fraca] | itens 2/3 0,61 | botas S 1,00 | ess S | cam N | runas 1/3 | sec N | feit S | ms` + linha de diagnóstico. Totais: geral, por rota, **por grupo** (§3), ajuste × validação (§5), tempo (média, mediana, máximo). O teste **não falha** por concordância baixa no passo 1; no passo 8 ganha pisos (não pode cair abaixo do "depois" menos uma folga).

---

## 2. Consumo dos campos novos

### 2.1 Onde colocar o código
Arquivo novo `app/src/main/java/com/riftcoach/app/SkillMath.kt` (`internal object`, sem Android): funções puras de **valor** e **número de usos** de um `Effect`. `fight()` e `kitSupport()` passam a chamar as mesmas funções (hoje repetem a conta em `:556-558`, `:574-577` e `:710-712`). Teste novo `SkillMathTest.kt` com `Effect`/`Trigger` montados à mão (sem assets) — cada regra abaixo tem um teste.

```kotlin
internal class FightCounts(val T: Double, val nAutos: Double, val casts: Double, val level: Int)
internal object SkillMath {
    fun value(e: Effect, idx: Int, stat: (String) -> Double, level: Int): Double   // base + razões (com grow) e faixa upTo
    fun uses(e: Effect, slot: Int, n: Double, fc: FightCounts, table: List<Pair<String, String>>): Double
    fun combine(values: List<Pair<Effect, Double>>): Double                        // soma × variante
}
```

### 2.2 Contrato do parser (o que o `GameDbParser` precisa marcar) — passo 3
Campos novos, todos opcionais (padrão = comportamento de hoje):

| Campo | Valores | Regra de leitura (sugestão; o programador confere no despejo) |
|---|---|---|
| `Trigger.dur: Double?` | segundos | "持续X秒" na mesma frase do `tique` |
| `Trigger.attacks: Boolean` | ataques × qualquer acerto | `true` quando o trecho do `HITS` diz 普攻/普通攻击 |
| `Trigger.self: Boolean` | vida de quem | `vidaBaixa` com 自身/自己 = do próprio campeão (Blitzcrank P) |
| `Effect.combine: String?` | `"soma"` / `"variante"` / `null` | relação com o **efeito de dano anterior da mesma habilidade**. `soma`: entre os dois há 外加/加上/并造成/额外造成/再次/随后/回程/返回/第二段 (Wukong R, Jinx R, Gragas W, Sion W, Warwick Q, Kayn Q, Sett R, Diana W, Soraka E, Lee Sin Q eco). `variante`: 强化/改为/取而代之/若…则/当…时 ou forma alternativa (Kayn formas, Kog'Maw R <40 %, Pantheon <25 %, Kindred). `null`: não deu para saber |
| `Effect.hits: Double?` | golpes por uso | número explícito ("N次", "共N次"): Garen E (giros), Hecarim W |
| `Effect.upToBy: String?` | `"nivel"` / `"outro"` | `nivel` quando a faixa vem de 随等级成长 (`LVL_RANGE`, `OUT_HEAL_LVL`); senão `outro` (distância, vida do alvo, carga) |

Junto, os ajustes pequenos já recomendados em `notas-calibracao.md` (rótulo `tique` mais largo `每(隔|过)?\d*\.?\d*秒`, Rakan P, `monstersOnly`, `coberturaNaoPiora`). **No passo 3 o `BuildEngine` não muda** → o placar de concordância tem que sair idêntico (prova de que o parser só acrescentou marcas).
Entrega do passo 3 inclui um despejo (no rascunho) de **todas as habilidades com 2+ efeitos de dano** com o `combine` atribuído, para o inspetor conferir por amostragem; meta: os casos citados nas notas classificados certo e ≥ 80 % classificados.

### 2.3 Regras no motor (passo 4)

**a) Valor de um efeito — `SkillMath.value`**
- Razões com `grow`: `pct_ef = pct + Σ g.pct × (g.pct < 0,2 ? stat(g) : stat(g)/100)`, limitado a `3 × pct` (proteção contra unidade errada). Ambíguos (Amumu W 0,4; Garen R 0,12; Viego R 5) ficam na regra e são listados no teste como [dedução].
- Faixa `upTo`: `v = v_min + α·(v_max − v_min)`, com `α = (nível−1)/14` se `upToBy == "nivel"`, senão `α = UPTO_MEIO = 0,5` (constante nomeada, ajustável no passo 7).
- `statValue` (`BuildEngine.kt:453-471`): somar `"mana bônus" -> s.mana − s.baseMana` (campo novo `baseMana` em `Stats`) e `"vel. de movimento bônus" -> s.ms − hero.moveSpeed`.
- Testes: Ryze com/sem item de mana muda o valor; faixa de nível no nível 1 = mínimo e no 15 = máximo; `grow` 0,12 com 100 AD bônus = +12 pontos; `grow` 5 com 100 AP = +5 pontos; limite 3×.

**b) Número de usos na luta — `SkillMath.uses`** (`n` = usos da habilidade como hoje, `:553`; passiva: ver c)
| Gatilho | Fórmula |
|---|---|
| nenhum | `n × (hits ?: 1)` |
| `intervalo` (s) | `min(n, 1 + max(0, T − 0,5)/s)`; na passiva: `1 + max(0, T − 0,5)/s` |
| `acertos` (N) | `(attacks ? nAutos : nAutos + casts) / N` (não multiplica por `n`: é passiva do golpe) |
| `tique` (s) | `n × min(dur, T)/s`, com `dur = Trigger.dur ?: tabela "Duração (s)" ?: DUR_PADRAO = 3` |
| `vidaBaixa` (x %) do alvo | `n × baixa(x)`, `baixa(x) = min(0,6; 0,8·x/100)` (40 % → 0,32, igual ao `tgtlow` atual `:636`) |
| `vidaBaixa` própria (`self`) | `n × (ranged ? 0,25 : 0,4)` (igual ao `selflow` `:638`) |
Testes: um caso por linha, com números conferidos à mão (ex.: Caitlyn P N=6 com 12 ataques = 2 usos; Aatrox P 24 s numa luta de 8 s = 1,31).

**c) Passiva** (`:574-578`): passa a somar **todos** os efeitos de dano da passiva; com gatilho usa a tabela acima; sem gatilho mantém a heurística atual `min(4, casts·0,6 + nAutos·0,15)`. Condicionais sem gatilho no texto (Sion P, Jhin P, Brand P) ficam para depois (registrar na lista de pendências; não inventar).

**d) Soma × variante — `SkillMath.combine`** (substitui `:567-571`):
1. encadeia os efeitos `soma` no anterior (somam inteiros);
2. entre os blocos `variante`, fica o maior; se a variante tem gatilho `vidaBaixa`, pondera: `(1 − baixa(x))·normal + baixa(x)·variante`;
3. pares `null` mantêm a regra atual (maior + 0,35 × outros) — assim, o que o parser não souber classificar não muda.
Testes: Jinx R soma (> hoje); Pantheon/Kog'Maw variante ponderada; habilidade sem marca = valor igual ao de hoje (teste de não-mudança).

**e) `kitSupport`** (`:696-716`): usa `value`/`uses`/`combine` para cura e escudo (tiques de cura: Sona R, Soraka; faixas de cura por nível). Teste: Soraka W/R com valores conferidos.

**f) Desempenho**: tudo é O(efeitos) por luta, sem laços novos; pré-calcular por herói (uma vez, `lazy`) o que não depende de itens (duração do tique, classificação). Medir no `ConcordanciaTest` (§1.8).

---

## 3. Ajuste por grupos de campeões

### 3.1 Mudanças que valem para todos (antes dos grupos)
- **X1 — "time médio" quando o draft não está completo** (`context()`, `:197-277`). Hoje inimigo desconhecido = ninguém com cc, cura, escudo ou mergulho. Proposta: valores **esperados** calculados dos dados que o app já tem — média das características (`Champion.tags`, `dmg`, classe) dos campeões de cada rota, ponderada pela taxa de escolha em `data.stats` (`rank.json`, tier Diamante+). Com `k` inimigos conhecidos, soma os conhecidos + `(5−k)/5 ×` o time médio. O mesmo para aliados desconhecidos (o Suporte passa a "esperar" um atirador). Contrato: `Ctx` ganha campos `Double` (`ccExp`, `healersExp`, `shieldersExp`, `diversExp`, `tanksExp`, `allyAutoersExp`, `allyApExp`, `autoShare`/`phys`/`magic` já são médias); as listas de nomes continuam para os textos de motivo. Com o draft inimigo completo, nada muda (os testes de adaptação atuais provam).
- **X2 — lâmina encantada realista** (`:590`): `min(1+T/cd, casts × entrelaça)`, com `entrelaça = clamp(autoUptime × 1,6; 0,3; 1)` (mago também dá 1 ataque depois do feitiço; hoje Ahri faz ~0,6 procs na luta). **E** pedir ao analista para conferir na base os números do Dilacerador Divino (10 % / 6 %) e da Trindade (Bravura, velocidade) — regra de não inventar números.
- **X3 — chegar no alvo** (velocidade de movimento): para corpo a corpo, `contato = clamp((ms / MS_REF)^0,6; 0,85; 1,15)` multiplicando `nAutos` e os usos de habilidades de curto alcance. Dá valor a Couraça do Defunto, Quebrapassos, Força da Natureza, Trindade, Youmuu. `MS_REF` = média da velocidade final de um corpo a corpo com botas (calcular, não chutar).
- **X4 — torre** : item com `tags` `split` usa o `utilValue("tower")` que já existe para runas (`:729-733`). Quebra-cascos (12 conjuntos).

### 3.2 Grupos (derivados do que já existe: `Arch` + `tags`; nenhuma lista de campeões)
| Grupo | Regra | Exemplos da referência | O que difere hoje | Alavancas (onde) |
|---|---|---|---|---|
| G1 Lutador pesado / tanque de rota | `FIGHTER && has("tank")`, ou `TANK` fora do Suporte | Garen, Darius, Sett, Mordekaiser, Malphite Barão, Nautilus Barão | Dilacerador/Sterak/Dança da Morte em vez de Cutelo/Quebra-cascos/Couraça/Espinhos/Manopla Glacial/Coração Congelado; Mercúrio em vez de Placas | X1 (mais dano físico e ataques esperados → Placas, Espinhos, Coração Congelado), X2, X3, X4; peso do dano do tanque (`a = 0,25`, `:917`) e `reflect × 14` (`:843`) |
| G2 Lutador de mergulho / selva AD | `FIGHTER && !has("tank")` | Lee Sin, Jax, Camille, Xin Zhao, Vi, Viego, Fiora, Renekton, Aatrox, Yasuo | Dilacerador em vez de Trindade/Eclipse/Céu Dividido; Lembrete Mortal nunca; Mercúrio em vez de Glutonas | X1 (curadores esperados → Lembrete Mortal), X2, X3; estilos (`champStyles`, `:980-991`) |
| G3 Assassino | `ASSASSIN` | Zed, Kha'Zix, Akali, Katarina, Master Yi | já perto (Zed) | só conferir que X1–X3 não pioram |
| G4 Mago | `MAGE` | Ahri, Syndra, Orianna, Veigar, Lux Meio | Liandry/Criafendas/Botas de Mana em vez de Luden/Lich/Rabadon/Malignance + Mercúrio/Ionianas | X1 (cc esperado → Mercúrio), X2 (Lich), dano contínuo (queimadura `burn` `:612`, `amp ramp` `:629`) com desconto para janelas curtas — o "estouro" pesa mais que a luta longa; conferir os atributos das Botas de Mana 2409 |
| G5 Atirador | `MARKSMAN` | Jinx, Kai'Sa, Caitlyn, Vayne, Ezreal, MF, Jhin, Lucian | já perto; Yun Tal/Furacão acima do real; Coletor/Rei Destruído abaixo; Glutonas para atiradores de habilidade | X1; execução do Coletor (hoje `amp 2,5 %`); **não** acrescentar campeões em `AUTO_UPTIME` sem fato do jogo citado |
| G6 Encantador | `ENCHANTER` | Soraka, Lulu, Janna, Nami, Seraphine | sem item inicial; Mandato/Mikael em vez de Diadema/Turíbulo/Eco Harmônico; Mercúrio em vez de Ionianas | item inicial (§3.3); Ionianas (§3.3); X1 (atirador aliado esperado → Turíbulo); `b` de defesa 0,3 → menor (fica atrás); aceleração também nas utilidades com recarga (`times`, `:877`) |
| G7 Suporte tanque | `TANK_SUPPORT` | Thresh, Leona, Nautilus, Malphite Sup, Sett Sup | sem item inicial; Medalhão/Chegada do Inverno em vez de Warmog/Virtude/Zeke/Força da Natureza; Mercúrio em vez de Placas/Ionianas | item inicial; X1; X3 |

Suportes "de dano" (Veigar Sup, Lux Sup) caem em G4 pelo arquétipo, mas recebem o item inicial pela rota.

### 3.3 Item inicial de suporte e Botas Ionianas
**Por que o motor não escolhe** [confirmado]: 2164/2165 têm preço 500 e `hide: true` → fora dos candidatos (`:1052`); a conta não tem ouro nem acúmulos. As Ionianas (2407) só valem 15 de aceleração de habilidade; a redução de 15 % na recarga dos feitiços (`haste k: "summ"`) e os +50 % de regeneração de mana (texto da base) não entram em lugar nenhum.

**O que mudar no modelo**:
1. Rota Suporte ⇒ **1º espaço = item inicial** (regra do jogo: o suporte vive do ouro desse item, não é decorar build). O motor escolhe entre 2164 e 2165 **pela nota** (`score` no nível 15 com o item já acumulado) e monta mais 4 itens. Contrato: `BuildResult` não muda — `items[0]` é o item inicial, com motivo "item de suporte: dá ouro e cresce com o tempo"; a nota fixa de `:1145` sai. Dados: `items_model.json` 2164/2165 ganham `fx` com os acúmulos máximos (250 de vida, 20 AD ou 40 AP adaptativo; 2164 mais 14 AD/28 AP) — números do texto da base, já escritos no `pt` (citar a fonte no `src`). As runas (`RuneEngine.kt:112-116`) usam `start + items` → passam a incluir o item inicial automaticamente.
2. Ionianas: `fx` `{"t":"util","k":"summ"}` e `{"t":"util","k":"mana"}` (fonte: texto da base 2407). Em `utilValue` (`:726-778`): `"summ"` vale mais para quem tem feitiço de valor alto no par (Curar/Exaustão/Incendiar do suporte) — valor por arquétipo, constante nomeada; `"mana"` já existe (`:742`). `stats()` (`:383-386`) para de descartar `k: "summ"` em silêncio.
3. O peso menor da defesa do encantador (G6) e o time médio (X1) completam a escolha Ionianas × Mercúrio.

Testes: toda build de Suporte começa com 2164 ou 2165; nenhuma build fora do Suporte tem esses itens; suporte tanque prefere 2165 (assert fraco: "não piora" no placar, não o id); Ionianas recebem nota > 0 em `util`.

---

## 4. Plano em passos

Cada passo: um bloco pequeno, uma revisão do `code-inspector` (modelo **diferente** do programador), placar de concordância antes/depois anexado ao relatório (tuning, validação, runas, tempo). Gradle só pelo programador do bloco.

| # | Passo | Arquivos | Testes que provam | Programador | Inspetor |
|---|---|---|---|---|---|
| 1 | **Medir o "antes"**: `MetaReference` + `ConcordanciaTest` + `MetaReferenceTest`; gravar `concordancia-antes.txt` | só `app/src/test/...` | sanidade (43 campeões, ids, caminho); regras de pontuação (empate 0,85, bota ausente, `canonical` 2119→2118, sem ligação) | Sonnet · high | Opus · medium |
| 2 | Expor `setValue` e o diagnóstico "por que errou" (refatoração sem efeito) + `mana bônus`, `vel. de movimento bônus`, `grow` em `SkillMath.value` | `BuildEngine.kt`, `SkillMath.kt`, testes | 55 saídas idênticas ao "antes" **antes** do `statValue`; depois, só campeões com esses atributos mudam (o teste lista quais) | Sonnet · high | Opus · medium |
| 3 | **Contrato do parser** (§2.2) + ajustes pequenos das notas | `GameDb.kt`, `GameDbTest.kt` | testes por padrão (Jinx R soma, Pantheon variante, Garen E `hits`, tique com `dur`); placar **idêntico** (motor intocado); despejo das habilidades com 2+ danos | **Opus · high** | Sonnet · high |
| 4 | **Consumo no motor**: `uses`, `combine`, `upTo`, passiva, `kitSupport` | `SkillMath.kt`, `BuildEngine.kt`, `SkillMathTest.kt` | uma regra = um teste com `Effect` sintético; "sem marca = igual a hoje"; placar (runas não podem cair abaixo do "antes") | **Opus · high** | Sonnet · high |
| 5 | **X1 — time médio** | `BuildEngine.kt` (`context`, `Ctx`), `RuneEngine.kt` (só onde usa `ctx.*.size`) | draft vazio tem cc/cura esperados > 0; draft completo = igual a hoje; testes de adaptação passam | Opus · medium | Sonnet · high |
| 6 | **Suporte**: item inicial + Ionianas | `BuildEngine.kt`, `items_model.json` (com fonte), `RuneEngine.kt` se precisar | §3.3; nenhuma regressão fora do Suporte | Sonnet · high | Opus · medium |
| 7a–7f | **Ajustes por grupo**, um grupo por bloco, na ordem G6 → G7 → G1 → G2 → G4 → G5/G3; X2/X3/X4 entram no grupo onde o diagnóstico apontar | `BuildEngine.kt` (constantes nomeadas no `companion`), `RuneEngine.kt` só se as runas caírem | por bloco: concordância do grupo sobe nos casos de **ajuste**, validação não cai, nenhum outro grupo cai > 1 ponto, testes de adaptação passam | **Opus · high** | Sonnet · high |
| 8 | **Medir o "depois"**: relatório antes × depois, pisos no `ConcordanciaTest`, tempos | testes, `concordancia-depois.txt` | pisos = "depois" − folga de 2 pontos | Sonnet · medium | Opus · medium |
| 9 | Teste no emulador (tempo e telas da aba Análise) | — | 0,5–2,6 s hoje; aceitar até +25 % | `qa-tester` Sonnet · medium | — |

Por que Opus nos passos 3, 4, 5 e 7: leitura de padrões do chinês com muitos casos-limite (3), matemática que mexe em todas as builds e runas ao mesmo tempo (4, 7) e mudança de contrato do contexto (5). Os passos 1, 2, 6, 8 são bem descritos aqui → Sonnet basta.

Regras de trabalho para o passo 7 (cada bloco):
1. Ler o diagnóstico "por que errou" dos casos de **ajuste** do grupo.
2. Mudar **uma ou duas alavancas** gerais (fórmula ou constante nomeada), nunca uma linha com nome de campeão ou id de item escolhido para um campeão.
3. Rodar o placar; manter só se: ajuste do grupo sobe, validação total não cai, runas não caem, tempo médio não sobe > 10 %.
4. Relatório com antes/depois do bloco e o motivo de jogo de cada mudança.

---

## 5. Riscos e como evitar

| Risco | Como evitar |
|---|---|
| **Sobreajuste** à referência (55 casos, um site, um servidor) | Separar por **campeão** (todas as rotas juntas): **validação** = Darius, Camille, Renekton, Vi, Graves, Kha'Zix, Syndra, Katarina, Caitlyn, Ezreal, Lucian, Nami, Nautilus, Janna (14 campeões, 16 rotas, todos os grupos); **ajuste** = os outros 29 (39 rotas, inclui os 5 que motivaram a etapa + Jinx e Zed). O programador só usa o total da validação, nunca o diagnóstico por caso dela. O inspetor procura no `git diff` nomes/chaves de campeão e ids de item novos em `main/` (proibidos sem fato do jogo citado; `AUTO_UPTIME`, `CRIT_FIGHTERS`, `MELEE/RANGED`, `STYLES` congeladas nesta etapa salvo aprovação). |
| Motor vira "build fixa do meta" e para de se adaptar | Os testes de adaptação existentes (anti-cura contra curadores, armadura × RM) continuam obrigatórios; o passo 5 acrescenta "draft completo = igual a hoje". |
| A referência não é a build ótima (hábito, patch diferente: base 7.3 × páginas 7.x) | A meta é **concordância razoável**, não 100 %; o placar ponderado dá crédito às alternativas; casos "fracos" à parte. |
| **Regressão nas runas** (já caíram 6→5 e 8→7 no bloco das fórmulas) | O placar das 55 mede runas em todo passo; piso: não abaixo do "antes" a partir do passo 4. `RuneEngineTest.comparaComOMeta` segue imprimindo os 10 casos antigos. |
| **Desempenho no celular** | Nada novo dentro dos laços de candidatos além de contas O(1); pré-cálculos por herói com `lazy`; time médio calculado uma vez. Tempo por caso impresso; limite: média no PC até +10 % por bloco e +25 % no total; conferir no emulador no passo 9. |
| Classificação errada do parser (`combine`) troca subestima por superestima | `null` mantém a regra antiga; despejo completo para o inspetor; testes com os casos citados nas notas. |
| Números de itens duvidosos (Dilacerador, Glutonas com números do 7.2, Coletor) | O analista confere na base antes do ajuste do grupo que depender deles; nada de número inventado. |
| Mudança de contrato de `Ctx` quebra `RuneEngine.spells` (usa `ctx.enemies`) | Listas continuam; só se acrescentam campos `Double`; o passo 5 compila e roda o `RuneEngineTest`. |
| Dois agentes no mesmo arquivo (`BuildEngine.kt` em quase todos os passos) | Passos em série; nada em paralelo nesta etapa além do analista (dados). |

---

## 6. Perguntas para o CEO (linguagem simples)
1. **Meta de "parecido com os jogadores"**: hoje o app acerta pouco os 3 itens principais em vários campeões. Uma meta boa para esta etapa seria "acertar pelo menos 2 dos 3 itens principais na maioria dos campeões", sem copiar as builds. Você concorda com essa meta, ou quer outro alvo?
2. **Suporte**: no jogo, o suporte compra um item inicial (Foice da Névoa Negra ou Baluarte da Montanha) que ocupa um dos espaços até o fim. Podemos mostrar esse item como o **1º item** da build do suporte (e mais 4)? Ou prefere que ele apareça separado, como "item inicial"?
3. **Quando ainda não se sabe quem são os inimigos**, o app passaria a montar a build pensando num "time inimigo típico" (com um pouco de controle, de cura, etc.), em vez de num time sem nada disso. Concorda?
4. **Tempo de espera**: hoje o cálculo leva de meio segundo a 2,6 segundos no emulador. Aceita que fique até ~25 % mais lento se as builds ficarem bem mais parecidas com as dos jogadores Diamante+?
