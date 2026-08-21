package tr.edu.balikesir.anketrapor

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.ResultReceiver
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

/**
 * :planner prosesinde çalışır. INTERNET veya Accessibility erişimi yoktur.
 * Model yalnız AGENT/3 planı üretir; parser + güvenlik doğrulamasını geçmeden kaydedilmez.
 * LiteRT-LM 0.11 API'siyle uyumludur; JSON doğruluğu çok aşamalı parse/repair ile zorlanır.
 */
class LocalPlannerActivity : Activity() {
    companion object {
        const val EXTRA_PROMPT = "planner_prompt"
        const val EXTRA_RECEIVER = "planner_receiver"
        const val RESULT_OK_PLAN = 1
        const val RESULT_ERROR_PLAN = -1

        private val SYSTEM_PROMPT = """
          Sen Yerel Ajan'ın görev planlayıcısısın. YALNIZCA geçerli AGENT/3 JSON nesnesi üret.
          Cevabın ilk karakteri { ve son karakteri } olmalı. Markdown/kod bloğu/açıklama kullanma.

          ZORUNLU KÖK ŞEMA:
          {"version":3,"name":"görev adı","steps":[...]}
          - version kesinlikle 3.
          - steps boş olamaz ve her step nesnesinde op bulunmalı.
          - Desteklenmeyen op üretme.
          - Asla ödeme, banka, şifre, OTP/SMS okuma, satın alma, silme veya son Yayınla/Paylaş/Gönder eylemini otomatikleştirme.
          - Yetersiz veri varsa başarı uydurma; assert veya açık hata/eksik sonuç yolu oluştur.
          - Sonsuz döngü yok. repeat/foreach sınırlı olmalı.
          - Web araştırmasında mümkünse gerçek kaynak URL'lerini koru ve birden çok kaynaktan veri gerekiyorsa ayrı dataset üretip JOIN et.

          DESTEKLENEN OP'LAR:
          var.set {name,value|expr}; clipboard.read {name}; list.append {name,value};
          if {condition,then,else}; foreach {list,item,index,steps}; repeat {count|count_expr,steps};
          dataset.filter {source,target,item,where}; dataset.sort {source,target,item,key,ascending};
          dataset.dedupe {source,target,item,key}; dataset.join {left,right,target,left_key,right_key,left_var,right_var,type};
          assert {expr,message}; app.open {package}; url.open {url};
          ui.tap {any:[...]}; ui.set_text {any:[...],value|expr|source:"clipboard"};
          ui.read_text {name,any:[...]}; ui.wait_text {any:[...]}; wait {ms}; back; swipe {direction};
          instagram.share_ajan_folder;
          web.search_extract {queries:[...],target_count,max_pages,filename,allowed_domains,link_contains,must_contain,must_not_contain,fields:[{name,type,regex,min,max,max_exclusive}],output,allow_partial};
          output.xlsx {source,filename,columns:[{header,field|expr}],hyperlink_header}; status {message,expr}; stop {message}.

          İFADELER:
          {"var":"x"}
          veya {"op":"add|sub|mul|div|mod|round|floor|ceil|min|max|eq|ne|gt|gte|lt|lte|and|or|not|concat|lower|upper|trim|contains|starts_with|ends_with|replace|regex|to_number|to_text|length|get|coalesce|array|object","args":[...]}
          Nesne alanı: {"op":"get","args":[{"var":"row"},"Alan Adı"]}
          Dinamik sorgu: ${'$'}{degisken.yolu}

          ÇOK KAYNAKLI GÖREV KURALI:
          Aynı sonuç için puan/yorum bir kaynaktan, fiyat/özellik başka kaynaktan geliyorsa hepsini tek sayfada arama.
          Her kaynağı farklı web.search_extract output değişkenine al; ortak ad/kimlik üzerinden normalize et, dataset.join kullan, hesaplanan alanları expr ile üret, son filtreyi JOIN sonrasında uygula, sonra output.xlsx ile çıkar.
        """.trimIndent()
    }

