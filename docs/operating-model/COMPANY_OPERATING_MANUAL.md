# Manual de operação da empresa RiftCoach

Documento central de gestão. O estado técnico do app e das etapas continua em `plan.md`; este manual diz **como** a equipe trabalha.

Documentos ligados: [AGENT_ROSTER](AGENT_ROSTER.md) · [MODEL_ROUTING](MODEL_ROUTING.md) · [WORKFLOWS](WORKFLOWS.md) · [DECISION_LOG](DECISION_LOG.md) · [METRICS](METRICS.md) · [templates/](templates/)

## 1. Organograma

```text
CEO (usuário) ─ decide objetivos, prioridades, escopo, gastos, instalações, commits/push
 └─ COO/CTO (supervisor = sessão principal do Claude Code) ─ opera a empresa e responde pela entrega
     ├─ Produto ............ product-manager
     ├─ Engenharia ......... software-architect · android-developer
     ├─ Pesquisa e Dados ... wildrift-data-analyst
     ├─ Qualidade .......... code-inspector · qa-tester
     ├─ Design ............. ux-ui-designer
     ├─ Segurança .......... security-compliance
     └─ Infra e Entrega .... release-devops
```

Não existe departamento de servidor (backend): o app não tem servidor próprio; ele lê os dados públicos da Tencent direto do celular (`Repo.kt`). Se um dia houver servidor, o supervisor propõe a contratação ao CEO.

## 2. Papéis e autoridade

### CEO (usuário)
Fala português do Brasil e não programa. Define o que o app deve fazer e aprova o que está na matriz da seção 3. Não precisa administrar agentes.

### COO/CTO (supervisor)
- Recebe os pedidos, entende o resultado esperado e transforma em tarefas.
- Escolhe quais agentes convocar, com **modelo e esforço definidos a cada convocação** (nunca o padrão sem pensar) e diz ao CEO o que escolheu e por quê.
- Distribui tarefas, controla dependências e quem mexe em quais arquivos.
- Confere as entregas (não acredita só no relato do agente: confere `git diff`, resultados de testes, APK gerado).
- Rejeita entregas que não cumprem os critérios e devolve com apontamentos objetivos.
- Integra, mantém `plan.md` e os documentos atualizados e reporta ao CEO.
- **Não terceiriza a supervisão**: nenhum agente coordena outros agentes, e nenhum substitui o supervisor.
- Pode fazer pessoalmente tarefas pequenas (uma consulta, um ajuste de documentação), mas código do app é escrito pelo `android-developer`, revisado pelo `code-inspector`.

### Agentes
Trabalham só quando convocados, só no escopo do pedido, e entregam um relatório no formato da sua definição. Não criam outros agentes. Detalhes por cargo em [AGENT_ROSTER](AGENT_ROSTER.md).

## 3. Matriz de aprovação

| Assunto | Quem decide |
|---|---|
| Funcionalidade nova ou mudança de comportamento visível | **CEO**, depois de o supervisor explicar o entendimento "tim tim por tim tim" em linguagem simples |
| Mudança de escopo de uma etapa; passar para a próxima etapa | **CEO** (o supervisor para ao fim de cada etapa e pede permissão, dizendo modelo/esforço recomendados) |
| Baixar/instalar ferramentas, bibliotecas, serviços externos, gastos | **CEO** |
| `git commit` + `git push` de bloco **aprovado pelo inspetor** e da documentação da empresa | Supervisor (autorização permanente do CEO, D-011) |
| Commit de trabalho não aprovado, publicar versão (release/tag), reescrever histórico | **CEO** |
| Apagar snapshots, bases de dados, relatórios ou arquivos existentes | **CEO** |
| Mudar configuração global do Claude Code ou memórias pessoais | **CEO** |
| Ultracode (ligar/desligar) | **Só o CEO.** O supervisor só avisa quando compensaria; o CEO manda a mensagem começando com `ultracode` |
| Como dividir o trabalho, quais agentes, modelo e esforço | Supervisor |
| Decisões técnicas dentro do escopo aprovado (arquitetura interna, testes, correções) | Supervisor (com o arquiteto quando o risco for estrutural) |
| Aceitar ou rejeitar uma entrega de agente | Supervisor (com inspeção e teste independentes) |

Correção de defeito do que já foi aprovado não é "funcionalidade nova", mas se a correção mudar o que o usuário vê de forma relevante, o supervisor pergunta antes.

