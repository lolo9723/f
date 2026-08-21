package tr.edu.balikesir.anketrapor

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import org.json.JSONObject

/**
 * :planner prosesinde çalışır. INTERNET veya Accessibility erişimi yoktur.
 * Model yalnız AGENT/3 planı üretir; plan parser doğrulamasından geçmeden kaydedilmez.
 */
class LocalPlannerActivity : Activity() {
    companion object {
        const val EXTRA_PROMPT = "planner_prompt"
        const val EXTRA_RECEIVER = "planner_receiver"
        const val RESULT_OK_PLAN = 1
        const val RESULT_ERROR_PLAN = -1

        private val JSON_SCHEMA = """
          {
            "type":"object",
            "properties":{
              "version":{"type":"integer","enum":[3]},
              "name":{"type":"string"},
              "steps":{"type":"array","minItems":1,"maxItems":300,"items":{"type":"object","properties":{"op":{"type":"string"}},"required":["op"],"additionalProperties":true}}
            },
            "required":["version","steps"],
            "additionalProperties":true
          }
        """.trimIndent()

        private val SYSTEM_PROMPT = """
          Sen Yerel Ajan'ın görev planlayıcısısın. Yalnızca AGENT/3 JSON nesnesi üret.
          Kesin kurallar:
          - Açıklama, markdown, kod bloğu, doğal dil cevabı verme; yalnız JSON.
          - version her zaman 3.
          - Asla ödeme, banka, şifre, OTP, SMS okuma, satın alma, silme veya son Yayınla/Paylaş/Gönder adımını otomatikleştirme.
          - Veri araştırmasında mümkünse iki bağımsız kaynak kullan, kaynak URL'lerini sonuçta koru.
          - Yetersiz doğrulama varsa assert veya açık başarısızlık üret; tahmin ederek başarı verme.
          - Sonsuz döngü kurma. repeat/foreach yalnız sınırlı veri üzerinde olsun.

          Desteklenen üst düzey op'lar:
          var.set {name, value|expr}; clipboard.read {name}; list.append {name,value};
          if {condition,then,else}; foreach {list,item,index,steps}; repeat {count|count_expr,steps};
          dataset.filter {source,target,item,where}; dataset.sort {source,target,item,key,ascending};
          dataset.dedupe {source,target,item,key}; dataset.join {left,right,target,left_key,right_key,left_var,right_var,type};
          assert {expr,message};
          app.open {package}; url.open {url}; ui.tap {any:[...]}; ui.set_text {any:[...],value|expr|source:"clipboard"};
          ui.read_text {name,any:[...]}; ui.wait_text {any:[...]}; wait {ms}; back; swipe {direction};
          instagram.share_ajan_folder;
          web.search_extract {queries:[...],target_count,max_pages,filename,allowed_domains,link_contains,must_contain,must_not_contain,fields:[{name,type,regex,min,max,max_exclusive}],output,allow_partial};
          output.xlsx {source,filename,columns:[{header,field|expr}],hyperlink_header}; status {message,expr}; stop {message}.

          İfade biçimi:
          {"var":"x"} veya {"op":"add|sub|mul|div|mod|round|floor|ceil|min|max|eq|ne|gt|gte|lt|lte|and|or|not|concat|lower|upper|trim|contains|starts_with|ends_with|replace|regex|to_number|to_text|length|get|coalesce|array|object","args":[...]}
          Bir nesne alanını okumak için: {"op":"get","args":[{"var":"row"},"Alan Adı"]}.
          Dinamik web sorgularında ${'$'}{degisken.yolu} şablonu kullanılabilir.
          Çok kaynaklı görevlerde her web.search_extract sonucunu farklı output değişkenine al, gerekiyorsa foreach ile ikinci kaynağı ara, ardından dataset.join/filter/dedupe/sort ve output.xlsx kullan.
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
            if (plan == null) throw IllegalStateException("GPU ve CPU model başlatma denemeleri başarısız oldu.")
            val script = "AGENT/3\n$plan"
            AgentScriptEngineV3.parse(script)
            SecureStore(this).put("last_text", script)
            SecureStore(this).put(AgentScriptRuntimeV5.SCRIPT_SAVED, script)
            runOnUiThread {
                status.text = "✓ Plan doğrulandı ve şifreli yerel hafızaya kaydedildi. 20. Özel Agent Görevi'nden çalıştırabilirsin."
                progress.isIndeterminate = false
                progress.progress = 100
            }
            val b = Bundle().apply { putString("script", script); putString("model", model.name) }
            receiver?.send(RESULT_OK_PLAN, b)
        } catch (t: Throwable) {
            fail("Plan oluşturulamadı: ${t.message ?: t.javaClass.simpleName}")
        }
    }

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
                samplerConfig = SamplerConfig(topK = 32, topP = 0.90, temperature = 0.15, seed = 7),
                automaticToolCalling = false,
                maxOutputToken = 6000,
                thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 900),
                enableResponseFormat = true
            )
            engine.createConversation(config).use { conversation ->
                val response = conversation.sendMessage(
                    "KULLANICI GÖREVİ:\n$userPrompt\n\nBu görevi güvenli ve tamamlanabilir AGENT/3 planına dönüştür.",
                    maxOutputToken = 6000,
                    thinkingConfig = ThinkingConfig(enableThinking = true, thinkingTokenBudget = 900),
                    responseFormat = ResponseFormat.json(JSON_SCHEMA)
                )
                var json = cleanJson(response.toString())
                try {
                    AgentScriptEngineV3.parse("AGENT/3\n$json")
                    return json
                } catch (first: Exception) {
                    val repair = conversation.sendMessage(
                        "Ürettiğin JSON AGENT/3 doğrulamasından geçmedi. Hata: ${first.message}. Yalnız düzeltilmiş JSON nesnesini yeniden üret.",
                        maxOutputToken = 6000,
                        thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                        responseFormat = ResponseFormat.json(JSON_SCHEMA)
                    )
                    json = cleanJson(repair.toString())
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
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.replaceFirst(Regex("^```(?:json)?\\s*"), "").replaceFirst(Regex("\\s*```$"), "")
        }
        val first = s.indexOf('{')
        val last = s.lastIndexOf('}')
        if (first >= 0 && last > first) s = s.substring(first, last + 1)
        JSONObject(s) // syntax validation
        return s
    }

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