    private var receiver: ResultReceiver? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = intent.getParcelableExtra(EXTRA_RECEIVER)
        buildUi()
        val prompt = intent.getStringExtra(EXTRA_PROMPT)?.trim().orEmpty()
        if (prompt.isEmpty()) {
            fail("Planlanacak görev metni boş.")
            return
        }
        val model = LocalModelRegistry.strongestInstalled(this)
        if (model == null) {
            fail("Yerel model kurulu değil. Önce 🧠 menüsünden modeli kur.")
            return
        }
        status.text = "${model.name} yükleniyor…"
        Thread { runPlanner(model, prompt) }.start()
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
            text = "Hazırlanıyor…"
            textSize = 15f
            setTextColor(Color.rgb(45, 49, 57))
            setPadding(0, dp(16), 0, dp(12))
        }
        root.addView(status)
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        setContentView(root)
    }

    private fun runPlanner(model: LocalModelRegistry.Model, userPrompt: String) {
        try {
            val plan = tryBackend(model, userPrompt, true) ?: tryBackend(model, userPrompt, false)
            if (plan == null) throw IllegalStateException("GPU ve CPU planlama denemeleri geçerli bir AGENT/3 planı üretemedi.")
            val script = "AGENT/3\n$plan"
            AgentScriptEngineV3.parse(script)
            val secure = SecureStore(this)
            secure.put("last_text", script)
            secure.put(AgentScriptRuntimeV5.SCRIPT_SAVED, script)
            runOnUiThread {
                status.text = "✓ Plan iki aşamalı doğrulamadan geçti ve şifreli yerel hafızaya kaydedildi."
                progress.isIndeterminate = false
                progress.progress = 100
            }
            receiver?.send(RESULT_OK_PLAN, Bundle().apply {
                putString("script", script)
                putString("model", model.name)
            })
        } catch (t: Throwable) {
            fail("Plan oluşturulamadı: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** LiteRT-LM 0.11: response-format/thinking API yok; sıkı prompt + parser + iki repair turu kullanılır. */
    private fun tryBackend(model: LocalModelRegistry.Model, userPrompt: String, gpu: Boolean): String? {
        var engine: Engine? = null
        try {
            runOnUiThread { status.text = "${model.name} • ${if (gpu) "GPU" else "CPU"} planlıyor…" }
            val backend = if (gpu) Backend.GPU() else Backend.CPU()
            engine = Engine(
                EngineConfig(
                    modelPath = LocalModelRegistry.file(this, model).absolutePath,
                    backend = backend,
                    maxNumTokens = 8192,
                    cacheDir = cacheDir.absolutePath
                )
            )
            engine.initialize()
            val config = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                samplerConfig = SamplerConfig(topK = 24, topP = 0.88, temperature = 0.10, seed = 7),
                automaticToolCalling = false
            )
            engine.createConversation(config).use { conversation ->
                var raw = conversation.sendMessage(
                    "KULLANICI GÖREVİ:\n$userPrompt\n\nGörevi güvenli, tamamlanabilir ve mümkün olduğunca genel AGENT/3 planına dönüştür. Yalnız JSON döndür."
                ).toString()

                repeat(3) { attempt ->
                    try {
                        val json = cleanJson(raw)
                        AgentScriptEngineV3.parse("AGENT/3\n$json")
                        return json
                    } catch (validation: Exception) {
                        if (attempt >= 2) return@repeat
                        raw = conversation.sendMessage(
                            "Önceki çıktın geçersizdi. Hata: ${sanitize(validation.message)}. " +
                                "Aynı görevi yeniden üret. Yalnız tek bir geçerli JSON nesnesi döndür; kökte version=3 ve steps dizisi olsun; yalnız desteklenen op'ları kullan."
                        ).toString()
                    }
                }
                return null
            }
        } catch (_: Throwable) {
            return null
        } finally {
            try { if (engine?.isInitialized() == true) engine?.close() } catch (_: Throwable) {}
        }
    }

    /** Model etrafına açıklama eklese bile ilk dengeli JSON nesnesini güvenli biçimde ayıklar. */
    private fun cleanJson(raw: String): String {
        val s = raw.trim()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in s.indices) {
            val ch = s[i]
            if (start < 0) {
                if (ch == '{') { start = i; depth = 1 }
                continue
            }
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
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

    private fun sanitize(message: String?): String =
        (message ?: "doğrulama hatası").replace('\n', ' ').replace('\r', ' ').take(360)

    private fun fail(message: String) {
        runOnUiThread {
            status.text = "✕ $message"
            progress.isIndeterminate = false
            progress.progress = 100
        }
        receiver?.send(RESULT_ERROR_PLAN, Bundle().apply { putString("message", message) })
    }

    private fun dp(n: Int) = Math.round(n * resources.displayMetrics.density)
}