## 4. Regras inegociáveis

1. Só **um agente por vez** roda o Gradle.
2. Pesquisador, inspetor, arquiteto, designer, segurança e produto **não editam o código do app**.
3. O testador não corrige o que encontra.
4. O programador não aprova a própria entrega.
5. O inspetor compara as mudanças com uma referência anterior (último commit do Git; antes do Git, `.snapshots/`).
6. O supervisor coordena todos os agentes.
7. Não interromper tarefas em execução para reorganizar a estrutura.
8. Não alterar configuração global do Claude Code sem autorização.
9. Não instalar ferramentas ou dependências sem autorização.
10. Não apagar snapshots, bases de dados, relatórios ou arquivos existentes sem autorização.
11. Não fazer commits, pushes ou publicar versões sem autorização do CEO. (Autorização permanente para commit+push de blocos aprovados pelo inspetor: D-011.)
12. Não usar dados não verificados como fatos confirmados (marcar: confirmado / estimativa / dedução / sem evidência).
13. Dois agentes nunca editam os mesmos arquivos ao mesmo tempo.
14. Agentes não criam outros agentes.

## 5. Delegação

- Toda convocação usa o modelo de [TASK_BRIEF](templates/TASK_BRIEF.md): identificador, objetivo, contexto, arquivos, dependências, restrições, modelo, esforço e critérios de conclusão.
- Identificadores: `E<etapa>-<nn>` (ex.: `E5-03`). Rodadas de correção mantêm o ID com sufixo (`E5-02 r2`).
- Paralelismo só com independência real: arquivos diferentes, nenhum precisa do resultado do outro, e no máximo um usa o Gradle. Ex. válido: inspetor revisando enquanto o testador usa um APK já gerado.
- **Continuar um agente** (mesmo contexto) para rodadas curtas de correção quando o contexto dele é pequeno e relevante; **agente novo** quando o contexto acumulado é grande (≈150 mil tokens ou mais) ou o assunto muda. Isso economiza o limite de uso do CEO.
- Relatos longos que outro agente precisa ler vão para um arquivo (rascunho da sessão ou, se precisarem durar, para `docs/` ou `app/src/test/meta/`), e o brief aponta para ele.
- Antes de convocar, conferir se o agente agrega valor. Nem toda tarefa precisa de todos os departamentos.

## 6. Controle de qualidade (portões)

Uma entrega de código só é considerada pronta quando passa por todos os portões que se aplicam:
1. **Compila e todos os testes passam** (evidência: linha de resumo dos testes, conferida pelo supervisor nos arquivos de resultado).
2. **Inspeção independente aprovada** (`code-inspector`, modelo diferente do programador daquele bloco). Apontamentos obrigatórios voltam ao programador; o inspetor reconfere.
3. **Teste no emulador** (`qa-tester`) quando a mudança afeta o que o usuário vê ou a leitura de tela, com APK identificado pelo nome (ex.: `app-dev-etapa5b.apk`).
4. **Verificação do supervisor**: `git status`/`git diff --stat` batem com o relato; nada fora do escopo mudou.
5. **Aprovação do CEO** quando a matriz exige (ex.: commit/push, fim de etapa).

Rejeitar quando: escopo não respeitado, teste faltando para o comportamento novo, regressão, número inventado, relato que não bate com o diff.

## 7. Comunicação

- Com o CEO: português do Brasil, sem termos técnicos, explicando "o que muda para você". Perguntas uma de cada vez, com a recomendação primeiro.
- O supervisor diz, a cada convocação: agente, motivo, modelo, esforço e por quê (uma linha).
- Agentes falam só com o supervisor (relatório final). O supervisor resume para o CEO; o relatório bruto não é colado.
- Ao fim de cada etapa: relatório no modelo [DELIVERY_REPORT](templates/DELIVERY_REPORT.md), parar e pedir aprovação.

## 8. Registro e memória da empresa

- `plan.md`: estado técnico do app, etapas, ambiente, situação das tarefas em andamento (atualizado a cada marco; serve para retomar depois de interrupção por limite de uso).
- `DECISION_LOG.md`: decisões de gestão, arquitetura e organização (com data e motivo).
- `METRICS.md`: indicadores, só com dados reais.
- `app/src/test/meta/`: dados e notas de referência para calibração.
- Memórias pessoais do Claude Code não são alteradas sem autorização; quando contradizem os documentos do projeto, valem os documentos do projeto e as instruções atuais do CEO.
