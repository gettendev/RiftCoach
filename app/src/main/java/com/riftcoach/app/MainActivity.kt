package com.riftcoach.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.riftcoach.app.Ui.MATCH
import com.riftcoach.app.Ui.WRAP
import com.riftcoach.app.Ui.lp

/** Tela inicial: permissões, dados e o botão que liga a bolha. */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var overlayBtn: TextView
    private lateinit var dataStatus: TextView
    private lateinit var tierRow: LinearLayout
    private lateinit var laneRow: LinearLayout
    private lateinit var readRow: LinearLayout
    private lateinit var toggle: TextView

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.ensureLoaded(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(28f))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col)
        }
        // no Android 15+ a tela desenha atrás das barras do sistema: compensa com espaçamento
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }

        col.addView(Ui.text(this, "RiftCoach", 28f, Ui.GOLD, true))
        col.addView(
            Ui.text(this, "Assistente de draft para Wild Rift", 14f, Ui.MUTED),
            lp(WRAP, WRAP).apply { topMargin = dp(4f); bottomMargin = dp(18f) }
        )

        // 1) permissão
        val c1 = card(col, "1. Permissão para aparecer sobre o jogo")
        overlayStatus = Ui.text(this, "", 13f)
        c1.addView(overlayStatus)
        overlayBtn = button("Conceder permissão", false) { askOverlay() }
        c1.addView(overlayBtn, lp(WRAP, WRAP).apply { topMargin = dp(10f) })

        // 2) dados
        val c2 = card(col, "2. Dados do meta")
        dataStatus = Ui.text(this, "", 13f).apply { setLineSpacing(0f, 1.2f) }
        c2.addView(dataStatus)
        c2.addView(Ui.text(this, "Faixa de elo usada nas estatísticas:", 12f, Ui.MUTED), lp(WRAP, WRAP).apply { topMargin = dp(12f) })
        tierRow = wrapRow(c2)
        c2.addView(button("Atualizar dados agora", false) { updateData() }, lp(WRAP, WRAP).apply { topMargin = dp(12f) })
        c2.addView(
            Ui.text(this, "Base de dados do jogo: status de campeões, habilidades, itens, runas e feitiços.", 12f, Ui.MUTED),
            lp(WRAP, WRAP).apply { topMargin = dp(14f) }
        )
        c2.addView(
            button("Ver a base de dados", false) { startActivity(Intent(this, DbActivity::class.java)) },
            lp(WRAP, WRAP).apply { topMargin = dp(8f) }
        )

        // 3) leitura automática
        val cr = card(col, "3. Leitura automática da tela")
        cr.addView(
            Ui.text(
                this,
                "O app lê a seleção de campeões e preenche bans, picks e rotas sozinho. " +
                    "Ao ligar a bolha, o Android pede permissão para capturar a tela: aceite. " +
                    "A captura fica só no seu celular; nada é enviado para fora.",
                12f, Ui.MUTED
            ).apply { setLineSpacing(0f, 1.2f) }
        )
        readRow = wrapRow(cr)

        // 4) rota
        val c3 = card(col, "4. Sua rota")
        c3.addView(Ui.text(this, "Com a leitura ligada, o app descobre sozinho. Dá para trocar na janela flutuante.", 12f, Ui.MUTED))
        laneRow = wrapRow(c3)

        // liga/desliga
        toggle = button("", true) { toggleBubble() }.apply {
            textSize = 16f
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        }
        col.addView(toggle, lp(MATCH, WRAP).apply { topMargin = dp(6f); bottomMargin = dp(16f) })

        // ajuda
        val c4 = card(col, "Como usar")
        c4.addView(
            Ui.text(
                this,
                "1. Ligue a bolha, aceite a captura de tela e abra o Wild Rift.\n" +
                    "2. Na seleção de campeões, toque na bolha RC para abrir a janela.\n" +
                    "3. Aba Bans: os melhores bans para a sua rota.\n" +
                    "4. Aba Picks: o app preenche sozinho o que aliados e inimigos confirmaram " +
                    "e ajusta as sugestões a cada pick. Se ele errar algum, toque no espaço e corrija.\n" +
                    "5. Aba Análise: plano de jogo dos dois times e ajustes de build.\n\n" +
                    "Deixe a janela no centro da tela: o app lê os nomes nas colunas laterais, " +
                    "e o que a janela cobrir ele não enxerga. Para escolher seu campeão no jogo, feche a janela no ✕; a leitura continua.\n\n" +
                    "O botão ◐ muda a transparência da janela. Arraste a bolha e a janela para onde quiser.\n\n" +
                    "Celulares Xiaomi/POCO: em Configurações > Apps > RiftCoach, ative \"Início automático\" " +
                    "e em \"Economia de bateria\" escolha \"Sem restrições\". Sem isso o sistema pode fechar a bolha.",
                12.5f, Ui.TEXT
            ).apply { setLineSpacing(0f, 1.25f) }
        )

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ----------------------------------------------------------- componentes

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
            background = Ui.round(Ui.CARD, dp(12f).toFloat())
        }
        c.addView(Ui.text(this, title, 14f, Ui.GOLD, true), lp(WRAP, WRAP).apply { bottomMargin = dp(8f) })
        parent.addView(c, lp(MATCH, WRAP).apply { bottomMargin = dp(12f) })
        return c
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        Ui.text(this, label, 13.5f, if (primary) Ui.BG else Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = Ui.round(if (primary) Ui.GOLD else Ui.withAlpha(0xFFFFFF, 0.12f), dp(8f).toFloat())
            setOnClickListener { onClick() }
        }

    /** Linha de etiquetas dentro de um cartão (rola de lado se não couber). */
    private fun wrapRow(parent: LinearLayout): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        parent.addView(hs, lp(MATCH, WRAP).apply { topMargin = dp(8f) })
        return row
    }

    // ----------------------------------------------------------------- estado

    private fun refresh() {
        val ok = Settings.canDrawOverlays(this)
        overlayStatus.text = if (ok) "✓ Permissão concedida." else "Falta permitir que o RiftCoach apareça sobre outros apps."
        overlayStatus.setTextColor(if (ok) Ui.GREEN else Ui.RED)
        overlayBtn.visibility = if (ok) View.GONE else View.VISIBLE

        val d = Repo.data
        dataStatus.text = "Estatísticas de ${d?.dateLabel()} · ${d?.champs?.size} campeões\n" +
            "Fonte: lolm.qq.com (servidor chinês). Atualiza sozinho a cada 12 horas quando a bolha é ligada."

        tierRow.removeAllViews()
        val tier = Repo.engine?.tier
        for ((id, label) in Engine.TIERS) {
            val c = Ui.chip(this, label, id == tier, 12.5f)
            c.setOnClickListener {
                Repo.setTier(this, id)
                refresh()
            }
            tierRow.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(6f) })
        }

        readRow.removeAllViews()
        val auto = Repo.autoRead(this)
        for ((on, label) in listOf(true to "Ligada", false to "Desligada (marcar à mão)")) {
            val c = Ui.chip(this, label, on == auto, 12.5f)
            c.setOnClickListener {
                Repo.setAutoRead(this, on)
                if (OverlayService.running)
                    Toast.makeText(this, "Vale na próxima vez que a bolha for ligada.", Toast.LENGTH_SHORT).show()
                refresh()
            }
            readRow.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(6f) })
        }

        laneRow.removeAllViews()
        for (l in Lane.entries) {
            val c = Ui.chip(this, l.label, l == Repo.draft.myLane, 12.5f)
            c.setOnClickListener {
                Repo.setLane(this, l)
                refresh()
            }
            laneRow.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(6f) })
        }

        toggle.text = if (OverlayService.running) "DESLIGAR BOLHA FLUTUANTE" else "LIGAR BOLHA FLUTUANTE"
    }

    private fun askOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun toggleBubble() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Primeiro conceda a permissão do passo 1.", Toast.LENGTH_LONG).show()
                askOverlay()
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            if (Repo.autoRead(this)) {
                // pede a permissão de captura; o serviço começa quando o usuário responder
                val mpm = getSystemService(MediaProjectionManager::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
                return
            }
            startForegroundService(Intent(this, OverlayService::class.java))
        }
        toggle.postDelayed({ refresh() }, 400)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val i = Intent(this, OverlayService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            i.putExtra(OverlayService.EXTRA_CODE, resultCode)
            i.putExtra(OverlayService.EXTRA_DATA, data)
        } else {
            Toast.makeText(this, "Sem permissão de captura: a bolha abre no modo manual.", Toast.LENGTH_LONG).show()
        }
        startForegroundService(i)
        toggle.postDelayed({ refresh() }, 400)
    }

    private companion object {
        const val REQ_CAPTURE = 7
    }

    private fun updateData() {
        dataStatus.text = "Baixando dados novos…"
        Repo.update(this) { err ->
            if (err == null) Toast.makeText(this, "Dados atualizados.", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "Não deu para atualizar: $err", Toast.LENGTH_LONG).show()
            refresh()
        }
    }
}
