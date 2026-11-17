package com.riftcoach.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

/** Converte o resultado do leitor de texto em linhas com posição em fração da tela. */
object FrameOcr {
    val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * `bmp` é o recorte analisado; (offX, offY) é onde ele começa dentro do quadro inteiro,
     * e frameW x frameH é o tamanho do quadro inteiro — tudo na mesma escala de pixels.
     */
    fun lines(text: Text, bmp: Bitmap, offX: Int, offY: Int, frameW: Int, frameH: Int): List<TextLine> {
        val out = ArrayList<TextLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val b = line.boundingBox ?: continue
                val x0 = (b.left + offX) / frameW.toFloat()
                val x1 = (b.right + offX) / frameW.toFloat()
                val y0 = (b.top + offY) / frameH.toFloat()
                val y1 = (b.bottom + offY) / frameH.toFloat()
                // só a coluna dos aliados precisa da cor (a linha dourada é a do próprio jogador)
                val gold = (x0 + x1) / 2 < 0.30f && isGold(bmp, b)
                out.add(TextLine(line.text, x0, y0, x1, y1, gold))
            }
        }
        return out
    }

    /** O texto dentro do retângulo é dourado? Olha só os pixels mais claros (as letras). */
    private fun isGold(bmp: Bitmap, box: Rect): Boolean {
        val l = max(0, box.left)
        val t = max(0, box.top)
        val r = min(bmp.width, box.right)
        val b = min(bmp.height, box.bottom)
        val w = r - l
        val h = b - t
        if (w < 4 || h < 4) return false
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, l, t, w, h)
        var maxL = 0
        for (p in px) {
            val lum = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
            if (lum > maxL) maxL = lum
        }
        if (maxL < 110) return false
        val thr = maxL * 0.72f
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        for (p in px) {
            val cr = (p shr 16) and 0xFF
            val cg = (p shr 8) and 0xFF
            val cb = p and 0xFF
            if ((cr * 3 + cg * 6 + cb) / 10 >= thr) {
                sr += cr
                sg += cg
                sb += cb
                n++
            }
        }
        if (n == 0) return false
        val ar = sr / n
        val ag = sg / n
        val ab = sb / n
        return ar - ab > 45 && ar >= ag && ag > ab
    }

    fun describe(read: ScreenRead, engine: Engine): String {
        fun name(id: String?) = engine.champ(id)?.name ?: "-"
        val a = read.allies.joinToString("; ") {
            (if (it.gold) "*" else "") + (it.lane?.short ?: name(it.champ)) + "/" + (it.nick ?: "?")
        }
        val e = read.enemies.joinToString("; ") { "J${it.slot + 1}=" + name(it.champ) }
        val b = read.bans.joinToString(",") { name(it) }
        if (read.phase == Phase.PREP) {
            return "fase=PREP time=[${read.loadTop.joinToString(",") { name(it) }}]"
        }
        if (read.phase == Phase.LOADING) {
            return "fase=LOADING cima=[${read.loadTop.joinToString(",") { name(it) }}] baixo=[${read.loadBottom.joinToString(",") { name(it) }}]"
        }
        return "fase=${read.phase} aliados=[$a] inimigos=[$e] bans=[$b]"
    }
}

/**
 * Captura a tela e lê o draft.
 * Fora do draft só espia o título no topo a cada poucos segundos, para não pesar durante a partida;
 * quando reconhece a seleção de campeões, passa a ler a tela inteira cerca de uma vez por segundo.
 */
