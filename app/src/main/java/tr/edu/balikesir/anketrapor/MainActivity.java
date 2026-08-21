package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int REQ_TEXT_FILE = 1101;
    private static final int REQ_IMAGES = 1102;
    private static final int REQ_PDF = 1103;

    private SecureStore secureStore;
    private LinearLayout moduleHost;
    private TextView learningBanner;
    private Module selectedModule;
    private String taskText = "";
    private final ArrayList<String> selectedFiles = new ArrayList<>();

    private static final int BG = Color.rgb(246, 247, 249);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(28, 31, 36);
    private static final int MUTED = Color.rgb(102, 110, 120);
    private static final int GOOD = Color.rgb(25, 125, 70);
    private static final int WARN = Color.rgb(170, 104, 0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secureStore = new SecureStore(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLearningBanner();
        renderModules();
    }

    private void buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(30));
        scroller.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("Yerel Ajan", 28, true, TEXT));
        TextView sub = text("Telefonunda görünür otomasyon • son Yayınla / Paylaş / Gönder dokunuşu her zaman sende", 14, false, MUTED);
        LinearLayout.LayoutParams subLp = lp(); subLp.topMargin = dp(5); root.addView(sub, subLp);

        TextView security = text("✓ Çekirdek APK'da INTERNET izni yok  •  SMS / OTP / rehber / mikrofon / kamera izni yok", 13, true, GOOD);
        security.setPadding(dp(12), dp(10), dp(12), dp(10));
        security.setBackground(roundRect(Color.rgb(232, 246, 237), dp(12), Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams secLp = lp(); secLp.topMargin = dp(14); root.addView(security, secLp);

        learningBanner = text("", 13, true, WARN);
        learningBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        learningBanner.setBackground(roundRect(Color.rgb(255, 246, 224), dp(12), Color.TRANSPARENT, 0));
        learningBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams learnLp = lp(); learnLp.topMargin = dp(10); root.addView(learningBanner, learnLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsLp = lp(); controlsLp.topMargin = dp(14); root.addView(controls, controlsLp);

        Button accessibility = button("Erişilebilirliği Aç");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        controls.addView(accessibility, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button privacy = button("Güvenlik");
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(0, dp(48), .72f); privacyLp.leftMargin = dp(10);
        privacy.setOnClickListener(v -> showSecurityDialog());
        controls.addView(privacy, privacyLp);

        TextView section = text("Hazır görevler", 19, true, TEXT);
        LinearLayout.LayoutParams sectionLp = lp(); sectionLp.topMargin = dp(24); root.addView(section, sectionLp);
        TextView explain = text("Dış uygulamalardaki görevler ilk kullanımda bir kez 'Öğret' ister. Ajan kaydettiğin adımları tekrarlar ve kritik son düğmeden önce durur.", 13, false, MUTED);
        LinearLayout.LayoutParams explainLp = lp(); explainLp.topMargin = dp(5); root.addView(explain, explainLp);

        moduleHost = new LinearLayout(this);
        moduleHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hostLp = lp(); hostLp.topMargin = dp(10); root.addView(moduleHost, hostLp);

        setContentView(scroller);
        renderModules();
        refreshLearningBanner();
    }

    private void renderModules() {
        if (moduleHost == null) return;
        moduleHost.removeAllViews();
        for (Module m : modules()) {
            boolean calibrated = AgentAccessibilityService.hasCalibration(this, m.id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setBackground(roundRect(CARD, dp(14), Color.rgb(225, 228, 233), 1));
            card.setOnClickListener(v -> openModule(m));

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.TOP);
            card.addView(top, lp());

            TextView name = text(m.index + ".  " + m.name, 16, true, TEXT);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String status; int statusColor;
            if (m.mode == Mode.LOCAL_READY) { status = "ÇALIŞIR"; statusColor = GOOD; }
            else if (m.mode == Mode.PLANNED) { status = "ALTYAPI"; statusColor = MUTED; }
            else if (calibrated) { status = "HAZIR"; statusColor = GOOD; }
            else { status = "ÖĞRET"; statusColor = WARN; }
            TextView badge = text(status, 11, true, statusColor);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            badge.setBackground(roundRect(Color.rgb(247, 248, 250), dp(30), Color.rgb(220, 223, 228), 1));
            top.addView(badge);

            TextView desc = text(m.desc, 13, false, MUTED);
            LinearLayout.LayoutParams descLp = lp(); descLp.topMargin = dp(7); card.addView(desc, descLp);
            LinearLayout.LayoutParams cardLp = lp(); cardLp.bottomMargin = dp(10); moduleHost.addView(card, cardLp);
        }
    }

    private void openModule(Module m) {
        selectedModule = m;
        selectedFiles.clear();
        taskText = secureStore.get("last_text", "");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), 0);
        box.addView(text(m.desc, 14, false, MUTED), lp());

        TextView source = text("Metin kaynağı", 13, true, TEXT);
        LinearLayout.LayoutParams sourceLp = lp(); sourceLp.topMargin = dp(14); box.addView(source, sourceLp);
        Button textSource = button(taskText.isEmpty() ? "Metin seç: Pano / DOCX / TXT / Elle / Son" : "Metin hazır • değiştirmek için dokun");
        textSource.setOnClickListener(v -> chooseTextSource(textSource));
        LinearLayout.LayoutParams bLp = lp(); bLp.topMargin = dp(6); box.addView(textSource, bLp);

        if (m.media) {
            Button images = button("Görsel(ler) seç"); images.setOnClickListener(v -> pickImages());
            LinearLayout.LayoutParams x = lp(); x.topMargin = dp(8); box.addView(images, x);
        }
        if (m.pdf) {
            Button pdf = button("PDF / ek dosya seç"); pdf.setOnClickListener(v -> pickPdf());
            LinearLayout.LayoutParams x = lp(); x.topMargin = dp(8); box.addView(pdf, x);
        }

        boolean calibrated = AgentAccessibilityService.hasCalibration(this, m.id);
        if (m.mode == Mode.CALIBRATE) {
            Button teach = button(calibrated ? "Görevi yeniden öğret" : "Görevi bir kez öğret");
            teach.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Öğretme modu")
                    .setMessage("Ajan yalnızca tıkladığın alanların seçicilerini ve düzenlenebilir alanların sırasını kaydeder; yazdığın gerçek metni kaydetmez. Hedef uygulamada görevi elle yap, SON Yayınla/Paylaş/Gönder düğmesine basmadan Yerel Ajan'a dön ve üstteki turuncu banda dokun.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Başlat", (d,w) -> startLearning(m)).show());
            LinearLayout.LayoutParams x = lp(); x.topMargin = dp(12); box.addView(teach, x);
        }

        Button run = button(m.mode == Mode.PLANNED ? "Bu sürümde yürütücü yok" : "Hazırla / Çalıştır");
        run.setEnabled(m.mode != Mode.PLANNED && (m.mode != Mode.CALIBRATE || calibrated));
        run.setAlpha(run.isEnabled() ? 1f : .45f);
        run.setOnClickListener(v -> runModule(m));
        LinearLayout.LayoutParams runLp = lp(); runLp.topMargin = dp(12); box.addView(run, runLp);

        TextView finalRule = text("Kural: Ajan son Yayınla / Paylaş / Gönder düğmesine dokunmaz.", 12, true, GOOD);
        LinearLayout.LayoutParams finalLp = lp(); finalLp.topMargin = dp(10); box.addView(finalRule, finalLp);

        new AlertDialog.Builder(this).setTitle(m.name).setView(box).setNegativeButton("Kapat", null).show();
    }

    private void chooseTextSource(Button displayButton) {
        String[] sources = {"Pano", "DOCX / TXT dosyası", "Elle yaz", "Son kullanılan"};
        new AlertDialog.Builder(this).setTitle("Metin kaynağı").setItems(sources, (d, which) -> {
            if (which == 0) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = cm == null ? null : cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) { toast("Panoda metin yok."); return; }
                CharSequence s = clip.getItemAt(0).coerceToText(this);
                if (s == null || s.toString().trim().isEmpty()) { toast("Panoda kullanılabilir metin yok."); return; }
                setTaskText(s.toString()); displayButton.setText("Metin: Pano ✓");
            } else if (which == 1) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"});
                startActivityForResult(i, REQ_TEXT_FILE);
            } else if (which == 2) {
                EditText input = new EditText(this); input.setMinLines(6); input.setGravity(Gravity.TOP | Gravity.START);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); input.setText(taskText);
                new AlertDialog.Builder(this).setTitle("Metni yaz / yapıştır").setView(input).setNegativeButton("Vazgeç", null)
                        .setPositiveButton("Kullan", (x,y) -> { setTaskText(input.getText().toString()); displayButton.setText("Metin: Elle ✓"); }).show();
            } else {
                String last = secureStore.get("last_text", "");
                if (last.isEmpty()) toast("Son kullanılan metin yok."); else { taskText = last; displayButton.setText("Metin: Son kullanılan ✓"); }
            }
        }).show();
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(i, REQ_IMAGES);
    }

    private void pickPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(i, REQ_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == REQ_TEXT_FILE) {
                Uri uri = data.getData(); if (uri == null) return;
                String name = displayName(uri); String lower = name.toLowerCase(Locale.ROOT);
                String text = lower.endsWith(".docx") ? readDocx(uri) : readText(uri);
                if (text.trim().isEmpty()) toast("Dosyada metin bulunamadı."); else { setTaskText(text); toast("Metin alındı: " + name); }
            } else if (requestCode == REQ_IMAGES || requestCode == REQ_PDF) {
                collectNames(data); toast(selectedFiles.size() + " dosya göreve eklendi.");
            }
        } catch (Exception e) { toast("Dosya okunamadı: " + e.getClass().getSimpleName()); }
    }

    private void collectNames(Intent data) {
        ClipData clips = data.getClipData();
        if (clips != null) for (int n=0;n<clips.getItemCount();n++) { Uri u=clips.getItemAt(n).getUri(); if (u!=null) selectedFiles.add(displayName(u)); }
        else if (data.getData()!=null) selectedFiles.add(displayName(data.getData()));
    }

    private void setTaskText(String s) { taskText = s == null ? "" : s.trim(); if (!taskText.isEmpty()) secureStore.put("last_text", taskText); }

    private String readText(Uri uri) throws Exception {
        try (InputStream in=getContentResolver().openInputStream(uri)) { if (in==null) return ""; return new String(readAll(in), StandardCharsets.UTF_8); }
    }

    private String readDocx(Uri uri) throws Exception {
        try (InputStream base=getContentResolver().openInputStream(uri); ZipInputStream zin=new ZipInputStream(base)) {
            ZipEntry entry;
            while ((entry=zin.getNextEntry())!=null) if ("word/document.xml".equals(entry.getName())) {
                String xml=new String(readAll(zin),StandardCharsets.UTF_8).replace("<w:tab/>","\t").replace("</w:p>","\n").replace("</w:tr>","\n").replace("</w:tc>","\t");
                return xml.replaceAll("<[^>]+>","").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replaceAll("[ \\t]+\\n","\n").replaceAll("\\n{3,}","\n\n").trim();
            }
        }
        return "";
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>=0) out.write(buf,0,n); return out.toByteArray();
    }

    private String displayName(Uri uri) {
        String result="dosya"; Cursor c=null;
        try { c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null); if(c!=null&&c.moveToFirst()){int idx=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(idx>=0)result=c.getString(idx);} }
        catch(Exception ignored){} finally{if(c!=null)c.close();}
        return result==null?"dosya":result;
    }

    private void startLearning(Module m) {
        AgentAccessibilityService.beginLearning(this,m.id,m.targetPackage); launchTarget(m.targetPackage);
        toast("Öğretme başladı. Son yayın/paylaş düğmesine basmadan geri dön.");
    }

    private void runModule(Module m) {
        if (m.mode==Mode.LOCAL_READY) {
            if(taskText.isEmpty()){toast("Önce Agent Script seç veya yapıştır.");return;}
            setTaskText(taskText);
            if(!AgentAccessibilityServiceV4.isConnectedAndHealthy()){
                toast("Erişilebilirlik servisi kapalı veya öz test başarısız. Önce 'Erişilebilirliği Aç' bölümünden Yerel Ajan'ı etkinleştir.");
                return;
            }
            boolean accepted=AgentAccessibilityServiceV4.requestAgentStartFromUi();
            toast(accepted?"Agent Script yürütücüye gönderildi. Ajan başlıyor…":"Agent başlatılamadı. Erişilebilirliği kapatıp yeniden aç ve tekrar dene.");
            return;
        }
        if(m.mode!=Mode.CALIBRATE){toast("Bu modül bu sürümde yalnızca yol haritasında.");return;}
        if(!AgentAccessibilityService.hasCalibration(this,m.id)){toast("Önce görevi bir kez öğret.");return;}
        if(m.needsText&&taskText.isEmpty()){toast("Önce metin kaynağını seç.");return;}
        JSONArray files=new JSONArray(); for(String s:selectedFiles)files.put(s);
        AgentAccessibilityService.beginRun(this,m.id,m.targetPackage,taskText,files.toString()); launchTarget(m.targetPackage);
        toast("Ajan başladı. Son kritik düğmeden önce duracak.");
    }

    private void launchTarget(String packageName) {
        if(packageName==null||packageName.isEmpty()){toast("Bu modül için hedef uygulama tanımlı değil.");return;}
        Intent launch=getPackageManager().getLaunchIntentForPackage(packageName);
        if(launch==null&&"com.android.chrome".equals(packageName)){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com")));return;}
        if(launch==null){toast("Hedef uygulama bulunamadı: "+packageName);return;}
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch);
    }

    private void refreshLearningBanner() {
        if(learningBanner==null)return; String module=AgentAccessibilityService.learningModule(this);
        if(module==null||module.isEmpty()){learningBanner.setVisibility(View.GONE);return;}
        Module m=moduleById(module); learningBanner.setText("Öğretme açık: "+(m==null?module:m.name)+"  •  ÖĞRETMEYİ BİTİR"); learningBanner.setVisibility(View.VISIBLE);
        learningBanner.setOnClickListener(v->{AgentAccessibilityService.finishLearning(this);toast("Öğretme bitti. Kaydedilen adımlar artık çalıştırılabilir.");refreshLearningBanner();renderModules();});
    }

    private void showSecurityDialog() {
        new AlertDialog.Builder(this).setTitle("Güvenlik tasarımı")
                .setMessage("• Manifestte INTERNET izni yok.\n• SMS, OTP, rehber, kamera, mikrofon ve bildirim okuma izni yok.\n• Dosya erişimi yalnızca Android dosya seçicisiyle senin seçtiğin dosyalara yapılır.\n• Erişilebilirlik yalnızca Chrome, Instagram, Canva ve Android dosya seçici paketleriyle sınırlandırılmıştır.\n• Öğretme sırasında yazdığın gerçek form metni kaydedilmez.\n• Yayınla, Paylaş, Gönder, Publish, Share, Send gibi nihai eylemler sert engellidir.\n\nHiçbir yazılım yüzde 100 güvenlik garantisi veremez; bu sürüm risk yüzeyini özellikle dar tutar.")
                .setPositiveButton("Tamam",null).show();
    }

    private static List<Module> modules() {
        ArrayList<Module> m=new ArrayList<>();
        m.add(new Module(1,"baun_publish","BAUN Duyuru Hazırla","Pano/DOCX/TXT metnini alır; görsel/PDF ekleriyle paneli öğrettiğin akışta doldurur ve Yayınla öncesinde durur.",Mode.CALIBRATE,"com.android.chrome",true,true,true));
        m.add(new Module(2,"instagram_post","Instagram Gönderisi Hazırla","Görselleri ve açıklamayı hazırlar; Instagram'da Paylaş düğmesine basmaz.",Mode.CALIBRATE,"com.instagram.android",true,true,false));
        m.add(new Module(3,"canva_poster","Canva Afiş Hazırla","Şablon tabanlı Canva akışını tekrarlar; ilk sürümde yaratıcı karar yerine öğretilmiş tasarım şablonunu kullanır.",Mode.CALIBRATE,"com.canva.editor",true,true,false));
        m.add(new Module(4,"canva_check","Canva Tasarım Kontrolü","Öğretilmiş kontrol akışını tekrarlar; görsel yapay zekâ değerlendirmesi bu sürümde yok.",Mode.CALIBRATE,"com.canva.editor",false,false,false));
        m.add(new Module(5,"baun_update","BAUN Duyuru Güncelle","Mevcut duyuruda öğretilmiş alanları günceller; son Güncelle/Yayınla adımı sende.",Mode.CALIBRATE,"com.android.chrome",true,true,true));
        m.add(new Module(6,"baun_en","BAUN İngilizce Sayfa Hazırla","Hazır İngilizce metni ilgili sayfaya yerleştirir; bu sürüm kendi başına çeviri üretmez.",Mode.CALIBRATE,"com.android.chrome",true,true,true));
        m.add(new Module(7,"instagram_carousel","Instagram Carousel Hazırla","Birden fazla görsel + açıklama için öğretilmiş akışı yürütür, Paylaş öncesinde durur.",Mode.CALIBRATE,"com.instagram.android",true,true,false));
        m.add(new Module(8,"instagram_story","Instagram Story Hazırla","Seçilen görseli Story akışına taşır; son paylaşımı sen yaparsın.",Mode.CALIBRATE,"com.instagram.android",false,true,false));
        m.add(new Module(9,"instagram_reel","Instagram Reel Hazırla","Video/görsel ve açıklama akışını öğretilmiş biçimde hazırlar; nihai paylaşım sende.",Mode.CALIBRATE,"com.instagram.android",true,true,false));
        m.add(new Module(10,"web_table","Web Sayfasını Tabloya Çevir","Görünür tarayıcı otomasyonu tasarlandı; gerçek XLSX üretme motoru bu sürümde henüz yok.",Mode.PLANNED,"com.android.chrome",false,false,false));
        m.add(new Module(11,"universities","Türkiye Üniversitelerini Tara","Çok sayfalı tarama için ayrı güvenli web işçisi gerekir; çekirdek APK internetsizdir.",Mode.PLANNED,"com.android.chrome",true,false,false));
        m.add(new Module(12,"academics","Akademisyen Araştır","Kamuya açık profil taraması + tablo çıktısı için web işçisi/Excel motoru sonraki sürümdedir.",Mode.PLANNED,"com.android.chrome",true,false,false));
        m.add(new Module(13,"excel_clean","Excel Temizle","Excel dosya motoru bu ilk güvenli APK'da henüz eklenmedi; buton sahte işlem yapmaz.",Mode.PLANNED,"",false,false,false));
        m.add(new Module(14,"excel_compare","İki Excel'i Karşılaştır","Karşılaştırma motoru sonraki sürüm için ayrıldı; mevcut sürüm dosyanı değiştirmez.",Mode.PLANNED,"",false,false,false));
        m.add(new Module(15,"word_cover","Word Üst Yazı Hazırla","DOCX yazma/şablon motoru henüz eklenmedi; metin kaynağı altyapısı hazır.",Mode.PLANNED,"",true,false,false));
        m.add(new Module(16,"docx_edit","DOCX Düzenle","DOCX okuma çalışır; güvenli biçim koruyan yazma motoru sonraki sürümde eklenecek.",Mode.PLANNED,"",true,false,false));
        m.add(new Module(17,"pdf_extract","PDF'den Veri Çıkar","PDF metin/tablo çıkarma motoru bu sürümde yok; PDF ek dosya olarak seçilebilir.",Mode.PLANNED,"",false,false,true));
        m.add(new Module(18,"rename_files","Dosya Adlarını Standardize Et","Android SAF ile güvenli yeniden adlandırma akışı sonraki sürümdedir.",Mode.PLANNED,"",true,false,false));
        m.add(new Module(19,"link_check","Link Kontrolü","Doğrudan HTTP isteği yapmamak için çekirdek internetsizdir; tarayıcı kontrolü sonraki sürümdedir.",Mode.PLANNED,"com.android.chrome",true,false,false));
        m.add(new Module(20,"agent_task","Özel Agent Görevi Çalıştır","Genel AGENT/1–3 görev kodlarını parse eder ve yerel güvenli yürütücüde çalıştırır.",Mode.LOCAL_READY,"",true,false,false));
        return m;
    }

    private Module moduleById(String id){for(Module m:modules())if(m.id.equals(id))return m;return null;}
    enum Mode{CALIBRATE,LOCAL_READY,PLANNED}
    static class Module{
        final int index;final String id,name,desc,targetPackage;final Mode mode;final boolean needsText,media,pdf;
        Module(int index,String id,String name,String desc,Mode mode,String targetPackage,boolean needsText,boolean media,boolean pdf){this.index=index;this.id=id;this.name=name;this.desc=desc;this.mode=mode;this.targetPackage=targetPackage;this.needsText=needsText;this.media=media;this.pdf=pdf;}
    }

    private TextView text(String s,float sp,boolean bold,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setLineSpacing(0,1.08f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setTextColor(TEXT);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setPadding(dp(10),0,dp(10),0);b.setBackground(roundRect(Color.WHITE,dp(12),Color.rgb(207,212,220),1));return b;}
    private GradientDrawable roundRect(int fill,int radius,int stroke,int strokeDp){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(radius);if(strokeDp>0)g.setStroke(dp(strokeDp),stroke);return g;}
    private LinearLayout.LayoutParams lp(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
