package tr.edu.balikesir.anketrapor

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** 1.0.4: gömülü no-think model + sert timeout + tek güvenli AGENT/3 planlama yolu. */
class LocalPlannerActivity : Activity() {
    companion object {
        const val EXTRA_PROMPT = "planner_prompt"
        const val EXTRA_RECEIVER = "planner_receiver"
        const val RESULT_OK_PLAN = 1
        const val RESULT_ERROR_PLAN = -1
        private const val HARD_TIMEOUT_MS = 75_000L

        private val SYSTEM_PROMPT = """
          Yalnız geçerli JSON üret. Şema: {"version":3,"name":"kısa ad","steps":[...]}.
          Açıklama, markdown ve kod bloğu yazma.
          İzinli op'lar: var.set, clipboard.read, list.append, if, foreach, repeat,
          dataset.filter, dataset.sort, dataset.dedupe, dataset.join, assert,
          app.open, url.open, ui.tap, ui.set_text, ui.read_text, ui.wait_text,
          wait, back, swipe, instagram.share_ajan_folder, web.search_extract,
          output.xlsx, status, stop.
          UI seçicileri ui.* içinde any:["metin","alternatif"] kullanır.
          web.search_extract: queries, target_count, max_pages, allowed_domains,
          link_contains, must_contain, must_not_contain, fields ve output kullanabilir.
          field: {name,type:"text|number",regex:[...],min,max,max_exclusive}.
          Bir bilgi farklı kaynaklardaysa ayrı web.search_extract output'ları üret,
          sonra dataset.join/filter/dedupe ile birleştir ve output.xlsx yaz.
          Dinamik değerler için AGENT/3 ifadelerini kullan; gereksiz adım üretme.
          Sonsuz döngü yok; repeat/foreach sınırlı olsun.
          Ödeme, banka, şifre, OTP/SMS okuma, satın alma, silme veya son
          Yayınla/Paylaş/Gönder eylemini otomatikleştirme. Veri yetersizse başarı uydurma;
          assert veya açık stop mesajı kullan. Sonuç dosyası istenirse kaynak URL'lerini koru.
        """.trimIndent()
    }

