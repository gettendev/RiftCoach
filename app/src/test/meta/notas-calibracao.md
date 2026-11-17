# Notas para a calibração do BuildEngine (etapa 5)

Juntadas das 2 rodadas do inspetor sobre o bloco de fórmulas (commit 821ed6f). O parser já marca estas coisas, mas o `BuildEngine` ainda não as consome.

## O que o BuildEngine deve passar a tratar
1. **Gatilhos (`Effect.trigger`)** — passivas com dano (BuildEngine.kt ~574: 1º "Dano" × min(4, casts×0,6 + autos×0,15)) contam passivas condicionais como contínuas:
   - `intervalo` = recarga (Aatrox 24 s, Nocturne 12 s);
   - `acertos` = 1/N dos ataques (Caitlyn 6, Vi 3, Ekko 3, Vayne W 3, Aurora 3);
   - `vidaBaixa` = condição (Zed P <50%; Pantheon, Kindred, Kog'Maw = alvo; Blitzcrank P = a própria vida — o `Trigger` ainda não diz de quem é);
   - `tique` = dano/cura por segundo → multiplicar pela duração do texto (Fiddlesticks E, Mordekaiser P, Corki E, Swain R, Sona R, Amumu W…);
   - sem gatilho próprio, mas condicionais: Sion P (só depois de reviver), Jhin P (última bala), Brand P.
2. **Faixas (`Effect.upTo`)** — hoje só o mínimo entra → subestima: Jinx R, Sion Q/R (R real vai a 400–1200 + 80% AD bônus), Nunu E/R (180–915), Fizz R, Akali R, Vi Q, Varus Q, Lee Sin Q2, Riven R, Diana R, Zoe Q, Hwei E. Usar média ou ponderar por distância/vida do alvo. Também faixas por nível: Ornn W, K'Sante P, Rek'Sai P, Sylas W.
3. **Mesmo golpe em dois efeitos que SOMAM** (Wukong R, Jinx R, Gragas W, Sion W, Warwick Q, Kayn Q, Sett R, Diana W escudo base + 3ª esfera) é tratado como variante ("maior + 0,35 × outro") → subestima. Golpe repetido (Volibear R, Soraka E, Skarner Q, Poppy W) conta 1,35×. Sugestão: parser marcar `Effect.group`/`sameHit` (aditivo × variante).
4. **Vários golpes como um só**: Garen E (8 giros), Hecarim W, Mordekaiser/Sona R por tique → multiplicar.
5. **Formas alternadas** não somam com a normal: Kayn, Kog'Maw R <40%, Pantheon <25%, Kindred. Lee Sin Q "eco" é um segundo uso e soma.
6. **"mana bônus"** (Ryze Q/W/E) vale 0 no `statValue` → incluir.
7. **`Ratio.grow`** mistura "por ponto" (0,005–0,12) e "por 100" (Amumu 0,4, Shen 1,5, Kai'Sa 5). Heurística: < 0,2 = por ponto; ambíguos Amumu W, Garen R (0,12), Viego R (5).
8. Reconferir depois: Morgana, Lux, Orianna, Cho'Gath, Evelynn, Lulu, Ryze, Sion (os tipos mudaram).

## Ajustes pequenos no parser para fazer junto (recomendados, não obrigatórios)
- Rótulo `tique` (PER_SECOND, GameDb.kt ~394) só pega "每秒(对…)造成/回复/为". Faltam "每0.5秒/每0.25秒/每1秒" e "每秒受到/还会/可回复/额外造成": Hecarim W, Fiddlesticks R, Hwei Q, Mel R, MF E, Morgana W, Nidalee W, Rammus R, Renekton R, Shyvana W, Teemo P, Viktor R, Vladimir W, Yunara E, Evelynn P, Janna R. Sugestão: `每(隔|过)?\d*\.?\d*秒` perto de 造成/受到/回复/额外.
- Rakan P "每35秒…获得…护盾" sem gatilho (EVERY exige 隔/过).
- `Trigger` sem dono (alvo × próprio) — Blitzcrank P.
- `monstersOnly` (~519/526): tirar "非英雄" antes de testar 英雄; restringir ao trecho depois da última vírgula.
- `coberturaNaoPiora`: apertar `base.size == ranks || == 1` para `== ranks` (hoje passa).
- K'Sante P `describe()` mistura "cresce com o nível" e "até" (só estética).

## Concordância das runas (RuneEngineTest, 10 casos)
- Etapa 4.3: essencial 6/10, caminho 8/10, runas do caminho 17/30, secundária 1/10, feitiços 10/10.
- Depois do bloco de fórmulas: essencial 5/10, caminho 7/10, runas do caminho 16/30, secundária 3/10, feitiços 10/10 (Zed Selva: Eletrocutar → Primeiro Ataque; Lee Sin: Precisão → Determinação — efeito dos danos condicionais contados como contínuos).
