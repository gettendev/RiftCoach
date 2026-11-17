package com.riftcoach.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.riftcoach.app.Ui.MATCH
import com.riftcoach.app.Ui.WRAP
import com.riftcoach.app.Ui.lp

/** Tela para conferir a base de dados: campeões, itens, runas e feitiços, com todos os números. */
@SuppressLint("SetTextI18n")
class DbActivity : Activity() {

    private class Row(val title: String, val sub: String, val open: () -> Unit)

    private lateinit var root: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var detailScroll: ScrollView
    private lateinit var detail: LinearLayout
    private lateinit var status: TextView
    private val adapter = RowAdapter()
    private var tab = 0
    private var db: GameDb? = null

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.ensureLoaded(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left + dp(16f), b.top + dp(12f), b.right + dp(16f), b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(dp(16f), insets.systemWindowInsetTop + dp(12f), dp(16f), insets.systemWindowInsetBottom)
            }
            insets
        }

        root.addView(Ui.text(this, "Base de dados", 22f, Ui.GOLD, true))
        status = Ui.text(this, "Carregando…", 12f, Ui.MUTED)
        root.addView(status, lp(MATCH, WRAP).apply { topMargin = dp(2f); bottomMargin = dp(10f) })

        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabsRow, lp(MATCH, WRAP))

        search = EditText(this).apply {
            hint = "Buscar pelo nome…"
            setHintTextColor(Ui.withAlpha(Ui.MUTED, 0.7f))
            setTextColor(Ui.TEXT)
            textSize = 14f
            setSingleLine()
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            background = Ui.round(Ui.CARD, dp(8f).toFloat())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = fill()
            })
        }
        root.addView(search, lp(MATCH, WRAP).apply { topMargin = dp(10f); bottomMargin = dp(8f) })

        list = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
            this.adapter = this@DbActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> this@DbActivity.adapter.rows[pos].open() }
        }
        root.addView(list, lp(MATCH, 0, 1f))

        detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(24f))
        }
        detailScroll = ScrollView(this).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            addView(detail)
        }
        root.addView(detailScroll, lp(MATCH, 0, 1f))
        setContentView(root)

        Repo.loadDb(this) { loaded, err ->
            db = loaded
            if (loaded == null) {
                status.text = "Não foi possível abrir a base: $err"
            } else {
                status.text = "Versão ${loaded.version} do jogo · fonte oficial: lolm.qq.com · " +
                    "${loaded.heroes.size} campeões, ${loaded.items.size} itens, ${loaded.runes.size} runas, ${loaded.spells.size} feitiços"
                buildTabs()
                fill()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (detailScroll.visibility == View.VISIBLE) closeDetail() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun buildTabs() {
        tabsRow.removeAllViews()
        val names = listOf("Campeões", "Itens", "Runas", "Feitiços")
        for (i in names.indices) {
            val c = Ui.chip(this, names[i], i == tab, 13f)
            c.setOnClickListener {
                tab = i
                closeDetail()
                buildTabs()
                fill()
            }
            tabsRow.addView(c, lp(WRAP, WRAP).apply { rightMargin = dp(6f) })
        }
    }

    private fun norm(s: String) = NameIndex.norm(s)

    private fun fill() {
        val d = db ?: return
        val q = norm(search.text.toString())
        val rows = ArrayList<Row>()
        when (tab) {
            0 -> {
                val engine = Repo.engine
                val champs = engine?.allChamps()?.sortedBy { it.name } ?: emptyList()
                for (c in champs) {
                    val h = d.heroes[c.id] ?: continue
                    if (q.isNotEmpty() && !norm(c.name).contains(q)) continue
                    rows.add(Row(c.name, "Vida ${GameDb.num(h.hp)} · AD ${GameDb.num(h.ad)} · Armadura ${GameDb.num(h.armor)} · RM ${GameDb.num(h.mr)}") { showHero(c, h) })
                }
            }
            1 -> for (it in d.items.sortedWith(compareBy<Item> { -it.tier }.thenBy { it.name })) {
                if (q.isNotEmpty() && !norm(it.name).contains(q)) continue
                rows.add(Row(it.name, "${it.price} de ouro · ${tierName(it.tier)} · " + it.stats.joinToString(", ") { s -> "${s.second} ${s.first}" }) { showItem(d, it) })
            }
            2 -> for (r in d.runes.sortedWith(compareBy<Rune> { it.legacy }.thenBy { !it.keystone }.thenBy { it.tree }.thenBy { it.slot }.thenBy { it.name })) {
                if (q.isNotEmpty() && !norm(r.name).contains(q)) continue
                val sub = when {
                    r.keystone -> "Runa essencial"
                    r.legacy -> "Fora do jogo atual"
                    else -> "${r.tree} · linha ${r.slot + 1}"
                }
                rows.add(Row(r.name, sub) { showText(r.name, sub, r.textCn, Repo.runeData?.info?.get(r.id)?.pt) })
            }
            else -> for (s in d.spells.sortedBy { !it.rift }) {
                if (q.isNotEmpty() && !norm(s.name).contains(q)) continue
                val sub = if (s.rift) "Feitiço de invocador" else "Só no Abismo Uivante"
                rows.add(Row(s.name, sub) { showText(s.name, sub, s.textCn, Repo.runeData?.spellPt?.get(s.id)) })
            }
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
    }

    private fun tierName(t: Int) = when (t) {
        1 -> "básico"
        2 -> "intermediário"
        else -> "completo"
    }

    // ---------------------------------------------------------------- detalhe

    private fun openDetail(title: String, sub: String) {
        detail.removeAllViews()
        val back = Ui.chip(this, "← Voltar à lista", false, 13f)
        back.setOnClickListener { closeDetail() }
        detail.addView(back, lp(WRAP, WRAP).apply { bottomMargin = dp(12f) })
        detail.addView(Ui.text(this, title, 22f, Ui.TEXT, true))
        if (sub.isNotEmpty()) detail.addView(Ui.text(this, sub, 13f, Ui.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(3f) })
        list.visibility = View.GONE
        search.visibility = View.GONE
        detailScroll.visibility = View.VISIBLE
        detailScroll.scrollTo(0, 0)
    }

    private fun closeDetail() {
        detailScroll.visibility = View.GONE
        list.visibility = View.VISIBLE
        search.visibility = View.VISIBLE
    }

    private fun section(title: String) {
        detail.addView(Ui.text(this, title, 12f, Ui.GOLD, true).apply { letterSpacing = 0.05f }, lp(MATCH, WRAP).apply { topMargin = dp(18f); bottomMargin = dp(6f) })
    }

    private fun line(label: String, value: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.text(this, label, 13.5f, Ui.MUTED), lp(0, WRAP, 1f))
        row.addView(Ui.text(this, value, 13.5f, Ui.TEXT, true))
        detail.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(5f) })
    }

    private fun para(text: String, color: Int = Ui.TEXT, sp: Float = 13.5f) {
        detail.addView(Ui.text(this, text, sp, color).apply { setLineSpacing(0f, 1.2f) }, lp(MATCH, WRAP).apply { bottomMargin = dp(5f) })
    }

    private fun hasChinese(s: String) = s.any { it.code in 0x4E00..0x9FFF }

    private fun showHero(c: Champion, h: HeroDetail) {
        openDetail(c.name, "Números oficiais do campeão")
        section("ATRIBUTOS  (nível 1 → nível 15)")
        fun grow(label: String, base: Double, per: Double) =
            line(label, GameDb.num(base) + "  →  " + GameDb.num(Math.round(h.at(base, per, 15) * 10) / 10.0) + "   (+" + GameDb.num(per) + " por nível)")
        grow("Vida", h.hp, h.hpPer)
        if (h.mp > 0) grow("Mana", h.mp, h.mpPer)
        grow("Dano de ataque (AD)", h.ad, h.adPer)
        grow("Armadura", h.armor, h.armorPer)
        grow("Resistência mágica", h.mr, h.mrPer)
        grow("Regeneração de vida", h.hpRegen, h.hpRegenPer)
        line("Velocidade de movimento", GameDb.num(h.moveSpeed))
        line("Dano crítico", GameDb.num(h.critDamage) + "%")
        line("Velocidade de ataque (índice da base)", GameDb.num(h.attackSpeedIndex) + "  (+" + GameDb.num(h.attackSpeedPer) + " por nível)")
        para("O índice de velocidade de ataque é o número bruto da base oficial; ainda não sei como ele vira o valor que o jogo mostra.", Ui.withAlpha(Ui.MUTED, 0.8f), 11.5f)

        for (s in h.skills) {
            section(s.slotLabel.uppercase())
            if (s.effects.isEmpty() && s.table.isEmpty()) para("Sem números extraídos para esta habilidade.", Ui.MUTED)
            for (e in s.effects) {
                if (e.raw != null) para("${e.kind}: fórmula ainda não interpretada (${e.raw})", Ui.MUTED, 12.5f)
                else para("• " + e.describe(), Ui.BLUE)
            }
            for ((label, value) in s.table) line(if (hasChinese(label)) "Outro valor" else label, value)
        }
        para("Um * depois do atributo significa que o texto oficial não diz de qual atributo é o multiplicador, e o app deduziu.", Ui.withAlpha(Ui.MUTED, 0.8f), 11.5f)
    }

    private fun showItem(d: GameDb, it: Item) {
        openDetail(it.name, "${it.price} de ouro · ${tierName(it.tier)} · ${it.type}")
        if (it.nameUnsure) para("O nome em português deste item ainda precisa ser conferido com o jogo.", Ui.RED, 12.5f)
        section("ATRIBUTOS")
        if (it.stats.isEmpty()) para("Este item não dá atributos fixos.", Ui.MUTED)
        for ((label, value) in it.stats) line(label, "+$value")
        if (it.from.isNotEmpty()) {
            section("FEITO DE")
            para(it.from.joinToString("  +  ") { id -> d.item(id)?.name ?: id })
        }
        val model = Repo.itemModels?.get(it.id)
        if (model != null && model.pt.isNotBlank()) {
            section("PASSIVA")
            para(model.pt)
            model.src?.let { s -> para("Fonte de número que não está na base oficial — $s", Ui.withAlpha(Ui.MUTED, 0.8f), 11.5f) }
        } else if (it.textCn.isNotBlank()) {
            section("TEXTO OFICIAL (EM CHINÊS)")
            para(it.textCn, Ui.MUTED, 12.5f)
            para("Este item é um componente: a passiva dele ainda não foi traduzida. Os números dentro do texto são os do jogo.", Ui.withAlpha(Ui.MUTED, 0.8f), 11.5f)
        }
    }

    private fun showText(title: String, sub: String, textCn: String, pt: String?) {
        openDetail(title, sub)
        if (title.endsWith("(?)")) para("O nome em português ainda precisa ser conferido com o jogo.", Ui.RED, 12.5f)
        if (!pt.isNullOrBlank()) {
            section("EFEITO")
            para(pt)
        } else {
            section("TEXTO OFICIAL (EM CHINÊS)")
            para(textCn, Ui.MUTED, 12.5f)
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = rows[position]
            val box = LinearLayout(this@DbActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = Ui.round(Ui.CARD, dp(8f).toFloat())
            }
            box.addView(Ui.text(this@DbActivity, r.title, 15f, Ui.TEXT, true))
            box.addView(Ui.text(this@DbActivity, r.sub, 12f, Ui.MUTED).apply { maxLines = 2 }, lp(MATCH, WRAP).apply { topMargin = dp(3f) })
            // margem entre as linhas
            return LinearLayout(this@DbActivity).apply {
                setPadding(0, 0, 0, dp(6f))
                addView(box, lp(MATCH, WRAP))
            }
        }
    }
}
