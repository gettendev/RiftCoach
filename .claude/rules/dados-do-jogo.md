---
paths:
  - "app/src/main/assets/**"
  - "app/src/test/meta/**"
---

# Regras para dados do jogo

- Estes arquivos são a base de números do Wild Rift que o motor de cálculo usa. Um número errado vira build errada para o jogador.
- **Nunca inventar** estatísticas, números de habilidades/itens/runas ou nomes. Sem fonte → deixar sem preencher e registrar como pendência.
- Origem: base oficial da Tencent (`db_*.json`, versão 7.3), meta oficial (`rank.json`, `hero_list.json`), referência de jogadores só para calibrar (`app/src/test/meta/`). `champions.json` foi escrito de memória (não conferido). Nomes pt-BR com "(?)" em `names_pt.json` ainda não foram conferidos.
- Mudança nos assets é tarefa do `android-developer` (com fonte citada pelo `wildrift-data-analyst`) e passa pelo `code-inspector`.
- Arquivos grandes (`db_heroes.json` ≈ 2,7 MB, `meta_reference.json` ≈ 450 KB): inspecionar a estrutura e trechos; não copiar inteiros para o contexto.
