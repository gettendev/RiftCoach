# Cadastro de agentes

Definições em `.claude/agents/<nome>.md` (nome, quando convocar, ferramentas, modelo e esforço padrão, limites, formato do relatório). O supervisor pode trocar modelo e esforço a cada convocação (ver [MODEL_ROUTING](MODEL_ROUTING.md)). Nenhum agente tem a ferramenta de criar outros agentes.

Situação em 17/11/2026: as definições foram criadas nesta data. Antes disso, os papéis existiam só como instruções escritas pelo supervisor a cada convocação (agente genérico). O conhecimento acumulado (comandos do Gradle, dicas do emulador, fontes de dados, formato dos relatórios) foi incorporado às definições.

| Agente | Cargo | Departamento | Modelo / esforço padrão | Ferramentas | Edita código do app? | Uso | Origem |
|---|---|---|---|---|---|---|---|
| — (sessão principal) | COO/CTO, supervisor | Diretoria | escolhido pelo CEO no app | todas | não (coordena) | sempre | — |
| `product-manager` | Gerente de Produto | Produto | Sonnet / medium | Read, Grep, Glob, Write, Edit | não (só `docs/product/`) | sob demanda: pedidos novos grandes ou ambíguos | novo |
| `software-architect` | Arquiteto de Software | Engenharia | Opus / high | Read, Grep, Glob, PowerShell, Write | não (só documentos) | sob demanda: mudanças estruturais/arriscadas | novo |
| `android-developer` | Engenheiro Android | Engenharia | Sonnet / high | Read, Grep, Glob, Edit, Write, PowerShell | **sim** (único) | frequente | reaproveitado do "programador" |
| `wildrift-data-analyst` | Analista de Pesquisa e Dados | Pesquisa e Dados | Sonnet / medium | Read, Grep, Glob, WebSearch, WebFetch, PowerShell, Write | não (só relatórios/referências) | sob demanda: dados do jogo, meta, calibração | reaproveitado do "pesquisador" |
| `code-inspector` | Inspetor de Código | Qualidade | Opus / high | Read, Grep, Glob, PowerShell, Write | não (só teste temporário autorizado) | frequente: todo bloco de código | reaproveitado do "inspetor" |
| `qa-tester` | Engenheiro de QA | Qualidade | Sonnet / medium | Read, Grep, Glob, PowerShell, Write | não | frequente: mudanças visíveis/leitura de tela | reaproveitado do "testador" |
| `ux-ui-designer` | Designer de UX/UI | Design | Sonnet / medium | Read, Grep, Glob, Write | não (só `docs/design/`) | sob demanda: telas/janela/fluxos | novo |
| `security-compliance` | Segurança e Conformidade | Segurança | Opus / high | Read, Grep, Glob, PowerShell, WebSearch, WebFetch, Write | não (só relatório) | sob demanda: auditorias, permissões, políticas Riot | novo |
| `release-devops` | Release e DevOps | Infra e Entrega | Sonnet / medium | Read, Grep, Glob, PowerShell, Write, Edit | não (só versão no `build.gradle.kts` quando pedido, docs/tools) | sob demanda: fim de etapa, APK de entrega | novo |

## Observações
- **Write/Edit em agentes que não programam** existe para produzirem seus documentos; o limite de pastas está escrito na definição de cada um e é conferido pelo supervisor no `git status`.
- **PowerShell** é a ferramenta de terminal deste computador (Windows). Em outro ambiente com Bash, as definições precisam ser revistas (ferramenta inexistente em `tools` impede o agente de iniciar).
- Não há agente de servidor (backend): o app não tem servidor próprio (decisão D-005).
- Histórico de desempenho por agente: [METRICS](METRICS.md).
