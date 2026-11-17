# Brief de tarefa — <ID> <título curto>

> Modelo para a convocação de um agente. Copiar para o pedido (prompt) e preencher. Itens que não se aplicam: escrever "não se aplica".

- **ID:** E<etapa>-<nn> [rN]
- **Agente / cargo:** <nome do agente>
- **Modelo · esforço:** <modelo> · <esforço> — **por quê:** <uma linha>
- **Objetivo:** <resultado esperado, em uma ou duas frases>

## Contexto
- O que já existe / o que aconteceu antes (resumo; relatórios longos: caminho do arquivo).
- Referência do "antes": último commit (`git diff HEAD`) ou <outro>.

## Arquivos
- Pode alterar: <lista>
- Não pode alterar: <lista> (sempre: `.snapshots/`, histórico do Git, configuração global)
- Pasta de rascunho: <caminho>

## Dependências e recursos
- Depende de: <tarefas/relatórios>
- **Gradle:** <liberado só para você | não use>
- **Emulador:** <liberado | não use — outro agente está nele>
- APK a gerar/usar: <nome identificado, ex.: app-dev-e5-03.apk>

## Restrições
- <regras do papel + específicas da tarefa; ex.: "não mexer nos pesos do BuildEngine">

## Critérios de conclusão
1. <verificável>
2. Testes: <todos passando; testes novos para …>
3. Arquivos temporários apagados.

## Relatório
Formato da definição do agente, mais: <itens específicos desta tarefa>.
