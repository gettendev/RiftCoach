# Passagem de trabalho — <ID> de <agente/cargo> para <agente/cargo>

> Usar quando o trabalho de um agente segue para outro (programador → inspetor, inspetor → programador, testador → programador) ou quando um agente é substituído por outro. O supervisor preenche a partir do relatório recebido e salva em arquivo se for longo.

- **ID / rodada:** E<etapa>-<nn> rN
- **De → Para:** <quem entregou> → <quem recebe>
- **Data:** <dd/mm/aaaa>

## O que foi feito
- <itens concluídos, com arquivo/função>

## Estado verificado pelo supervisor
- `git status` / `git diff --stat`: <confere com o relato? sim/não + diferenças>
- Testes: <total / falhas> (arquivo de resultado conferido)
- APK: <caminho, se houver>

## O que o próximo deve fazer
1. <pedido objetivo>
2. <pontos de risco apontados por quem entregou>

## Pendências e decisões já tomadas
- <o que fica para depois e onde está registrado>
- <decisões do supervisor/CEO que não devem ser revistas>

## Onde estão os detalhes
- Relatório completo: <caminho>
- Evidências (prints, logs, despejos): <caminho>