    private var receiver: ResultReceiver? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val main = Handler(Looper.getMainLooper())
    private val delivered = AtomicBoolean(false)
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = intent.getParcelableExtra(EXTRA_RECEIVER)
        buildUi()
        val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
        if (prompt.isEmpty()) { fail("Planlanacak görev metni boş."); return }
        startedAt = SystemClock.elapsedRealtime()
        main.postDelayed(hardTimeout, HARD_TIMEOUT_MS)
        main.post(elapsedTicker)
        Thread { prepareAndPlan(prompt) }.start()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Yerel Ajan • Akıllı Planlayıcı"
            textSize = 22f
            setTextColor(Color.rgb(25, 28, 33))
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        })
        status = TextView(this).apply {
            text = "Gömülü model hazırlanıyor…"
            textSize = 15f
            setTextColor(Color.rgb(45, 49, 57))
            setPadding(0, dp(16), 0, dp(12))
        }
        root.addView(status)
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        setContentView(root)
    }

    private fun prepareAndPlan(userPrompt: String) {
        try {
            if (!BundledModelInstaller.selfTest()) throw IllegalStateException("Model kurulum çekirdeği öz testi başarısız.")
            if (!LocalModelRegistry.looksInstalled(this, LocalModelRegistry.BUNDLED)) {
                BundledModelInstaller.ensureInstalled(this) { done, total ->
                    if (!delivered.get()) {
                        runOnUiThread {
                            progress.isIndeterminate = false
                            progress.progress = ((done * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
                            status.text = "Gömülü model hazırlanıyor • ${mb(done)} / ${mb(total)}"
                        }
                    }
                }
            }
            if (delivered.get()) return
            runOnUiThread { progress.isIndeterminate = true; status.text = "${LocalModelRegistry.BUNDLED.name} • CPU planlıyor…" }
            val plan = runModel(userPrompt)
            if (plan == null) throw IllegalStateException("Model geçerli AGENT/3 planı üretemedi.")
            val script = "AGENT/3\n$plan"
            AgentScriptEngineV3.parse(script)
            val secure = SecureStore(this)
            secure.put("last_text", script)
            secure.put(AgentScriptRuntimeV5.SCRIPT_SAVED, script)
            succeed(script)
        } catch (t: Throwable) {
            fail("Plan oluşturulamadı: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun runModel(userPrompt: String): String? {
        var engine: Engine? = null
        try {
            engine = Engine(
                EngineConfig(
                    modelPath = LocalModelRegistry.file(this, LocalModelRegistry.BUNDLED).absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = LocalModelRegistry.BUNDLED.maxTokens,
                    cacheDir = cacheDir.absolutePath
                )
            )
            engine.initialize()
            if (delivered.get()) return null
            val config = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                samplerConfig = SamplerConfig(topK = 16, topP = 0.85, temperature = 0.05, seed = 7),
                automaticToolCalling = false
            )
            engine.createConversation(config).use { conversation ->
                var raw = conversation.sendMessage("GÖREV: $userPrompt\nYalnız tek JSON nesnesi üret.").toString()
                try {
                    val json = cleanJson(raw)
                    AgentScriptEngineV3.parse("AGENT/3\n$json")
                    return json
                } catch (first: Exception) {
                    if (delivered.get() || elapsed() > 58_000L) return null
                    runOnUiThread { status.text = "Plan şeması düzeltiliyor…" }
                    raw = conversation.sendMessage(
                        "Çıktı geçersiz: ${sanitize(first.message)}. Aynı görevi daha kısa üret. " +
                        "Yalnız {\"version\":3,\"name\":...,\"steps\":[...]} JSON'u döndür."
                    ).toString()
                    val json = cleanJson(raw)
                    AgentScriptEngineV3.parse("AGENT/3\n$json")
                    return json
                }
            }
        } catch (_: Throwable) {
            return null
        } finally {
            try { if (engine?.isInitialized() == true) engine?.close() } catch (_: Throwable) {}
        }
    }

    private fun cleanJson(raw: String): String {
        val s = raw.trim()
        var start = -1; var depth = 0; var inString = false; var escaped = false
        for (i in s.indices) {
            val ch = s[i]
            if (start < 0) { if (ch == '{') { start = i; depth = 1 }; continue }
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
                continue
            }
            if (ch == '"') inString = true
            else if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) {
                    val json = s.substring(start, i + 1)
                    JSONObject(json)
                    return json
                }
            }
        }
        throw IllegalArgumentException("Model geçerli JSON nesnesi üretmedi.")
    }

    private fun succeed(script: String) {
        if (!delivered.compareAndSet(false, true)) return
        main.removeCallbacks(hardTimeout); main.removeCallbacks(elapsedTicker)
        runOnUiThread {
            progress.isIndeterminate = false; progress.progress = 100
            status.text = "✓ Plan doğrulandı ve yerel hafızaya kaydedildi."
        }
        receiver?.send(RESULT_OK_PLAN, Bundle().apply {
            putString("script", script)
            putString("model", LocalModelRegistry.BUNDLED.name)
        })
    }

    private fun fail(message: String) {
        if (!delivered.compareAndSet(false, true)) return
        main.removeCallbacks(hardTimeout); main.removeCallbacks(elapsedTicker)
        runOnUiThread { status.text = "✕ $message"; progress.isIndeterminate = false; progress.progress = 0 }
        receiver?.send(RESULT_ERROR_PLAN, Bundle().apply { putString("message", message) })
    }

    private val hardTimeout = Runnable {
        if (delivered.get()) return@Runnable
        fail("Planlayıcı 75 saniyelik güvenli süre sınırını aştı. Takılmak yerine durduruldu.")
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 700L)
    }

    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (delivered.get()) return
            val sec = elapsed() / 1000
            if (::status.isInitialized && status.text.contains("planlıyor")) status.text = "${LocalModelRegistry.BUNDLED.name} • CPU planlıyor… ${sec}s"
            main.postDelayed(this, 1000L)
        }
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt
    private fun mb(n: Long) = String.format(java.util.Locale.US, "%.0f MB", n / (1024.0 * 1024.0))
    private fun sanitize(message: String?) = (message ?: "doğrulama hatası").replace('\n',' ').replace('\r',' ').take(180)
    private fun dp(n: Int) = Math.round(n * resources.displayMetrics.density)
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}
