# Indicadores da operação

Objetivo: melhorar a qualidade e reduzir desperdício (retrabalho e uso do limite). **Só dados reais**, tirados dos relatórios dos agentes, das notificações de término (duração, tokens) e dos resultados de teste. Nada de estimativas. Linhas de código e número de tarefas não medem produtividade.

## Indicadores
| Indicador | Como medir | Para que serve |
|---|---|---|
| Aprovação na 1ª inspeção | blocos com veredito "aprovado" (sem obrigatórias) na rodada 1 ÷ blocos | qualidade do brief e da implementação |
| Defeitos achados pelo inspetor | nº de correções obrigatórias por rodada | onde o programador erra |
| Defeitos achados pelo testador | nº por gravidade (grave/médio/cosmético) por versão testada | o que escapa da inspeção |
| Regressões | defeitos em algo que funcionava antes | risco das mudanças |
| Retrabalho | nº de rodadas até a aprovação | custo de idas e vindas |
| Compilações/testes | build ok? testes (total/falhas) na entrega | estabilidade |
| Tempo de ciclo | duração de cada convocação (notificação de término) | ritmo |
| Uso | tokens do agente (notificação de término) × modelo/esforço × complexidade | escolha de modelo/esforço |

## Registro por convocação (17/11/2026)
Fonte: notificações de término dos agentes e relatórios nesta data. "tokens" = tokens do agente informados pelo Claude Code.

| Tarefa | Agente (papel) | Modelo · esforço | Duração | Tokens | Resultado |
|---|---|---|---|---|---|
| E5-01 | programador | Fable · high | — | — | falhou ao iniciar: Fable exige créditos (D-002); nenhum arquivo mudou |
| E5-01 r1 | programador | Opus · high | 24 min | 286 mil | 118 → 50 fórmulas não interpretadas; 28 → 36 testes, todos passando |
| E5-01 r1 | inspetor | Sonnet · high | 14 min | 303 mil | aprovado com correções: **7 obrigatórias**, 0 regressões |
| E5-01 r2 | programador (mesmo agente) | Opus · high | 15 min | 420 mil (acumulado) | 7/7 corrigidas; 45 testes, todos passando |
| E5-01 r2 | inspetor (mesmo agente) | Sonnet · high | 3 min | 366 mil (acumulado) | **aprovado**, 0 obrigatórias, 0 regressões em 720 efeitos |
| teste 4.3 | testador | Sonnet · medium | 29 min | 223 mil | APK etapa 4.3: 0 travamentos; 3 médios, 6 cosméticos |
| E5-02 r1 | programador | Opus · high | 26 min | 288 mil | 7 problemas tratados; 45 → 51 testes, todos passando |
| E5-02 r1 | inspetor | Sonnet · high | 7 min | 184 mil | aprovado com correções: **2 obrigatórias**, 0 regressões |
| E5-02 r1 | testador | Sonnet · medium | 25 min | 160 mil | APK 5a: 7 de 8 itens confirmados; 0 travamentos; 1 médio-cosmético, 2 cosméticos |
| E5-02 r2 | programador (novo) | Opus · medium | 7 min | 178 mil | M1, M2, R1–R6 e 3 itens do testador; 51 → 57 testes, todos passando |
| apoio | consulta de documentação (claude-code-guide) | Haiku · low | 1 min | 49 mil | formato de agentes/skills/regras conferido |
| E5-02 r2 | inspetor (novo) | Sonnet · high | 6 min | 169 mil | aprovado com correções: **1 obrigatória** (O1, confirmada com teste temporário), 0 regressões |
| E5-02 r3 | programador (novo) | Sonnet · high | 5 min | 122 mil | O1, R-a…R-e feitos; 57 → 63 testes, todos passando; **desvio de processo**: abriu por engano um subagente vazio (sem efeito) — a definição `android-developer` não tem a ferramenta Agent |
| E5-02 r4 | programador (novo) | Sonnet · high | 4 min | 120 mil | R1–R4 + ajuste 10 lugares de ban (enviado no meio da tarefa); 63 → 67 testes, todos passando |
| E5-02 apoio | analista de dados | Sonnet · medium | 2 min | 71 mil | 10 lugares de ban (5 por time), fonte oficial patch 2.6; achou que o `FULL_BANS = 8` estava errado — confirmado pelo supervisor no print |
| E5-02 r3 | inspetor (novo) | Opus · high | 7 min | 166 mil | **aprovado**, 0 obrigatórias, 0 regressões; 4 recomendadas provadas com teste temporário |
| E5-02 r4 | inspetor (novo) | Opus · medium | 2 min | 93 mil | **aprovado**, 0 obrigatórias, 0 regressões; 3 recomendadas → backlog E5-04 |
| E5-03 dados | analista de dados | Sonnet · medium | 2 min | 92 mil | 7 ligações duvidosas: 6 confirmadas, 1 corrigida (Diadem of Songs 2118 → 2119); 91 itens, 48 runas, 9 feitiços consistentes |
| E5-03 dados r2 | analista (mesmo agente) | Sonnet · medium | 1 min | 96 mil (acumulado) | marcas "unsure" removidas das 6 confirmadas; 0 restantes |
| E5-03 plano | arquiteto | Opus · high | 11 min | 228 mil | plano em 9 passos, diagnóstico com arquivo:linha, 4 perguntas ao CEO (respondidas, D-012) |
| E5-03 passo 1 | programador (novo) | Sonnet · high | — | — | **parado pelo supervisor a pedido do CEO** (limite de uso a 96%); deixou 3 arquivos de teste pela metade, sem revisão |

## Resumo até agora
- Aprovação na 1ª inspeção: 0 de 2 blocos (E5-01, E5-02). E5-01 aprovado na 2ª rodada; E5-02 aprovado (sem obrigatórias) na 3ª rodada, mais 1 rodada de recomendadas (4 no total).
- Defeitos obrigatórios achados pelo inspetor: E5-01 = 7 (r1); E5-02 = 2 (r1) + 1 (r2).
- Regressões encontradas: 0.
- Travamentos no emulador: 0 em 2 versões testadas.
