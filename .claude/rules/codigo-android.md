---
paths:
  - "app/**/*.kt"
  - "app/build.gradle.kts"
  - "app/src/**/AndroidManifest.xml"
---

# Regras para o código do app

- Só o `android-developer` edita estes arquivos, dentro do escopo de uma tarefa com ID; depois vem a revisão do `code-inspector`.
- Views programáticas (sem Compose). Nenhuma dependência nova sem autorização do CEO.
- Cada comportamento novo ou corrigido tem teste unitário (pastas `app/src/test/java/...`); os testes rodam no PC, sem Android (use `org.json`).
- Leitura de tela (`ScreenReader`/`ScreenParser`/`OverlayService`): cuidado com threads (OCR em segundo plano, Views só na thread principal), com a janela/bolha cobrindo a captura e com leituras ruins apagando dados já confirmados.
- Motor de cálculo (`GameDb`, `BuildEngine`, `RuneEngine`): ajustar o modelo, não decorar builds de jogadores.
- Permissões no Manifest: qualquer mudança passa pelo `security-compliance` e pelo CEO.
- Só um agente por vez roda o Gradle.
