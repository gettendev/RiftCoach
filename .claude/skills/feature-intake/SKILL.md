---
name: feature-intake
description: Receber um pedido do CEO para o RiftCoach (funcionalidade nova, mudança de comportamento, melhoria) e transformá-lo em requisitos, critérios de aceitação e um resumo "tim tim por tim tim" para aprovação, antes de qualquer código.
when_to_use: Quando o CEO pedir algo novo ou uma mudança no que o app faz ou mostra. Não usar para correção de defeito já aprovado (use engineering-cycle) nem para perguntas simples.
---

# Recebimento de funcionalidade (feature intake)

Resultado: o CEO aprova (ou corrige) um entendimento claro do que será feito. **Nenhum código antes da aprovação.**

## 1. Classificar o pedido
- **Funcionalidade nova / mudança visível** → seguir esta skill inteira.
- **Defeito em algo já aprovado** → ir direto para `engineering-cycle` (perguntar ao CEO só se a correção mudar o que ele vê de forma relevante).
- **Pergunta / consulta** → responder, sem tarefa.
- **Pedido que exige instalar, gastar, serviço externo, commit/push** → a aprovação do CEO entra no próprio resumo.

## 2. Juntar o contexto (barato)
- Ler a parte relevante de `plan.md` (etapa atual, limitações conhecidas §8) e o backlog/etapas.
- Conferir no código o estado real do que será mudado (Grep/Read pontuais; não ler tudo).
- Se faltar informação do jogo, prever uma tarefa para o `wildrift-data-analyst`.

## 3. Especificar
- Pedido simples (1 tela, sem ambiguidade): o próprio supervisor escreve a especificação abaixo.
- Pedido grande ou ambíguo: convocar `product-manager` (sonnet, medium) com o modelo `docs/operating-model/templates/TASK_BRIEF.md`; se tocar em tela/janela, também `ux-ui-designer` (sonnet, medium); se for estrutural, `software-architect` (opus, high) para riscos.
Especificação mínima:
1. Objetivo (o problema do jogador).
2. Escopo e requisitos numerados.
3. Critérios de aceitação "Dado / Quando / Então", testáveis no emulador.
4. Restrições (overlay por cima do jogo, leitura de tela, celular do CEO, sem servidor).
5. Fora do escopo.
6. Dúvidas para o CEO (cada uma com a recomendação).

## 4. Apresentar ao CEO
Em português simples, sem termos técnicos:
- "O que vou fazer", em passos que ele reconheça no jogo/app.
- "O que muda para você" e "o que não muda".
- Dúvidas (uma por vez, recomendação primeiro).
- Quem será convocado, com modelo e esforço (uma linha por agente) e uma noção de custo de uso (baixo/médio/alto).
- Se compensar o Ultracode, dizer por quê e esperar a mensagem dele começando com `ultracode`.
Esperar a aprovação explícita.

## 5. Registrar
- Criar o ID da tarefa (`E<etapa>-<nn>`) e anotar em `plan.md` (situação da etapa).
- Decisões de escopo relevantes → `docs/operating-model/DECISION_LOG.md`.
- Seguir para `engineering-cycle`.
