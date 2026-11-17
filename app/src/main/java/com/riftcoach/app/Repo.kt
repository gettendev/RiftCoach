package com.riftcoach.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Guarda os dados carregados, o draft atual e as preferências. */
object Repo {
    private const val HERO_URL = "https://game.gtimg.cn/images/lgamem/act/lrlib/js/heroList/hero_list.js"
    private const val RANK_URL = "https://mlol.qt.qq.com/go/lgame_battle_info/hero_rank_list_v2"
    private const val AUTO_UPDATE_MS = 12L * 60 * 60 * 1000
    private const val TAG = "RiftCoach"

    @Volatile
    var data: GameData? = null
        private set

    @Volatile
    var engine: Engine? = null
        private set

    val draft = Draft()

    /** Acompanha o draft lido da tela. */
    val tracker = DraftTracker(draft)

    @Volatile
    var parser: ScreenParser? = null
        private set

    const val READER_OFF = 0
    const val READER_WAITING = 1
    const val READER_READING = 2

    /** Estado da leitura automática da tela (mostrado na janela). */
    @Volatile
    var readerState = READER_OFF

    @Volatile
    private var updating = false

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("riftcoach", Context.MODE_PRIVATE)

    @Synchronized
    fun ensureLoaded(ctx: Context): Engine {
        engine?.let { return it }
        val app = ctx.applicationContext
        val meta = readAsset(app, "champions.json")
        var d: GameData? = null
        val fh = File(app.filesDir, "hero_list.json")
        val fr = File(app.filesDir, "rank.json")
        if (fh.exists() && fr.exists()) {
            d = runCatching { DataParser.parse(fh.readText(), fr.readText(), meta) }.getOrNull()
        }
        val bundled = DataParser.parse(readAsset(app, "hero_list.json"), readAsset(app, "rank.json"), meta)
        if (d == null || d.date < bundled.date) d = bundled
        draft.myLane = Lane.fromName(prefs(app).getString("lane", null)) ?: Lane.ADC
        return install(app, d)
    }

    private fun install(ctx: Context, d: GameData): Engine {
        val e = Engine(d)
        e.tier = prefs(ctx).getString("tier", "1") ?: "1"
        data = d
        engine = e
        parser = ScreenParser(NameIndex(d.champs.values))
        return e
    }

    @Volatile
    var gameDb: GameDb? = null
        private set

    /**
     * Abre a enciclopédia (todos os números do jogo). O arquivo é grande, então a leitura
     * acontece em segundo plano; `done` recebe a base, ou null e a mensagem de erro.
     */
    /** pedidos esperando a base terminar de carregar (só a thread principal mexe nesta lista) */
    private val dbWaiting = ArrayList<(GameDb?, String?) -> Unit>()

    /** Uma thread só para as contas pesadas (base e builds), para não disputarem o processador. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun loadDb(ctx: Context, done: (GameDb?, String?) -> Unit) {
        gameDb?.let {
            done(it, null)
            return
        }
        dbWaiting.add(done)
        if (dbWaiting.size > 1) return // já está carregando
        val app = ctx.applicationContext
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            var err: String? = null
            val db = try {
                GameDbParser.parse(
                    readAsset(app, "db_heroes.json"), readAsset(app, "db_items.json"),
                    readAsset(app, "db_runes.json"), readAsset(app, "db_spells.json"), readAsset(app, "names_pt.json")
                )
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
                null
            }
            if (db != null) {
                gameDb = db
                val d = data
                if (d != null) {
                    runCatching {
                        val models = ItemModels.parse(readAsset(app, "items_model.json"))
                        val runes = RuneData.parse(readAsset(app, "runes_model.json"))
                        itemModels = models
                        runeData = runes
                        buildEngine = BuildEngine(db, d, models, runes)
                    }.onFailure { err = "modelo de itens: " + (it.message ?: it.javaClass.simpleName) }
                }
            }
            Log.d(TAG, "base de dados carregada em ${SystemClock.elapsedRealtime() - t0} ms" + (err?.let { " (erro: $it)" } ?: ""))
            Handler(Looper.getMainLooper()).post {
                val list = ArrayList(dbWaiting)
                dbWaiting.clear()
                for (cb in list) cb(db, err)
            }
        }
    }

    @Volatile
    var itemModels: Map<String, ItemModel>? = null
        private set

    /** tradução e efeitos das runas, e tradução dos feitiços */
    @Volatile
    var runeData: RuneData? = null
        private set

