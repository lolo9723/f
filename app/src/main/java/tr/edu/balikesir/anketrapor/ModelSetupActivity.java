package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Yalnız :web prosesinde açılır; model dosyasını indirir. Accessibility veya pano kullanmaz. */
public class ModelSetupActivity extends Activity {
    private static final int PICK_MODEL=4217;
    private final Handler h=new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button download, importFile;
    private long downloadId=-1;
    private LocalModelRegistry.Model model;
    private int attemptsForModel=0, fallbackCount=0;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        model=LocalModelRegistry.preferred(this);
        buildUi();
        refresh();
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(24),dp(20),dp(20));
        root.setBackgroundColor(Color.WHITE);
        root.addView(tv("Yerel Ajan • Güçlü Yerel Model",22,true));
        TextView info=tv("Ajan görevlerini doğal dilden AGENT planına çevirmek için cihazına uygun en güçlü public Qwen modelini kurar. Model ayrı dosyadır; APK'nın içine gömülmez ve planlayıcı prosesinin internet erişimi yoktur.",14,false);
        info.setPadding(0,dp(12),0,dp(12));root.addView(info);
        status=tv("",15,true);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(8));pp.topMargin=dp(16);root.addView(progress,pp);

        download=new Button(this);download.setAllCaps(false);download.setText("Önerilen modeli indir");download.setOnClickListener(v->startDownload(true));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));bp.topMargin=dp(18);root.addView(download,bp);

        importFile=new Button(this);importFile.setAllCaps(false);importFile.setText("İndirilmiş .litertlm dosyasını içe aktar");importFile.setOnClickListener(v->pickModelFile());
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));ip.topMargin=dp(8);root.addView(importFile,ip);

        Button close=new Button(this);close.setAllCaps(false);close.setText("Kapat");close.setOnClickListener(v->finish());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));cp.topMargin=dp(8);root.addView(close,cp);
        setContentView(root);
    }

    private void refresh(){
        LocalModelRegistry.Model installed=LocalModelRegistry.strongestInstalled(this);
        if(installed!=null){
            model=installed;status.setText("✓ "+LocalModelRegistry.status(this));progress.setProgress(100);download.setText("Model kurulu");download.setEnabled(false);importFile.setEnabled(true);return;
        }
        model=LocalModelRegistry.preferred(this);
        double gb=model.expectedBytes/(1024d*1024d*1024d);
        status.setText(LocalModelRegistry.status(this)+"\nİndirme: yaklaşık "+String.format(java.util.Locale.US,"%.1f",gb)+" GB\nKaynak: public Apache-2.0 Qwen LiteRT-LM");
        download.setEnabled(true);download.setText("Önerilen modeli indir");importFile.setEnabled(true);progress.setProgress(0);
    }

    private void startDownload(boolean reset){
        if(reset){attemptsForModel=0;fallbackCount=0;model=LocalModelRegistry.preferred(this);}
        enqueueCurrent();
    }

    private void enqueueCurrent(){
        if(LocalModelRegistry.looksInstalled(this,model)){refresh();return;}
        if(!LocalModelRegistry.enoughFreeSpace(this,model)){
            status.setText("Yeterli boş alan yok. "+model.name+" ve çalışma önbelleği için yaklaşık "+String.format(java.util.Locale.US,"%.1f",(model.expectedBytes+1073741824L)/(1024d*1024d*1024d))+" GB boş alan gerekli.");
            download.setEnabled(true);download.setText("Tekrar dene");return;
        }
        try{
            File f=LocalModelRegistry.file(this,model);
            if(f.exists()&&!f.delete()){status.setText("Eski/eksik model dosyası silinemedi.");return;}
            DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
            if(dm==null){status.setText("Android indirme yöneticisi bulunamadı.");return;}
            DownloadManager.Request r=new DownloadManager.Request(Uri.parse(model.url));
            r.setTitle("Yerel Ajan • "+model.name);
            r.setDescription("Public Qwen LiteRT-LM modeli indiriliyor");
            r.setAllowedOverMetered(true);r.setAllowedOverRoaming(false);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setMimeType("application/octet-stream");
            r.addRequestHeader("User-Agent","YerelAjan/1.0.1 Android");
            r.addRequestHeader("Accept","application/octet-stream,*/*");
            r.setDestinationInExternalFilesDir(this,"models",model.filename);
            attemptsForModel++;
            downloadId=dm.enqueue(r);
            download.setEnabled(false);download.setText("İndiriliyor…");importFile.setEnabled(false);
            status.setText(model.name+" indiriliyor • deneme "+attemptsForModel+"\nİndirme arka planda devam edebilir.");
            poll();
        }catch(Exception e){
            download.setEnabled(true);download.setText("Tekrar dene");importFile.setEnabled(true);
            status.setText("İndirme başlatılamadı: "+message(e));
        }
    }

    private final Runnable poller=new Runnable(){@Override public void run(){poll();}};
    private void poll(){
        if(downloadId<0)return;
        DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);if(dm==null)return;
        Cursor c=null;
        try{
            c=dm.query(new DownloadManager.Query().setFilterById(downloadId));
            if(c==null||!c.moveToFirst()){h.postDelayed(poller,1500);return;}
            int st=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            long done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if(total>0)progress.setProgress((int)Math.min(99,done*100L/total));
            if(st==DownloadManager.STATUS_SUCCESSFUL){
                downloadId=-1;
                if(LocalModelRegistry.looksInstalled(this,model)){
                    progress.setProgress(100);status.setText("✓ "+model.name+" hazır. Planlayıcı artık çevrimdışı çalışabilir.");download.setText("Model kuruldu");download.setEnabled(false);importFile.setEnabled(true);
                }else{
                    handleFailure(-2,"İndirme tamamlandı fakat dosya boyutu model paketiyle uyuşmuyor.");
                }
                return;
            }
            if(st==DownloadManager.STATUS_FAILED){
                int reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));downloadId=-1;
                handleFailure(reason,reasonText(reason));return;
            }
            status.setText(model.name+" indiriliyor • "+formatBytes(done)+(total>0?" / "+formatBytes(total):"")+"\nDeneme "+attemptsForModel);
            h.postDelayed(poller,1300);
        }catch(Exception e){h.postDelayed(poller,1800);}finally{if(c!=null)c.close();}
    }

    private void handleFailure(int reason,String detail){
        deletePartial(model);
        // Aynı public model için bir kez otomatik tekrar. HTTP yetki/erişim hatasında tekrar yerine direkt fallback.
        boolean httpAuth=reason==401||reason==403||reason==404;
        if(!httpAuth&&attemptsForModel<2){
            status.setText(model.name+" indirilemedi ("+detail+"). Aynı model otomatik yeniden deneniyor…");
            h.postDelayed(this::enqueueCurrent,1200);return;
        }
        LocalModelRegistry.Model next=LocalModelRegistry.fallback(model);
        if(next!=null&&fallbackCount<2){
            fallbackCount++;model=next;attemptsForModel=0;
            status.setText("Sunucu/model indirmesi başarısız: "+detail+"\nOtomatik olarak daha hafif public modele geçiliyor: "+model.name);
            h.postDelayed(this::enqueueCurrent,1400);return;
        }
        progress.setProgress(0);download.setEnabled(true);download.setText("Tekrar dene");importFile.setEnabled(true);
        String extra=httpAuth?" Sunucu erişim politikası indirmeyi reddetti.":"";
        status.setText("Model indirilemedi: "+detail+"."+extra+"\nAşağıdaki dosyadan içe aktarma seçeneği de kullanılabilir.");
    }

    private void deletePartial(LocalModelRegistry.Model m){try{File f=LocalModelRegistry.file(this,m);if(f.exists())f.delete();}catch(Exception ignored){}}

    private String reasonText(int r){
        if(r==401)return "HTTP 401 Yetkisiz";
        if(r==403)return "HTTP 403 Erişim reddedildi";
        if(r==404)return "HTTP 404 Dosya bulunamadı";
        if(r>=400&&r<600)return "HTTP "+r;
        if(r==DownloadManager.ERROR_INSUFFICIENT_SPACE)return "yetersiz depolama";
        if(r==DownloadManager.ERROR_DEVICE_NOT_FOUND)return "depolama aygıtı bulunamadı";
        if(r==DownloadManager.ERROR_CANNOT_RESUME)return "indirme sürdürülemedi";
        if(r==DownloadManager.ERROR_HTTP_DATA_ERROR)return "HTTP veri hatası";
        if(r==DownloadManager.ERROR_TOO_MANY_REDIRECTS)return "çok fazla yönlendirme";
        if(r==DownloadManager.ERROR_UNHANDLED_HTTP_CODE)return "desteklenmeyen HTTP yanıtı";
        return "Android hata "+r;
    }

    private void pickModelFile(){
        try{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");
            startActivityForResult(i,PICK_MODEL);
        }catch(Exception e){status.setText("Dosya seçici açılamadı: "+message(e));}
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_MODEL||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        status.setText("Model dosyası doğrulanıyor ve içe aktarılıyor…");download.setEnabled(false);importFile.setEnabled(false);progress.setIndeterminate(true);
        Thread t=new Thread(()->importUri(uri));t.start();
    }

    private void importUri(Uri uri){
        try{
            long size=querySize(uri);
            LocalModelRegistry.Model target=LocalModelRegistry.closestForSize(size,LocalModelRegistry.preferred(this));
            if(target==null)throw new IllegalArgumentException("Seçilen dosya desteklenen Qwen LiteRT-LM boyutlarıyla uyuşmuyor.");
            File out=LocalModelRegistry.file(this,target);if(out.exists())out.delete();
            try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream fos=new FileOutputStream(out)){
                if(in==null)throw new IllegalStateException("Dosya açılamadı.");byte[] buf=new byte[1024*1024];int n;long copied=0;
                while((n=in.read(buf))>0){fos.write(buf,0,n);copied+=n;final long x=copied;runOnUiThread(()->status.setText(target.name+" içe aktarılıyor • "+formatBytes(x)));}
                fos.flush();
            }
            if(!LocalModelRegistry.looksInstalled(this,target)){out.delete();throw new IllegalStateException("Dosya boyutu doğrulaması başarısız.");}
            model=target;
            runOnUiThread(()->{progress.setIndeterminate(false);progress.setProgress(100);status.setText("✓ "+target.name+" içe aktarıldı ve hazır.");download.setText("Model kuruldu");download.setEnabled(false);importFile.setEnabled(true);});
        }catch(Exception e){runOnUiThread(()->{progress.setIndeterminate(false);progress.setProgress(0);status.setText("Model içe aktarılamadı: "+message(e));download.setEnabled(true);download.setText("Tekrar dene");importFile.setEnabled(true);});}
    }

    private long querySize(Uri uri){
        Cursor c=null;try{c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null);if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.SIZE);if(i>=0&&!c.isNull(i))return c.getLong(i);}}catch(Exception ignored){}finally{if(c!=null)c.close();}return -1L;
    }

    private String formatBytes(long n){return String.format(java.util.Locale.US,"%.2f GB",n/(1024d*1024d*1024d));}
    private TextView tv(String s,float size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(28,31,36));if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    @Override protected void onDestroy(){h.removeCallbacksAndMessages(null);super.onDestroy();}
}