class ScreenReader(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val host: Host
) {
    interface Host {
        /** Retângulo da janela aberta (em pixels de tela), ou null se fechada. */
        fun panelRect(): Rect?
        fun bubbleRect(): Rect?
        fun onRead(changed: Boolean)
        fun onReaderStopped()
    }

    private val thread = HandlerThread("rc-reader").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var held: Image? = null
    private var fresh = false
    private var w = 0
    private var h = 0

    @Volatile
    private var stopped = false

    @Volatile
    private var activeUntil = 0L

    @Volatile
    private var forceOnce = false

    /** O nosso próprio desenho aparece na captura? (não aparece quando o usuário compartilha só o app do jogo) */
    private var overlayInCapture = true
    private var ownTextMisses = 0

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            main.post { host.onReaderStopped() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            bg.post { resizeTo(width, height) }
        }
    }

    fun start() {
        val p = screenSize()
        w = p.x
        h = p.y
        projection.registerCallback(callback, bg)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        display = projection.createVirtualDisplay(
            "riftcoach", w, h, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, bg
        )
        Repo.readerState = Repo.READER_WAITING
        bg.postDelayed(tick, 600)
    }

    /** Pede uma leitura completa agora (ex.: o usuário abriu a janela). */
    fun poke() {
        activeUntil = SystemClock.elapsedRealtime() + 6000
        forceOnce = true
        bg.removeCallbacks(tick)
        bg.postDelayed(tick, 150)
    }

    fun onScreenChanged() {
        bg.post {
            val p = screenSize()
            resizeTo(p.x, p.y)
        }
    }

    fun stop() {
        runCatching { projection.stop() }
        release()
    }

    private fun release() {
        if (stopped) return
        stopped = true
        Repo.readerState = Repo.READER_OFF
        bg.post {
            runCatching { display?.release() }
            runCatching { held?.close() }
            runCatching { reader?.close() }
            display = null
            held = null
            reader = null
            thread.quitSafely()
        }
    }

    private fun screenSize(): Point {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun resizeTo(nw: Int, nh: Int) {
        if (stopped || nw <= 0 || nh <= 0 || (nw == w && nh == h)) return
        val d = display ?: return
        val old = reader
        val nr = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 3)
        runCatching { held?.close() }
        held = null
        fresh = false
        w = nw
        h = nh
        d.resize(nw, nh, ctx.resources.displayMetrics.densityDpi)
        d.surface = nr.surface
        reader = nr
        runCatching { old?.close() }
    }

    private val tick = Runnable { runTick() }

    private fun schedule(delay: Long) {
        if (!stopped) bg.postDelayed(tick, delay)
    }

    private fun runTick() {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        val full = now < activeUntil
        val idleDelay = if (full) FULL_INTERVAL_MS else IDLE_INTERVAL_MS
        try {
            val img = reader?.acquireLatestImage()
            if (img != null) {
                runCatching { held?.close() }
                held = img
                fresh = true
            }
            val cur = held
            // tela em pé (fora do jogo) ou nada novo para ler: só espera
            if (cur == null || cur.height > cur.width || (!fresh && !forceOnce)) {
                updateState(now)
                schedule(idleDelay)
                return
            }
            fresh = false
            forceOnce = false

            val frameW: Int
            val frameH: Int
            val offX: Int
            val region: Bitmap
            if (full) {
                val raw = grab(cur, 0, cur.height)
                val scale = min(1f, MAX_OCR_WIDTH / raw.width.toFloat())
                region = if (scale < 1f) {
                    Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true)
                        .also { if (it !== raw) raw.recycle() }
                } else raw
                frameW = region.width
                frameH = region.height
                offX = 0
            } else {
                // só a faixa do título, no topo e ao centro
                val strip = grab(cur, 0, (cur.height * 0.10f).toInt())
                offX = (strip.width * 0.30f).toInt()
                region = Bitmap.createBitmap(strip, offX, 0, (strip.width * 0.40f).toInt(), strip.height)
                    .also { if (it !== strip) strip.recycle() }
                frameW = cur.width
                frameH = cur.height
            }

            FrameOcr.recognizer.process(InputImage.fromBitmap(region, 0))
                .addOnSuccessListener { text ->
                    runCatching { handle(text, region, offX, frameW, frameH, full) }
                        .onFailure { Log.w(TAG, "falha ao interpretar a tela", it) }
                }
                .addOnFailureListener { Log.w(TAG, "falha no leitor de texto", it) }
                .addOnCompleteListener {
                    region.recycle()
                    val t = SystemClock.elapsedRealtime()
                    schedule(if (t < activeUntil) (if (full) FULL_INTERVAL_MS else 150L) else IDLE_INTERVAL_MS)
                }
        } catch (e: Exception) {
            Log.w(TAG, "falha na captura", e)
            schedule(idleDelay)
        }
    }

    /** Copia as linhas [top, top+rows) do quadro para um Bitmap. */
    private fun grab(img: Image, top: Int, rows: Int): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val widthPx = rowStride / pixelStride
        val buf = plane.buffer.duplicate()
        val n = min(rows, img.height - top)
        buf.position(top * rowStride)
        // a última linha do buffer pode vir sem o preenchimento final
        val needed = widthPx * 4 * n
        val bmp = Bitmap.createBitmap(widthPx, n, Bitmap.Config.ARGB_8888)
        if (buf.remaining() >= needed) {
            buf.limit(buf.position() + needed)
            bmp.copyPixelsFromBuffer(buf)
        } else {
            val tmp = java.nio.ByteBuffer.allocate(needed)
            tmp.put(buf)
            tmp.rewind()
            bmp.copyPixelsFromBuffer(tmp)
        }
        return if (widthPx == img.width) bmp
        else Bitmap.createBitmap(bmp, 0, 0, img.width, n).also { bmp.recycle() }
    }

    private fun updateState(now: Long) {
        if (stopped) return
        Repo.readerState = if (now < activeUntil) Repo.READER_READING else Repo.READER_WAITING
    }

    // roda na thread principal
    private fun handle(text: Text, region: Bitmap, offX: Int, frameW: Int, frameH: Int, full: Boolean) {
        if (stopped) return
        val parser = Repo.parser ?: return
        var lines = FrameOcr.lines(text, region, offX, 0, frameW, frameH)
        val now = SystemClock.elapsedRealtime()

        if (full) lines = dropOwnWindows(lines)
        val read = parser.parse(lines)

        if (!full) {
            if (read.phase != Phase.NONE) {
                activeUntil = now + ACTIVE_HOLD_MS
                forceOnce = true // lê a tela inteira mesmo que a imagem não tenha mudado
                Repo.readerState = Repo.READER_READING
                host.onRead(false)
            } else updateState(now)
            return
        }

        if (read.isDraft) activeUntil = now + ACTIVE_HOLD_MS
        updateState(now)
        val changed = Repo.tracker.apply(read, System.currentTimeMillis())
        if (read.isDraft) Repo.engine?.let { e ->
            Log.d(TAG, FrameOcr.describe(read, e) + (if (changed) "  [mudou]" else ""))
            if (changed) {
                val d = Repo.draft
                fun n(id: String?) = e.champ(id)?.name ?: "-"
                Log.d(
                    TAG, "DRAFT rota=${d.myLane.short} eu=${n(d.myPick)} " +
                        "aliados=${d.allies.entries.joinToString(",") { it.key.short + "=" + n(it.value) }} " +
                        "inimigos=${d.enemies.joinToString(",") { n(it) }} bans=${d.bans.joinToString(",") { n(it) }}"
                )
            }
        }
        host.onRead(changed)
    }

    /** Tira da leitura os textos da nossa própria janela e da bolha, quando elas aparecem na captura. */
    private fun dropOwnWindows(lines: List<TextLine>): List<TextLine> {
        val panel = host.panelRect()
        val bubble = host.bubbleRect()
        val sw = w.toFloat()
        val sh = h.toFloat()
        fun inside(l: TextLine, r: Rect) =
            l.cx * sw >= r.left && l.cx * sw <= r.right && l.cy * sh >= r.top && l.cy * sh <= r.bottom

        if (panel != null) {
            val own = lines.any { inside(it, panel) && OWN_TEXT.containsMatchIn(it.text) }
            if (own) {
                overlayInCapture = true
                ownTextMisses = 0
            } else if (++ownTextMisses >= 3) {
                overlayInCapture = false
            }
        }
        if (!overlayInCapture) return lines
        return lines.filter { l -> !(panel != null && inside(l, panel)) && !(bubble != null && inside(l, bubble)) }
    }

    companion object {
        const val TAG = "RiftCoach"
        private const val MAX_OCR_WIDTH = 2048f
        private const val FULL_INTERVAL_MS = 600L
        private const val IDLE_INTERVAL_MS = 2500L
        private const val ACTIVE_HOLD_MS = 8000L
        private val OWN_TEXT = Regex("Bans|Picks|An[áa]lise|Novo")
    }
}