    @Volatile
    private var buildEngine: BuildEngine? = null

    private var buildKey: String? = null
    private var buildResult: BuildResult? = null

    @Volatile
    private var buildPending: String? = null

    private fun draftKey(d: Draft): String =
        d.myLane.name + "|" + d.myPick + "|" + d.enemies.joinToString(",") + "|" +
            d.allies.entries.joinToString(",") { it.key.name + "=" + it.value }

    /** A conta deste draft já terminou e não deu resultado? (campeão sem dados na base, por exemplo) */
    fun buildFailed(d: Draft): Boolean = draftKey(d) == buildKey && buildResult == null

    /**
     * A build calculada para o draft atual. Se ainda não estiver pronta, devolve null, calcula
     * em segundo plano e chama `ready` quando terminar. Só a thread principal chama isto.
     */
    fun buildFor(ctx: Context, d: Draft, ready: () -> Unit): BuildResult? {
        val key = draftKey(d)
        if (key == buildKey) return buildResult
        if (key == buildPending) return null
        buildPending = key
        // cópia do draft: a leitura da tela pode mudá-lo enquanto a conta roda
        val copy = Draft().apply {
            myLane = d.myLane
            myPick = d.myPick
            enemies.addAll(d.enemies)
            allies.putAll(d.allies)
        }
        loadDb(ctx) { _, _ ->
            val engine = buildEngine
            worker.execute {
                // o draft já mudou de novo enquanto este pedido esperava: pula, o mais novo está na fila
                if (buildPending != key) return@execute
                val t0 = SystemClock.elapsedRealtime()
                val r = runCatching { engine?.build(copy) }
                    .onFailure { Log.w(TAG, "falha ao calcular a build", it) }
                    .getOrNull()
                Log.d(TAG, "build, runas e feitiços calculados em ${SystemClock.elapsedRealtime() - t0} ms")
                Handler(Looper.getMainLooper()).post {
                    if (buildPending == key) {
                        buildKey = key
                        buildResult = r
                        buildPending = null
                        ready()
                    }
                }
            }
        }
        return null
    }

    fun autoRead(ctx: Context): Boolean = prefs(ctx).getBoolean("autoRead", true)

    fun setAutoRead(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("autoRead", on).apply()
    }

    fun setTier(ctx: Context, tier: String) {
        prefs(ctx).edit().putString("tier", tier).apply()
        engine?.tier = tier
    }

    fun setLane(ctx: Context, lane: Lane) {
        prefs(ctx).edit().putString("lane", lane.name).apply()
        draft.myLane = lane
        draft.allies.remove(lane)
    }

    private fun readAsset(ctx: Context, name: String): String =
        ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Baixa os dados novos da Tencent. Roda em segundo plano; `done` recebe null se deu certo ou a mensagem de erro. */
    fun update(ctx: Context, done: (String?) -> Unit) {
        if (updating) return
        updating = true
        val app = ctx.applicationContext
        Thread {
            val err: String? = try {
                val hero = fetch(HERO_URL)
                val rank = fetch(RANK_URL)
                val d = DataParser.parse(hero, rank, readAsset(app, "champions.json"))
                if (d.champs.size < 50 || d.stats.isEmpty()) {
                    "os dados vieram incompletos"
                } else {
                    File(app.filesDir, "hero_list.json").writeText(hero)
                    File(app.filesDir, "rank.json").writeText(rank)
                    prefs(app).edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
                    synchronized(this) { install(app, d) }
                    null
                }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            updating = false
            Handler(Looper.getMainLooper()).post { done(err) }
        }.start()
    }

    /** Atualiza sozinho se a última atualização tem mais de 12 horas. */
    fun autoUpdate(ctx: Context, done: (String?) -> Unit) {
        val last = prefs(ctx).getLong("lastUpdate", 0L)
        if (System.currentTimeMillis() - last > AUTO_UPDATE_MS) update(ctx, done)
    }
}
