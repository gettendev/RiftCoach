package com.riftcoach.app

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.riftcoach.app.Ui.MATCH
import com.riftcoach.app.Ui.WRAP
import com.riftcoach.app.Ui.lp

/** A janela semitransparente que abre ao tocar na bolha. */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class Panel(private val ctx: Context, private val host: Host) {

    interface Host {
        fun closePanel()
        fun setPanelFocusable(focusable: Boolean)
        fun dragPanel(dx: Int, dy: Int)
    }

    val root: LinearLayout
    private val header: LinearLayout
    private val laneRow: LinearLayout
    private val scroll: ScrollView
    private val body: LinearLayout
    private val pickerHost: LinearLayout
    private var searchBox: EditText? = null
    private var status: TextView? = null

    private val draft get() = Repo.draft
    private val tracker get() = Repo.tracker
    private val engine get() = Repo.engine!!

    private fun dp(v: Float) = Ui.dp(ctx, v)

    init {
        root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(6f), dp(6f))
        }
        laneRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f), 0, dp(8f), dp(6f))
            visibility = View.GONE
        }
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(2f), dp(10f), dp(10f))
        }
        scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        pickerHost = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(2f), dp(10f), dp(8f))
            visibility = View.GONE
        }
        val content = FrameLayout(ctx).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(pickerHost, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(header, lp(MATCH, WRAP))
        root.addView(laneRow, lp(MATCH, WRAP))
        root.addView(content, lp(MATCH, 0, 1f))
        applyOpacity()
        render()
    }

    // ------------------------------------------------------------ cabeçalho

    /** O alfa vai só na cor do fundo (não em `root.alpha`, que deixaria o texto translúcido junto). */
    private fun applyOpacity() {
        val idx = Repo.prefs(ctx).getInt("opacity", 1).coerceIn(0, OPACITY.size - 1)
        root.background = Ui.round(
            Ui.withAlpha(Ui.BG, OPACITY[idx]), dp(12f).toFloat(), Ui.withAlpha(Ui.GOLD, 0.55f), dp(1f)
        )
    }

    private fun dragListener() = object : View.OnTouchListener {
        var lx = 0f
        var ly = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.rawX
                    ly = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lx).toInt()
                    val dy = (e.rawY - ly).toInt()
                    if (dx != 0 || dy != 0) {
                        host.dragPanel(dx, dy)
                        lx += dx
                        ly += dy
                    }
                }
            }
            return true
        }
    }

    private fun buildHeader() {
        header.removeAllViews()
        val gap = dp(5f)

        val laneBtn = Ui.chip(ctx, draft.myLane.short + " ▾", true, 12f)
        laneBtn.setOnClickListener {
            laneRow.visibility = if (laneRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        header.addView(laneBtn, lp(WRAP, WRAP))

        val tabs = listOf("Bans", "Picks", "Análise")
        for (i in tabs.indices) {
            val sel = i == tab
            val t = Ui.text(ctx, tabs[i], 13f, if (sel) Ui.GOLD else Ui.MUTED, true).apply {
                setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
                if (sel) background = Ui.round(Ui.withAlpha(Ui.GOLD, 0.16f), dp(6f).toFloat())
                setOnClickListener {
                    tab = i
                    closePicker()
                    render()
                }
            }
            header.addView(t, lp(WRAP, WRAP).apply { leftMargin = gap })
        }

        // área livre do cabeçalho: arrastar a janela; também mostra o estado da leitura da tela
        val grip = Ui.text(ctx, "", 10f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setOnTouchListener(dragListener())
        }
        status = grip
        refreshStatus()
        header.addView(grip, lp(0, dp(28f), 1f))

        val opacity = Ui.chip(ctx, "◐", false, 13f).apply {
            setOnClickListener {
                val p = Repo.prefs(ctx)
                p.edit().putInt("opacity", (p.getInt("opacity", 1) + 1) % OPACITY.size).apply()
                applyOpacity()
            }
        }
        header.addView(opacity, lp(WRAP, WRAP))

        val reset = Ui.chip(ctx, "Novo", false, 12f).apply {
            setOnClickListener {
                draft.reset()
                Repo.tracker.reset()
                tab = 0
                closePicker()
                render()
            }
        }
        header.addView(reset, lp(WRAP, WRAP).apply { leftMargin = gap })

        val close = Ui.chip(ctx, "✕", false, 13f).apply {
            setOnClickListener { host.closePanel() }
        }
        header.addView(close, lp(WRAP, WRAP).apply { leftMargin = gap })

        laneRow.removeAllViews()
        laneRow.addView(Ui.text(ctx, "Sua rota:", 11f, Ui.MUTED).apply { gravity = Gravity.CENTER_VERTICAL }, lp(WRAP, MATCH))
        for (l in Lane.entries) {
            val c = Ui.chip(ctx, l.label, l == draft.myLane, 11f)
            c.setOnClickListener {
                Repo.setLane(ctx, l)
                Repo.tracker.userSetLane()
                laneRow.visibility = View.GONE
                render()
            }
            laneRow.addView(c, lp(WRAP, WRAP).apply { leftMargin = gap })
        }
    }

    /** Atualiza o texto de estado da leitura automática no cabeçalho. */
    fun refreshStatus() {
        val s = status ?: return
        when (Repo.readerState) {
            Repo.READER_READING -> {
                s.text = "● lendo a tela"
                s.setTextColor(Ui.GREEN)
            }
            Repo.READER_WAITING -> {
                s.text = "○ aguardando draft"
                s.setTextColor(Ui.withAlpha(Ui.MUTED, 0.8f))
            }
            else -> {
                s.text = "⋮⋮ manual"
                s.setTextColor(Ui.withAlpha(Ui.MUTED, 0.7f))
            }
        }
    }

    // --------------------------------------------------------------- render

    fun render() {
        buildHeader()
        body.removeAllViews()
        when (tab) {
            0 -> renderBans()
            1 -> renderPicks()
            else -> renderAnalysis()
        }
    }

    private fun section(title: String, top: Float = 10f) {
        body.addView(
            Ui.text(ctx, title, 10.5f, Ui.GOLD, true).apply { letterSpacing = 0.06f },
            lp(MATCH, WRAP).apply { topMargin = dp(top); bottomMargin = dp(5f) }
        )
    }

    private fun note(s: String, color: Int = Ui.MUTED, sp: Float = 11f) {
        body.addView(Ui.text(ctx, s, sp, color).apply { setLineSpacing(0f, 1.15f) }, lp(MATCH, WRAP).apply { bottomMargin = dp(4f) })
    }

    private fun grid2(cells: List<View>) {
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(cells[i], lp(0, WRAP, 1f).apply { rightMargin = dp(3f) })
            if (i + 1 < cells.size) row.addView(cells[i + 1], lp(0, WRAP, 1f).apply { leftMargin = dp(3f) })
            else row.addView(View(ctx), lp(0, 1, 1f).apply { leftMargin = dp(3f) })
            body.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(6f) })
            i += 2
        }
    }

    private fun pct(v: Double): String = String.format("%.1f%%", v).replace('.', ',')

    // ----------------------------------------------------------------- BANS

    private fun banCell(s: Suggestion, showLane: Boolean): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(5f), dp(6f), dp(5f))
            background = Ui.round(Ui.withAlpha(0xFFFFFF, 0.07f), dp(8f).toFloat())
        }
        row.addView(Ui.icon(ctx, s.champ), lp(dp(34f), dp(34f)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = s.champ.name + (if (showLane) "  · " + s.lane.short else "")
        col.addView(Ui.text(ctx, title, 12.5f, Ui.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(
            Ui.text(ctx, "WR ${pct(s.stat.wr)} · Ban ${pct(s.stat.br)} · Pick ${pct(s.stat.pr)}", 9.5f, Ui.MUTED).apply { maxLines = 1 },
            lp(WRAP, WRAP).apply { topMargin = dp(2f) }
        )
        if (s.reasons.isNotEmpty()) {
            col.addView(
                Ui.text(ctx, s.reasons.joinToString(" • "), 9.5f, Ui.BLUE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                lp(WRAP, WRAP).apply { topMargin = dp(1f) }
            )
        }
        row.addView(col, lp(0, WRAP, 1f).apply { leftMargin = dp(7f) })
        row.setOnClickListener {
            draft.bans.add(s.champ.id)
            tracker.userBanned(s.champ.id)
            render()
        }
        return row
    }

    private fun renderBans() {
        val advice = engine.suggestBans(draft)
        section("MELHORES BANS PARA A SUA ROTA — ${draft.myLane.label.uppercase()}", 4f)
        if (advice.lane.isEmpty()) note("Sem dados para esta rota.")
        grid2(advice.lane.map { banCell(it, false) })

        section("AMEAÇAS DE OUTRAS ROTAS")
        grid2(advice.global.map { banCell(it, true) })

        section("JÁ BANIDOS (toque num campeão acima para marcar)")
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (id in draft.bans.toList()) {
            val c = engine.champ(id) ?: continue
            val iv = Ui.icon(ctx, c, Ui.RED)
            iv.setOnClickListener {
                draft.bans.remove(id)
                tracker.userRemoved(id)
                render()
            }
            row.addView(iv, lp(dp(32f), dp(32f)).apply { rightMargin = dp(5f) })
        }
        row.addView(plus(32f) {
            openPicker("Marcar campeão banido", null, false) { id ->
                if (id != null) {
                    draft.bans.add(id)
                    tracker.userBanned(id)
                }
                render()
            }
        })
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        body.addView(hs, lp(MATCH, WRAP))
        note("Dados de ${Repo.data?.dateLabel()} · ${tierLabel()} · fonte: lolm.qq.com", Ui.withAlpha(Ui.MUTED, 0.7f), 9.5f)
    }

    private fun tierLabel(): String = Engine.TIERS.firstOrNull { it.first == engine.tier }?.second ?: ""

    // ---------------------------------------------------------------- PICKS

    private fun plus(sizeDp: Float, onClick: () -> Unit): View =
        Ui.text(ctx, "+", sizeDp * 0.5f, Ui.MUTED, true).apply {
            gravity = Gravity.CENTER
            background = Ui.oval(Ui.withAlpha(0xFFFFFF, 0.08f), Ui.withAlpha(Ui.MUTED, 0.6f), dp(1f))
            layoutParams = lp(dp(sizeDp), dp(sizeDp))
            setOnClickListener { onClick() }
        }

    private fun slot(label: String, champ: Champion?, ring: Int, labelColor: Int, onClick: () -> Unit): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick() }
        }
        if (champ != null) {
            box.addView(Ui.icon(ctx, champ, ring), lp(dp(40f), dp(40f)))
        } else {
            val p = plus(40f, onClick)
            if (ring != 0) p.background = Ui.oval(Ui.withAlpha(0xFFFFFF, 0.08f), ring, dp(2f))
            box.addView(p)
        }
        box.addView(
            Ui.text(ctx, label, 9f, labelColor, true).apply { gravity = Gravity.CENTER; maxLines = 1 },
            lp(WRAP, WRAP).apply { topMargin = dp(2f) }
        )
        return box
    }

    private fun teamRow(title: String, color: Int, slots: List<View>) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Ui.text(ctx, title, 9.5f, color, true), lp(dp(58f), WRAP))
        for (s in slots) row.addView(s, lp(0, WRAP, 1f))
        body.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(4f); bottomMargin = dp(4f) })
    }

    private fun renderPicks() {
        // --- meu time
        val mine = ArrayList<View>()
        val me = engine.champ(draft.myPick)
        mine.add(slot("VOCÊ · " + draft.myLane.short, me, Ui.GOLD, Ui.GOLD) {
            openPicker("Seu campeão", draft.myLane, me != null) { id ->
                setMyPick(id)
                render()
            }
        })
        for (l in Lane.entries) {
            if (l == draft.myLane) continue
            val c = engine.champ(draft.allies[l])
            mine.add(slot(l.short, c, 0, Ui.MUTED) {
                openPicker("Aliado — ${l.label}", l, c != null) { id ->
                    tracker.userRemoved(draft.allies[l])
                    if (id == null) {
                        draft.allies.remove(l)
                    } else {
                        draft.allies[l] = id
                        tracker.userSetAlly(l)
                        tracker.userPicked(id)
                    }
                    render()
                }
            })
        }
        teamRow("SEU TIME", Ui.BLUE, mine)

        // --- inimigos, com a rota deduzida
        val lanes = engine.deduceEnemyLanes(draft.enemies)
        val foes = ArrayList<View>()
        for (i in 0 until 5) {
            val id = draft.enemies.getOrNull(i)
            val c = engine.champ(id)
            val foeLane = if (id != null) lanes[id] else null
            val label = if (id != null) (foeLane?.short ?: "?") else "—"
            foes.add(slot(label, c, 0, if (foeLane == draft.myLane) Ui.RED else Ui.MUTED) {
                openPicker("Campeão inimigo", null, c != null) { picked ->
                    if (i < draft.enemies.size) tracker.userRemoved(draft.enemies[i])
                    if (picked == null) {
                        if (i < draft.enemies.size) draft.enemies.removeAt(i)
                    } else {
                        if (i < draft.enemies.size) draft.enemies[i] = picked else draft.enemies.add(picked)
                        tracker.userPicked(picked)
                    }
                    render()
                }
            })
        }
        teamRow("INIMIGOS", Ui.RED, foes)
        if (draft.enemies.isNotEmpty())
            note("A rota embaixo de cada inimigo é deduzida. Em vermelho: quem deve cair contra você.", Ui.withAlpha(Ui.MUTED, 0.75f), 9.5f)

        // --- sugestões (com o seu campeão já escolhido, viram alternativas)
        val laneName = draft.myLane.label.uppercase()
        if (me != null) {
            section("SEU CAMPEÃO — $laneName", 6f)
            note("${me.name} · a build, as runas e os feitiços estão na aba Análise.", Ui.GOLD, 11.5f)
            section("ALTERNATIVAS PARA $laneName (se ainda der para trocar)")
        } else {
            section("MELHORES PICKS PARA VOCÊ — $laneName", 6f)
        }
        if (me == null && draft.allies.isEmpty() && draft.enemies.isEmpty())
            note("Por enquanto só pelo meta. Toque nos espaços acima conforme os campeões forem escolhidos e a lista se ajusta.")
        val picks = engine.suggestPicks(draft)
        if (picks.isEmpty()) note("Sem dados para esta rota.")
        for (s in picks) body.addView(pickRow(s), lp(MATCH, WRAP).apply { bottomMargin = dp(6f) })
        if (draft.isComplete()) note("Draft completo. Veja a aba Análise para o plano de jogo e as dicas de build.", Ui.GREEN)
    }

    private fun pickRow(s: Suggestion): View {
        val chosen = draft.myPick == s.champ.id
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7f), dp(6f), dp(8f), dp(6f))
            background = Ui.round(
                Ui.withAlpha(0xFFFFFF, 0.07f), dp(8f).toFloat(),
                if (chosen) Ui.GOLD else 0, if (chosen) dp(1.5f) else 0
            )
        }
        row.addView(Ui.icon(ctx, s.champ), lp(dp(38f), dp(38f)))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(ctx, s.champ.name, 13.5f, Ui.TEXT, true))
        top.addView(
            Ui.text(ctx, "T${s.stat.level}", 9.5f, Ui.BG, true).apply {
                setPadding(dp(4f), dp(1.5f), dp(4f), dp(1.5f))
                background = Ui.round(if (s.stat.level <= 1) Ui.GOLD else Ui.MUTED, dp(4f).toFloat())
            },
            lp(WRAP, WRAP).apply { leftMargin = dp(6f) }
        )
        top.addView(
            Ui.text(ctx, "WR ${pct(s.stat.wr)} · Pick ${pct(s.stat.pr)}", 10f, Ui.MUTED),
            lp(0, WRAP, 1f).apply { leftMargin = dp(8f) }
        )
        top.addView(Ui.text(ctx, s.score.toString(), 15f, Ui.GOLD, true))
        col.addView(top, lp(MATCH, WRAP))

        if (s.reasons.isNotEmpty()) {
            col.addView(
                Ui.text(ctx, s.reasons.take(4).joinToString("  •  "), 10.5f, Ui.BLUE).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.12f)
                },
                lp(MATCH, WRAP).apply { topMargin = dp(3f) }
            )
        }
        row.addView(col, lp(0, WRAP, 1f).apply { leftMargin = dp(8f) })
        row.setOnClickListener {
            setMyPick(if (chosen) null else s.champ.id)
            render()
        }
        return row
    }

    private fun setMyPick(id: String?) {
        val old = draft.myPick
        if (old != id) tracker.userRemoved(old)
        draft.myPick = id
        if (id != null) tracker.userPicked(id)
    }

    // -------------------------------------------------------------- ANÁLISE

    private fun dmgLine(t: TeamRead): String {
        val total = t.ad + t.ap
        if (total <= 0.0) return ""
        val ad = Math.round(t.ad * 100 / total).toInt()
        return "Dano: $ad% físico · ${100 - ad}% mágico"
    }

    /** Uma linha da build: posição na ordem de compra, ícone, nome e o motivo. */
    private fun buildRow(order: String, item: Item, reasons: List<String>, extra: String?) =
        iconRow(order, "item" + item.id, item.icon, item.name, item.name + (if (extra != null) "  →  $extra" else ""), reasons)

    /** Linha com rótulo à esquerda, ícone, título e motivos (itens, runas e feitiços). */
    private fun iconRow(order: String, key: String, url: String, label: String, title: String, reasons: List<String>, labelDp: Float = 40f) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7f), dp(6f), dp(8f), dp(6f))
            background = Ui.round(Ui.withAlpha(0xFFFFFF, 0.07f), dp(8f).toFloat())
        }
        row.addView(Ui.text(ctx, order, 11f, Ui.GOLD, true).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(dp(labelDp), WRAP))
        row.addView(Ui.squareIcon(ctx, key, url, label), lp(dp(34f), dp(34f)).apply { leftMargin = dp(2f) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(ctx, title, 12.5f, Ui.TEXT, true).apply {
            maxLines = 2
        })
        if (reasons.isNotEmpty()) {
            col.addView(
                Ui.text(ctx, reasons.joinToString("  •  "), 10.5f, Ui.BLUE).apply { maxLines = 2; setLineSpacing(0f, 1.1f) },
                lp(MATCH, WRAP).apply { topMargin = dp(2f) }
            )
        }
        row.addView(col, lp(0, WRAP, 1f).apply { leftMargin = dp(8f) })
        body.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(5f) })
    }

    private fun renderBuild() {
        val me = engine.champ(draft.myPick)
        section(if (me != null) "BUILD DE ${me.name.uppercase()} PARA ESTA PARTIDA" else "BUILD PARA ESTA PARTIDA", 4f)
        if (me == null) {
            note("Quando o seu campeão for confirmado (ou você tocar nele na aba Picks), a build calculada aparece aqui.")
            return
        }
        val r = Repo.buildFor(ctx, draft) { if (tab == 2) render() }
        if (r == null) {
            note(if (Repo.buildFailed(draft)) "Não foi possível calcular a build de ${me.name}: faltam dados dele na base." else "Calculando a build de ${me.name}…")
            return
        }
        r.boots?.let { buildRow("Botas", it.item, it.reasons, r.bootsUpgrade?.name) }
        r.items.forEachIndexed { i, p -> buildRow("${i + 1}º", p.item, p.reasons, null) }
        for (n in r.notes) note(n, Ui.withAlpha(Ui.MUTED, 0.85f), 10.5f)
        note("A ordem é a de compra. A build é refeita sozinha quando o draft muda.", Ui.withAlpha(Ui.MUTED, 0.7f), 9.5f)

        r.runes?.let { page ->
            section("RUNAS")
            fun runeRow(label: String, rp: RunePick) =
                iconRow(label, "rune" + rp.rune.id, rp.rune.icon, rp.rune.name, rp.rune.name, rp.reasons, 62f)
            runeRow("Essencial", page.keystone)
            note("Caminho principal: ${page.primaryPath}", Ui.MUTED, 10.5f)
            page.primary.forEachIndexed { i, rp -> runeRow("Linha ${i + 1}", rp) }
            note("Caminho secundário: ${page.secondaryPath}", Ui.MUTED, 10.5f)
            runeRow("Extra", page.secondary)
        }
        if (r.spells.isNotEmpty()) {
            section("FEITIÇOS")
            r.spells.forEachIndexed { i, sp ->
                iconRow(if (i == 0) "1º" else "2º", "spell" + sp.spell.id, sp.spell.icon, sp.spell.name, sp.spell.name, sp.reasons, 62f)
            }
            r.spellAlt?.let { alt ->
                note("Alternativa: ${alt.spell.name}" + (alt.reasons.firstOrNull()?.let { " — $it" } ?: ""), Ui.withAlpha(Ui.MUTED, 0.85f), 10.5f)
            }
        }
    }

    private fun renderAnalysis() {
        renderBuild()
        val a = engine.analyze(draft)
        if (a.ally == null && a.enemy == null) {
            section("ANÁLISE DO DRAFT")
            note("Adicione pelo menos 2 campeões de um time na aba Picks para ver o plano de jogo de cada lado.")
            return
        }
        a.ally?.let {
            section("SEU TIME")
            note(dmgLine(it), Ui.BLUE)
            for (p in it.plan) note("• $p", Ui.TEXT, 11.5f)
        }
        a.enemy?.let {
            section("TIME INIMIGO")
            note(dmgLine(it), Ui.RED)
            for (p in it.plan) note("• $p", Ui.TEXT, 11.5f)
        }
        if (a.compare.isNotEmpty()) {
            section("COMO GANHAR ESTE JOGO")
            for (c in a.compare) note("• $c", Ui.TEXT, 11.5f)
        }
        if (a.tips.isNotEmpty()) {
            section("AJUSTES DE BUILD CONTRA ESTE TIME")
            for (t in a.tips) note("• $t", Ui.TEXT, 11.5f)
        }
    }

    // ------------------------------------------------- seletor de campeões

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun openPicker(title: String, lane: Lane?, removable: Boolean, onPick: (String?) -> Unit) {
        var filterLane = lane
        var query = ""
        val taken = draft.taken()

        pickerHost.removeAllViews()
        val gap = dp(5f)

        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.text(ctx, title, 12.5f, Ui.GOLD, true), lp(0, WRAP, 1f))
        if (removable) {
            top.addView(Ui.chip(ctx, "Remover", false, 11.5f).apply {
                setTextColor(Ui.RED)
                setOnClickListener {
                    closePicker()
                    onPick(null)
                }
            }, lp(WRAP, WRAP).apply { rightMargin = gap })
        }
        top.addView(Ui.chip(ctx, "Cancelar", false, 11.5f).apply { setOnClickListener { closePicker() } }, lp(WRAP, WRAP))
        pickerHost.addView(top, lp(MATCH, WRAP).apply { bottomMargin = dp(6f) })

        val adapter = ChampAdapter(taken)
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        fun refresh() {
            val all = engine.allChamps()
            val q = norm(query)
            adapter.items = if (q.isNotEmpty()) {
                all.filter { norm(it.name).contains(q) || norm(it.key).contains(q) }.sortedBy { it.name }
            } else {
                val fl = filterLane
                if (fl == null) {
                    all.sortedBy { it.name }
                } else {
                    val st = engine.laneStats(fl)
                    all.filter { st.containsKey(it.id) || fl in it.lanes }.sortedByDescending { st[it.id]?.pr ?: 0.0 }
                }
            }
            adapter.notifyDataSetChanged()

            chipRow.removeAllViews()
            val options: List<Lane?> = listOf<Lane?>(null) + Lane.entries
            for (o in options) {
                val c = Ui.chip(ctx, o?.short ?: "Todas", o == filterLane, 11f)
                c.setOnClickListener {
                    filterLane = o
                    refresh()
                }
                chipRow.addView(c, lp(WRAP, WRAP).apply { rightMargin = gap })
            }
        }

        val search = EditText(ctx).apply {
            hint = "Buscar…"
            setHintTextColor(Ui.withAlpha(Ui.MUTED, 0.7f))
            setTextColor(Ui.TEXT)
            textSize = 12f
            setSingleLine()
            isLongClickable = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = Ui.round(Ui.withAlpha(0xFFFFFF, 0.10f), dp(6f).toFloat())
            setOnTouchListener { _, e ->
                // a janela só aceita teclado enquanto a busca está em uso
                if (e.actionMasked == MotionEvent.ACTION_DOWN) host.setPanelFocusable(true)
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    refresh()
                }
            })
        }
        searchBox = search

        val filters = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        filters.addView(hs, lp(0, WRAP, 1f))
        filters.addView(search, lp(dp(110f), WRAP).apply { leftMargin = gap })
        pickerHost.addView(filters, lp(MATCH, WRAP).apply { bottomMargin = dp(6f) })

        val grid = GridView(ctx).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(56f)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(6f)
            isVerticalScrollBarEnabled = false
            selector = Ui.round(Ui.withAlpha(Ui.GOLD, 0.2f), dp(6f).toFloat())
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ ->
                val c = adapter.items[pos]
                if (c.id !in taken) {
                    closePicker()
                    onPick(c.id)
                }
            }
        }
        pickerHost.addView(grid, lp(MATCH, 0, 1f))

        refresh()
        scroll.visibility = View.GONE
        pickerHost.visibility = View.VISIBLE
    }

    private fun closePicker() {
        if (pickerHost.visibility != View.VISIBLE) return
        searchBox?.let {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
        searchBox = null
        host.setPanelFocusable(false)
        pickerHost.visibility = View.GONE
        pickerHost.removeAllViews()
        scroll.visibility = View.VISIBLE
    }

    private inner class ChampAdapter(val taken: Set<String>) : BaseAdapter() {
        var items: List<Champion> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = items[position]
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2f), 0, dp(2f))
            }
            cell.addView(Ui.icon(ctx, c), lp(dp(42f), dp(42f)))
            cell.addView(
                Ui.text(ctx, c.name, 9f, Ui.TEXT).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                lp(MATCH, WRAP).apply { topMargin = dp(2f) }
            )
            cell.alpha = if (c.id in taken) 0.28f else 1f
            return cell
        }
    }

    companion object {
        /**
         * Opacidade do fundo da janela: o botão ◐ alterna entre estas. Só o fundo fica transparente; o
         * texto e os ícones da janela são sempre 100% nítidos. A do meio é a padrão: com 0,86 o texto de
         * um fundo claro (a tela inicial do app) ainda atravessava; com 0,94 sobra só um vulto.
         */
        private val OPACITY = floatArrayOf(0.62f, 0.94f, 1f)

        /** Aba aberta; guardada aqui para sobreviver quando a janela é recriada (girar a tela). */
        var tab = 0
    }
}
