# Fluxos de trabalho

Os procedimentos detalhados estão nas skills (`.claude/skills/`): **feature-intake** (etapas 1–2), **engineering-cycle** (etapas 3–8) e **release-readiness** (etapa 9). Este documento é o mapa.

## Fluxo padrão (pedido → entrega)
| Etapa | O quê | Quem | Saída |
|---|---|---|---|
| 1. Recebimento | Entender o pedido e o resultado esperado | supervisor | pedido classificado (novo / defeito / consulta) |
| 2. Análise de produto | Objetivo, escopo, requisitos, critérios de aceitação, restrições, fora do escopo; **aprovação do CEO** para o que é novo | supervisor (+ `product-manager`, `ux-ui-designer` se preciso) | entendimento aprovado |
| 3. Planejamento técnico | Quais cargos; riscos estruturais | supervisor (+ `software-architect` se preciso) | lista de tarefas com IDs |
| 4. Delegação | Brief no modelo `TASK_BRIEF` com modelo e esforço | supervisor | agentes convocados |
| 5. Execução | Implementação no escopo; pesquisa/inspeção em paralelo só se independentes | `android-developer`, `wildrift-data-analyst` | entrega + relatório |
| 6. Inspeção | Revisão contra o último commit; apontamentos voltam ao programador | `code-inspector` | veredito |
| 7. Testes | Roteiro no emulador com APK identificado | `qa-tester` | relatório com evidências |
| 8. Integração | Portões de qualidade; `plan.md`, `METRICS.md` | supervisor | pronto ou nova rodada |
| 9. Entrega | Relatório `DELIVERY_REPORT`; commit/push só autorizado | supervisor | aprovação do CEO |

## Variações
- **Defeito em algo aprovado**: pula a etapa 2 (salvo mudança visível relevante); começa na 3.
- **Calibração do motor / dados**: `wildrift-data-analyst` monta ou confere a referência → `android-developer` (Opus) ajusta o modelo com um teste que mede a concordância (antes × depois) → `code-inspector` (Sonnet high) confere cada rodada: "ajustar o modelo, não decorar builds".
- **Pesquisa pura**: só `wildrift-data-analyst`; o supervisor confere as fontes e a classificação de confiança.
- **Auditoria de segurança/conformidade**: `security-compliance` independente; mudanças no app viram tarefas novas, aprovadas pelo CEO.
- **Mudança de interface**: `ux-ui-designer` propõe → CEO aprova → `android-developer` → `code-inspector` → `qa-tester` com critérios visuais.

## Coordenação de recursos
- **Gradle**: um agente por vez; o brief diz quem está liberado. O supervisor registra em `plan.md` §7 quem está usando.
- **Emulador**: um testador por vez; quem o liga desliga no fim (`adb emu kill`).
- **Arquivos**: nunca dois agentes editando o mesmo arquivo; documentação do supervisor não conflita com o código dos agentes.

## Interrupção (limite de uso, sessão encerrada)
- `plan.md` §7 sempre diz o que está em andamento, com quem, e onde estão os relatórios.
- Ao retomar: `git status` + `git diff --stat` para ver o que ficou pela metade; relatórios em arquivo (`app/src/test/meta/`, `docs/`) ou na pasta de rascunho da sessão (pode ter sido apagada); relançar o agente com o mesmo brief e o estado encontrado.
