package com.riftcoach.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object Ui {
    const val BG = 0xFF0A1428.toInt()
    const val CARD = 0xFF13233F.toInt()
    const val GOLD = 0xFFC8AA6E.toInt()
    const val TEXT = 0xFFF0E6D2.toInt()
    const val MUTED = 0xFFA7B0C0.toInt()
    const val BLUE = 0xFF5BC0EB.toInt()
    const val RED = 0xFFE8556B.toInt()
    const val GREEN = 0xFF58D68D.toInt()

    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun withAlpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    fun text(ctx: Context, s: CharSequence, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    fun round(color: Int, radius: Float, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun oval(color: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    /** Botão/etiqueta arredondada. */
    fun chip(ctx: Context, s: String, selected: Boolean, sp: Float = 12f): TextView =
        text(ctx, s, sp, if (selected) BG else TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 9f), dp(ctx, 5f), dp(ctx, 9f), dp(ctx, 5f))
            background = round(if (selected) GOLD else withAlpha(0xFFFFFF, 0.10f), dp(ctx, 6f).toFloat())
        }

    private val OVAL_OUTLINE = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    /** Ícone quadrado, de cantos arredondados (itens, runas, feitiços). `key` identifica o ícone no cache. */
    fun squareIcon(ctx: Context, key: String, url: String, label: String): ImageView =
        ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            val r = dp(ctx, 6f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            IconLoader.loadUrl(ctx, key, url, label, this)
        }

    /** Ícone redondo do campeão. `ring` desenha um anel colorido em volta. */
    fun icon(ctx: Context, champ: Champion, ring: Int = 0): ImageView =
        ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = OVAL_OUTLINE
            if (ring != 0) {
                val p = dp(ctx, 2f)
                setPadding(p, p, p, p)
                background = oval(ring)
            }
            IconLoader.load(ctx, champ, this)
        }
}

/** Círculo com as iniciais, usado enquanto o ícone não carregou (ou sem internet). */
class InitialsDrawable(name: String) : Drawable() {
    private val label = name.filter { it.isLetter() }.take(2).replaceFirstChar { it.uppercase() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val hues = intArrayOf(0xFF2E5C8A.toInt(), 0xFF7A4E9E.toInt(), 0xFF2F7F6F.toInt(), 0xFF9E5A3C.toInt(), 0xFF8A2E4E.toInt())
        color = hues[(name.hashCode() and 0x7FFFFFFF) % hues.size]
    }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, bg)
        fg.textSize = r * 0.85f
        canvas.drawText(label, b.exactCenterX(), b.exactCenterY() - (fg.descent() + fg.ascent()) / 2, fg)
    }

    override fun setAlpha(alpha: Int) {
        bg.alpha = alpha
        fg.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bg.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Baixa os ícones dos campeões uma vez e guarda no celular.
 * O seletor de campeões pede ~140 ícones de uma vez (e pede de novo a cada rolagem ou letra digitada):
 * cada ícone é baixado uma vez só, mesmo que vários quadrinhos o peçam, e o pedido mais recente vai
 * na frente da fila, para os ícones que estão na tela chegarem primeiro.
 */
object IconLoader {
    private val mem = LruCache<String, Bitmap>(260)

    /** fila "o último pedido sai primeiro" */
    private val queue = object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }
    private val exec = ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS, queue)
    private val main = Handler(Looper.getMainLooper())

    /** ícones baixando agora -> quadrinhos esperando por eles (só a thread principal mexe) */
    private val waiting = HashMap<String, ArrayList<ImageView>>()

    /** tarefa de cada ícone pedido, para passar para a frente da fila se ele for pedido de novo (só a thread principal mexe) */
    private val queued = HashMap<String, Runnable>()

    /** quando cada ícone falhou por último (sem internet, servidor fora): espera um pouco antes de tentar de novo */
    private val failedAt = HashMap<String, Long>()
    private const val RETRY_MS = 20_000L

    fun load(ctx: Context, champ: Champion, iv: ImageView) = loadUrl(ctx, champ.id, champ.avatar, champ.name, iv)

    /** `key` identifica o ícone no cache; `label` vira as iniciais enquanto ele não carrega. */
    fun loadUrl(ctx: Context, key: String, url: String, label: String, iv: ImageView) {
        iv.tag = key
        val cached = mem.get(key)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(InitialsDrawable(label))
        if (url.isBlank()) return
        waiting[key]?.let {
            if (iv !in it) it.add(iv) // já está baixando ou na fila: só espera junto (sem repetir o mesmo quadrinho)
            // pedido de novo (o quadrinho voltou para a tela): se ainda está na fila, passa para a frente
            queued[key]?.let { r -> if (queue.remove(r)) queue.offerFirst(r) }
            return
        }
        val dir = File(ctx.applicationContext.filesDir, "icons")
        val f = File(dir, "$key.png")
        val failed = failedAt[key]
        if (failed != null && SystemClock.uptimeMillis() - failed < RETRY_MS && !f.exists()) return
        waiting[key] = arrayListOf(iv)
        val task = Runnable {
            var bmp: Bitmap? = null
            try {
                bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                if (bmp == null) bmp = download(url, dir, key, f)
            } catch (_: Throwable) {
                // sem internet (ou sem memória para abrir a imagem): fica com as iniciais
            } finally {
                // sempre avisa a thread principal, senão o ícone ficaria "baixando" para sempre
                val result = bmp
                if (result != null) mem.put(key, result)
                main.post {
                    queued.remove(key)
                    val views = waiting.remove(key) ?: return@post
                    if (result == null) {
                        failedAt[key] = SystemClock.uptimeMillis()
                        return@post
                    }
                    failedAt.remove(key)
                    for (v in views) if (v.tag == key) v.setImageBitmap(result)
                }
            }
        }
        queued[key] = task
        exec.execute(task)
    }

    private fun download(url: String, dir: File, key: String, f: File): Bitmap? {
        dir.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytes() }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val tmp = File(dir, "$key.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            return bmp
        } finally {
            c.disconnect()
        }
    }
}
