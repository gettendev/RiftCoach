---
name: engineering-cycle
description: Conduzir uma tarefa aprovada do RiftCoach do começo ao fim — delegar ao android-developer, inspeção independente, teste no emulador, verificação do supervisor e integração — com modelo e esforço escolhidos a cada convocação.
when_to_use: Depois que o CEO aprovou o escopo (feature-intake) ou para corrigir defeitos de algo já aprovado, inclusive rodadas de correção pedidas pelo inspetor ou pelo testador.
---

# Ciclo de engenharia

## 0. Preparar
- Ler a situação em `plan.md` §7 (o que está em andamento, quem está usando o Gradle/emulador).
- Conferir `git status`: o que já está sem commit e de quem é.
- Escolher modelo/esforço com `docs/operating-model/MODEL_ROUTING.md`. **O inspetor usa modelo diferente do programador do bloco.**
- Dizer ao CEO, uma linha por agente: quem, por quê, modelo, esforço.

## 1. Delegar a implementação
- Convocar `android-developer` com o modelo `docs/operating-model/templates/TASK_BRIEF.md` (ID, objetivo, contexto, arquivos, dependências, restrições, critérios de conclusão). Dizer explicitamente se ele está liberado para o Gradle e qual APK gerar (`app-dev-<id>.apk`).
- Tarefa estrutural ou arriscada: antes, `software-architect` para o plano.
- Paralelismo só com independência real (arquivos diferentes, um só no Gradle).
- Atualizar `plan.md` §7: "E5-xx em andamento: quem, modelo, esforço".

## 2. Conferir a entrega (supervisor)
- `git status` e `git diff --stat HEAD`: só os arquivos esperados mudaram?
- Resultados de teste nos XML (`TEST-*.xml`): total e falhas batem com o relato?
- APK existe com data/tamanho coerentes?
Se não bater: devolver ao programador antes de gastar inspeção.

## 3. Inspeção independente
- Convocar `code-inspector` (modelo diferente do programador) com: ID, o que foi pedido (link para o brief/relatório anterior), resumo do relato do programador, **os pontos de risco que o próprio programador apontou**, referência (`git diff HEAD`), se pode rodar o Gradle, pasta de rascunho.
- Relatos longos: salvar num arquivo e apontar para ele, em vez de colar.
- Veredito:
  - **reprovado / aprovado com correções obrigatórias** → devolver ao programador (mesmo agente se o contexto dele ainda for pequeno; senão, um novo com o relatório em arquivo) e repetir 2–3 (rodada `r2`, `r3`…).
  - **aprovado** (só recomendadas) → decidir quais recomendadas entram agora e quais vão para depois (registrar onde).

## 4. Teste no emulador (quando afeta o que o usuário vê ou a leitura de tela)
- Convocar `qa-tester` com o APK identificado e um roteiro focado no que mudou + checagem rápida do resto. Pode rodar ao mesmo tempo que a inspeção (ele não usa o Gradle).
- Problemas graves → corrigir já (volta ao passo 1). Médios → nesta tarefa ou na próxima, decisão do supervisor. Cosméticos → em bloco.

## 5. Integrar
- Todos os portões do manual (§6) passaram? Nada fora do escopo mudou?
- Atualizar `plan.md` (situação, números de testes, pendências) e `docs/operating-model/METRICS.md` (uma linha por bloco, só dados reais).
- **Commit + push**: bloco aprovado pelo inspetor → o supervisor faz commit (só os arquivos do bloco, mensagem com o ID) e push sem perguntar (autorização permanente, D-011) e avisa o CEO em uma linha. Trabalho não aprovado, release/tag ou reescrever histórico → pedir ao CEO. Autor e remoto já configurados no repositório; o `git push` usa o login salvo do CEO (se pedir login, rodar no painel de terminal do app para ele entrar).

## 6. Fechar
- Fim de etapa → `release-readiness` e relatório ao CEO (modelo `DELIVERY_REPORT.md`), parar e pedir aprovação.
- Não é fim de etapa → informar o CEO em poucas linhas e seguir para o próximo bloco aprovado.

## Quando rejeitar
Escopo não respeitado; teste faltando para o comportamento novo; regressão; número do jogo inventado; relato que não bate com o diff; arquivos temporários esquecidos; mudança em arquivo proibido para o papel.
