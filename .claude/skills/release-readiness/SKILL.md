---
name: release-readiness
description: Verificar se uma versão do RiftCoach está pronta para entregar ao CEO (APK do celular), com checklist de testes, inspeção, teste no emulador, integridade do APK, documentação e itens pendentes, e montar o relatório de entrega.
when_to_use: Ao fim de cada etapa ou antes de gerar o RiftCoach.apk para o CEO instalar no celular.
---

# Prontidão para entrega

## 1. Checklist (todos com evidência)
| # | Item | Como conferir |
|---|---|---|
| 1 | Código da etapa inspecionado e aprovado | relatórios do `code-inspector` sem obrigatórias pendentes |
| 2 | Todos os testes passando | XML de resultado (`TEST-*.xml`) do último build, mesmo código do `git status` |
| 3 | Teste no emulador da versão final | relatório do `qa-tester` com o nome do APK; nenhum problema grave aberto |
| 4 | Sem travamentos | `logcat -b crash` vazio no relatório do testador |
| 5 | Nada fora do escopo | `git diff --stat` da etapa explicado item por item |
| 6 | Sem arquivos temporários | `git status` sem `Tmp*`, despejos ou logs no projeto |
| 7 | Documentação atualizada | `plan.md` (situação, testes, limitações), `DECISION_LOG.md`, `METRICS.md` |
| 8 | Pendências conhecidas listadas | §8 do `plan.md` |

Item falhou → não entregar; voltar ao `engineering-cycle`.

## 2. Gerar o APK do celular
- Gradle livre (ninguém mais usando). Convocar `release-devops` (sonnet, medium) ou o próprio `android-developer` para `:app:testDebugUnitTest :app:assembleDebug`.
- Copiar `$env:LOCALAPPDATA\RiftCoachBuild\app\outputs\apk\debug\app-debug.apk` para `RiftCoach.apk` na raiz (ignorado pelo Git).
- Registrar tamanho, data, SHA-256 (`Get-FileHash`), `versionCode/versionName`. Mudar a versão só com o CEO de acordo.

## 3. Relatório ao CEO
Usar `docs/operating-model/templates/DELIVERY_REPORT.md`, em português simples:
- O que mudou para ele (antes × depois, com exemplos do jogo).
- O que testar no celular, passo a passo.
- Limitações e o que ficou para depois.
- Agentes/modelos/esforços usados e retrabalho.
- Perguntas: aprovar a etapa? autorizar commit/push? próxima etapa (com modelo/esforço recomendados)?

## 4. Depois da resposta do CEO
- Aprovado + commit autorizado → commit com mensagem clara; push só se autorizado.
- Problema no celular → registrar como defeito (grave/médio/cosmético) e voltar ao `engineering-cycle`.
