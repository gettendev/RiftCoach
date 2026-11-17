package com.riftcoach.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import com.google.mlkit.vision.common.InputImage

/**
 * Tela de teste (só na versão "dev"): finge ser o jogo mostrando um print em tela cheia.
 *   am start -n com.riftcoach.app/.DevScreenActivity --es f 6.jpg     mostra o print
 *   am start -n com.riftcoach.app/.DevScreenActivity --es ocr all     lê todos os prints direto e registra no log
 */
class DevScreenActivity : Activity() {

    private lateinit var image: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.ensureLoaded(this)
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(0xFF000000.toInt())
        }
        // O jogo de verdade tem fundo animado, então a captura recebe quadros novos o tempo todo.
        // Um print parado não gera quadros; este pontinho piscando no canto imita o movimento.
        val blink = View(this)
        val root = android.widget.FrameLayout(this)
        root.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(blink, android.widget.FrameLayout.LayoutParams(8, 8, android.view.Gravity.BOTTOM or android.view.Gravity.END))
        setContentView(root)
        var on = false
        val ticker = object : Runnable {
            override fun run() {
                on = !on
                blink.setBackgroundColor(if (on) 0xFF202020.toInt() else 0xFF303030.toInt())
                blink.postDelayed(this, 150)
            }
        }
        blink.post(ticker)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideBars()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun load(name: String): Bitmap =
        assets.open("prints/$name").use { BitmapFactory.decodeStream(it) }

    private fun handle(intent: Intent) {
        intent.getStringExtra("f")?.let {
            image.setImageBitmap(load(it))
            Log.d(ScreenReader.TAG, "DEV mostrando $it")
        }
        if (intent.getStringExtra("ocr") != null) {
            val names = (assets.list("prints") ?: emptyArray()).sortedBy { it.substringBefore('.').toIntOrNull() ?: 0 }
            ocrNext(names, 0, intent.getStringExtra("raw") != null)
        }
    }

    private fun ocrNext(names: List<String>, i: Int, raw: Boolean) {
        if (i >= names.size) {
            Log.d(ScreenReader.TAG, "DEV fim")
            return
        }
        val src = load(names[i])
        // o celular real tem a tela maior que o print; aqui ampliamos para o tamanho que o leitor usa
        val bmp = Bitmap.createScaledBitmap(src, 2048, 2048 * src.height / src.width, true)
        FrameOcr.recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val lines = FrameOcr.lines(text, bmp, 0, 0, bmp.width, bmp.height)
                if (raw) for (l in lines) {
                    Log.d(ScreenReader.TAG, "DEV ${names[i]} linha x=%.3f y=%.3f %s'%s'".format(l.x0, l.cy, if (l.gold) "OURO " else "", l.text))
                }
                val read = Repo.parser!!.parse(lines)
                Log.d(ScreenReader.TAG, "DEV ${names[i]}: " + FrameOcr.describe(read, Repo.engine!!))
            }
            .addOnFailureListener { Log.w(ScreenReader.TAG, "DEV ${names[i]} falhou", it) }
            .addOnCompleteListener { ocrNext(names, i + 1, raw) }
    }
}
