package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

/** Yalnız :web prosesinde açılır; model dosyasını indirir. Accessibility veya pano kullanmaz. */
public class ModelSetupActivity extends Activity {
    private final Handler h=new Handler(Looper.getMainLooper());
    private TextView status; private ProgressBar progress; private Button download;
    private long downloadId=-1; private LocalModelRegistry.Model model;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); model=LocalModelRegistry.preferred(this); buildUi(); refresh();
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(24),dp(20),dp(20));root.setBackgroundColor(Color.WHITE);
        TextView title=tv("Yerel Ajan • Güçlü Yerel Model",22,true);root.addView(title);
        TextView info=tv("Ajan görevlerini doğal dilden AGENT planına çevirmek için cihazına uygun en güçlü modeli kurar. Model ayrı dosyadır; APK'nın içine gömülmez ve planlayıcı prosesinin internet erişimi yoktur.",14,false);info.setPadding(0,dp(12),0,dp(12));root.addView(info);
        status=tv("",15,true);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(8));pp.topMargin=dp(16);root.addView(progress,pp);
        download=new Button(this);download.setAllCaps(false);download.setText("Önerilen modeli indir");download.setOnClickListener(v->startDownload());LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));bp.topMargin=dp(18);root.addView(download,bp);
        Button close=new Button(this);close.setAllCaps(false);close.setText("Kapat");close.setOnClickListener(v->finish());LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));cp.topMargin=dp(8);root.addView(close,cp);
        setContentView(root);
    }

    private void refresh(){
        model=LocalModelRegistry.preferred(this);
        if(LocalModelRegistry.strongestInstalled(this)!=null){status.setText("✓ "+LocalModelRegistry.status(this));progress.setProgress(100);download.setText("Model kurulu");download.setEnabled(false);return;}
        double gb=model.expectedBytes/(1024d*1024d*1024d);
        status.setText(LocalModelRegistry.status(this)+"\nİndirme: yaklaşık "+String.format(java.util.Locale.US,"%.1f",gb)+" GB");
        download.setEnabled(true);progress.setProgress(0);
    }

    private void startDownload(){
        if(LocalModelRegistry.looksInstalled(this,model)){refresh();return;}
        if(!LocalModelRegistry.enoughFreeSpace(this,model)){status.setText("Yeterli boş alan yok. Model ve çalışma önbelleği için en az yaklaşık "+String.format(java.util.Locale.US,"%.1f",(model.expectedBytes+805306368L)/(1024d*1024d*1024d))+" GB boş alan gerekli.");return;}
        try{
            File f=LocalModelRegistry.file(this,model);if(f.exists()&&!f.delete()){status.setText("Eski/eksik model dosyası silinemedi.");return;}
            DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);
            if(dm==null){status.setText("Android indirme yöneticisi bulunamadı.");return;}
            DownloadManager.Request r=new DownloadManager.Request(Uri.parse(model.url));r.setTitle("Yerel Ajan • "+model.name);r.setDescription("Yerel model indiriliyor");r.setAllowedOverMetered(true);r.setAllowedOverRoaming(false);r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);r.setDestinationInExternalFilesDir(this,"models",model.filename);
            downloadId=dm.enqueue(r);download.setEnabled(false);download.setText("İndiriliyor…");status.setText(model.name+" indiriliyor. Uygulamayı açık tutmak zorunda değilsin.");poll();
        }catch(Exception e){download.setEnabled(true);download.setText("Tekrar dene");status.setText("İndirme başlatılamadı: "+message(e));}
    }

    private final Runnable poller=new Runnable(){@Override public void run(){poll();}};
    private void poll(){
        if(downloadId<0)return;DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);if(dm==null)return;
        Cursor c=null;try{
            c=dm.query(new DownloadManager.Query().setFilterById(downloadId));if(c==null||!c.moveToFirst()){h.postDelayed(poller,1500);return;}
            int statusCode=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));long done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if(total>0)progress.setProgress((int)Math.min(99,done*100L/total));
            if(statusCode==DownloadManager.STATUS_SUCCESSFUL){downloadId=-1;if(LocalModelRegistry.looksInstalled(this,model)){progress.setProgress(100);status.setText("✓ "+model.name+" hazır. Planlayıcı artık çevrimdışı çalışabilir.");download.setText("Model kuruldu");download.setEnabled(false);}else{status.setText("İndirme tamamlandı fakat dosya boyutu doğrulanamadı. Tekrar indir.");download.setText("Tekrar indir");download.setEnabled(true);}return;}
            if(statusCode==DownloadManager.STATUS_FAILED){int reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));downloadId=-1;status.setText("Model indirilemedi (Android hata "+reason+"). İnternet/depolama durumunu kontrol edip tekrar dene.");download.setText("Tekrar indir");download.setEnabled(true);return;}
            status.setText(model.name+" indiriliyor • "+formatBytes(done)+(total>0?" / "+formatBytes(total):""));h.postDelayed(poller,1300);
        }catch(Exception e){h.postDelayed(poller,1800);}finally{if(c!=null)c.close();}
    }

    private String formatBytes(long n){return String.format(java.util.Locale.US,"%.2f GB",n/(1024d*1024d*1024d));}
    private TextView tv(String s,float size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(28,31,36));if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    @Override protected void onDestroy(){h.removeCallbacksAndMessages(null);super.onDestroy();}
}
