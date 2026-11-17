package com.riftcoach.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

/** Mantém a bolha flutuante, a janela por cima do jogo e a leitura automática da tela. */
class OverlayService : Service(), Panel.Host, ScreenReader.Host {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: Panel? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var reader: ScreenReader? = null
    private var lastPhase = Phase.NONE
    private var revealHandled = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground(false)
        try {
            Repo.ensureLoaded(this)
            addBubble()
            running = true
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir a bolha: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        Repo.autoUpdate(this) { err -> if (err == null) panel?.render() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null && intent.hasExtra(EXTRA_DATA) && reader == null && running) {
            startReader(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        reader?.stop()
        reader = null
        Repo.readerState = Repo.READER_OFF
        removePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reader?.onScreenChanged()
        // girou a tela: recria a janela no tamanho novo
        if (panel != null) {
            removePanel()
            showPanel()
        }
    }

    // ---------------------------------------------------------- notificação

    private fun startAsForeground(capturing: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bolha flutuante", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("RiftCoach ativo")
            .setContentText(
                if (capturing) "Lendo a tela durante a seleção de campeões."
                else "Toque na bolha para abrir a janela de draft."
            )
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), "Desligar", stop).build()
            )
            .build()
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (capturing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(1, n, type)
            }
            Build.VERSION.SDK_INT >= 29 && capturing ->
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(1, n)
        }
    }

    // ------------------------------------------------------ leitura da tela

    private fun startReader(intent: Intent) {
        try {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) return
            // o Android exige o serviço já marcado como "captura de tela" antes de pegar a captura
            startAsForeground(true)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = mpm.getMediaProjection(code, data) ?: return
            val r = ScreenReader(this, projection, this)
            r.start()
            reader = r
        } catch (e: Exception) {
            Log.w(ScreenReader.TAG, "não foi possível iniciar a leitura da tela", e)
            Repo.readerState = Repo.READER_OFF
            Toast.makeText(this, "Leitura da tela indisponível: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun screenRect(v: View?): Rect? {
        if (v == null || !v.isAttachedToWindow) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    override fun panelRect(): Rect? = screenRect(panel?.root)

    override fun bubbleRect(): Rect? = screenRect(bubble)

    override fun onRead(changed: Boolean) {
        // com o seu campeão confirmado, a build (com runas e feitiços) já começa a ser calculada,
        // mesmo com a janela fechada: quando você abrir, ela está pronta
        if (changed && Repo.draft.myPick != null) {
            Repo.buildFor(this, Repo.draft) { if (Panel.tab == 2) panel?.render() }
        }
        val phase = Repo.tracker.phase
        // Na revelação dos bans os nomes aparecem bem no centro, embaixo da janela.
        // A janela sai da frente por alguns segundos para o app conseguir ler, e volta sozinha.
        if (phase == Phase.BAN_REVEAL && !revealHandled) {
            revealHandled = true
            if (panel != null) {
                removePanel()
                ui.postDelayed({ if (running && panel == null) showPanel() }, REVEAL_HIDE_MS)
            }
        } else if (phase != Phase.BAN_REVEAL) {
            revealHandled = false
        }
        if (phase != lastPhase) {
            lastPhase = phase
            // acompanha a fase do jogo: banimento -> aba Bans, escolha -> aba Picks, preparação -> Análise
            when (phase) {
                Phase.BAN, Phase.BAN_REVEAL -> Panel.tab = 0
                Phase.PICK -> Panel.tab = 1
                Phase.PREP, Phase.LOADING -> Panel.tab = 2
                Phase.NONE -> {}
            }
            panel?.render()
        } else if (changed) {
            panel?.render()
        } else {
            panel?.refreshStatus()
        }
    }

    override fun onReaderStopped() {
        reader = null
        Repo.readerState = Repo.READER_OFF
        panel?.refreshStatus()
        Toast.makeText(this, "RiftCoach: a leitura da tela parou. Desligue e ligue a bolha para voltar a ler.", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- bolha

    private fun dp(v: Float) = Ui.dp(this, v)

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(46f)
        val view = Ui.text(this, "RC", 14f, Ui.GOLD, true).apply {
            gravity = Gravity.CENTER
            background = Ui.oval(Ui.withAlpha(Ui.BG, 0.88f), Ui.GOLD, dp(2f))
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        // No jogo (tela deitada) este ponto cai no topo, entre os ícones de ban e o título:
        // um lugar sem texto nem botão do jogo. Dá para arrastar para onde quiser.
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(300f)
        lp.y = dp(2f)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = lp.x
                        startY = lp.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (moved || abs(dx) > slop || abs(dy) > slop) {
                            moved = true
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            runCatching { wm.updateViewLayout(view, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) togglePanel()
                }
                return true
            }
        })
        wm.addView(view, lp)
        bubble = view
        bubbleLp = lp
    }

    // --------------------------------------------------------------- janela

    private fun togglePanel() {
        if (panel != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val w = min(dp(470f), dm.widthPixels - dp(16f))
        // Deitado (no jogo): deixa livre a faixa do topo, onde o jogo escreve a fase do draft,
        // e abre no centro, porque as colunas dos times ficam nas laterais. Tudo isso precisa
        // continuar visível para a leitura da tela.
        val top = if (landscape) (dm.heightPixels * 0.115f).toInt() else 0
        val h = min(dp(430f), if (landscape) dm.heightPixels - top - dp(4f) else (dm.heightPixels * 0.94f).toInt())
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS_PASSIVE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (dm.widthPixels - w) / 2
        lp.y = if (landscape) top else (dm.heightPixels - h) / 2
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        try {
            val p = Panel(this, this)
            wm.addView(p.root, lp)
            panel = p
            panelLp = lp
            reader?.poke()
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir a janela: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it.root) } }
        panel = null
        panelLp = null
    }

    override fun closePanel() = removePanel()

    override fun setPanelFocusable(focusable: Boolean) {
        val p = panel ?: return
        val lp = panelLp ?: return
        val flags = if (focusable) FLAGS_TYPING else FLAGS_PASSIVE
        if (lp.flags == flags) return
        lp.flags = flags
        runCatching { wm.updateViewLayout(p.root, lp) }
    }

    override fun dragPanel(dx: Int, dy: Int) {
        val p = panel ?: return
        val lp = panelLp ?: return
        lp.x += dx
        lp.y += dy
        runCatching { wm.updateViewLayout(p.root, lp) }
    }

    companion object {
        const val ACTION_STOP = "com.riftcoach.app.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "overlay"
        private const val REVEAL_HIDE_MS = 3500L

        /** Normal: a janela não rouba o teclado nem os toques fora dela (o jogo continua jogável). */
        private const val FLAGS_PASSIVE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        /** Durante a busca: aceita teclado, mas toques fora continuam indo para o jogo. */
        private const val FLAGS_TYPING = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        @Volatile
        var running = false
    }
}
