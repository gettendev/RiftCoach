# Escolha de modelo e esforço

## 1. O que está disponível (conferido em 17/11/2026)
- Claude Code **2.1.325**, rodando dentro do app Claude Desktop (Windows). Sem o comando `claude` no PATH.
- Modelos por apelido: `opus` (Opus 5.5), `sonnet` (Sonnet 5.5), `haiku` (Haiku 5.5). **`fable` (Fable 5.2) exige créditos de uso extras**: tentativa em 17/11 falhou com "requires usage credits". Não usar sem o CEO autorizar gasto.
- Esforço: `low`, `medium`, `high`, `xhigh`, `max` (os níveis disponíveis dependem do modelo).
- Ordem de precedência no Claude Code: parâmetro da convocação (`model`/`effort` da ferramenta Agent) > campo da definição do agente > variável `CLAUDE_CODE_SUBAGENT_MODEL` > modelo da sessão. **Não usar variáveis globais** que forcem o mesmo modelo para todos.
- O esforço de um agente que já está rodando não muda; vale a partir da próxima convocação.

Reconferir esta seção quando uma convocação falhar por modelo indisponível ou quando o CEO mudar de plano.

## 2. Padrões por cargo
| Cargo | Padrão | Mais barato (tarefa simples) | Mais forte (tarefa difícil) |
|---|---|---|---|
| Pesquisa simples / consulta de documentação | Haiku · low | — | Sonnet · medium |
| `product-manager` | Sonnet · medium | Haiku · medium | Opus · high (escopo grande e ambíguo) |
| `software-architect` | Opus · high | Sonnet · high | Opus · xhigh |
| `wildrift-data-analyst` | Sonnet · medium | Haiku · medium (conferência rotineira) | Sonnet · high / Opus · high (montar base de referência) |
| `ux-ui-designer` | Sonnet · medium | Haiku · medium | Opus · medium |
| `android-developer` | Sonnet · high | Sonnet · medium (correções bem descritas) | **Opus · high/xhigh** (calibração matemática, concorrência, depuração difícil) |
| `code-inspector` | Opus · high | Sonnet · high (quando o programador foi Opus) | Opus · xhigh |
| `qa-tester` | Sonnet · medium | Haiku · medium (reteste curto) | Sonnet · high |
| `security-compliance` | Opus · high | Sonnet · high | Opus · xhigh |
| `release-devops` | Sonnet · medium | Haiku · low (só conferir APK) | Sonnet · high |

## 3. Critérios
1. **Revisão independente**: o inspetor de um bloco usa **modelo diferente** do programador daquele bloco. Regra prática: o lado que faz o raciocínio mais difícil fica com o Opus; o outro lado com Sonnet `high`. Ex.: correções bem descritas → programador Sonnet, inspetor Opus; calibração matemática → programador Opus, inspetor Sonnet `high`.
2. Não usar Opus para tarefa trivial nem `xhigh`/`max` para tarefa simples. `max` só com motivo escrito.
3. Pesquisas e conferências rotineiras → Haiku/Sonnet com esforço baixo/médio.
4. Nunca baixar a qualidade de uma revisão crítica só para economizar.
5. Instruções bem detalhadas (com arquivo:linha e correção sugerida) permitem esforço menor no programador.
6. **Continuar um agente × agente novo**: continuar (mesmo contexto) para rodadas curtas quando o contexto dele é pequeno e relevante; agente novo quando o contexto passa de ≈150 mil tokens ou o assunto muda — cada passo de um agente reenvia todo o contexto dele, o que gasta o limite de uso do CEO.
7. Paralelo só com independência real.

## 4. Quando um modelo não está disponível
1. A convocação falha (ex.: "requires usage credits", modelo descontinuado) → não insistir.
2. Conferir se o agente chegou a mudar arquivos (`git status`) antes de relançar.
3. Relançar com o substituto da mesma faixa: Fable → Opus; Opus → Sonnet `xhigh`; Sonnet → Haiku `high` (só para tarefas simples).
4. Ajustar o inspetor para continuar com modelo diferente do programador.
5. Avisar o CEO em uma linha e registrar em `DECISION_LOG.md` se for uma mudança duradoura.

## 5. Transparência
Em cada convocação, dizer ao CEO: **agente · motivo · modelo · esforço · por quê** (uma linha). No relatório de entrega, listar os modelos efetivamente usados, inclusive substituições. As linhas de `METRICS.md` registram modelo/esforço de cada bloco.
