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
import android.os.ParcelFileDescriptor;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Sağlam model kurulum ekranı.
 * :web sürecinde çalışır. Model dosyası kopyalanırken gerçek ilerleme, iptal ve stall-timeout vardır.
 */
public class ModelSetupActivityV2 extends Activity {
    private static final int PICK_MODEL=5217;
    private static final long STALL_TIMEOUT_MS=45_000L;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button download, importFile, cancel, close;
    private LocalModelRegistry.Model model;
    private long downloadId=-1;
    private int attemptsForModel=0, fallbackCount=0;

    private volatile boolean importing=false, cancelImport=false, importTimedOut=false;
    private volatile long lastProgressAt=0L;
    private volatile ParcelFileDescriptor activePfd;
    private volatile File activePart;

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
        TextView info=tv("Doğal dil görevlerini AGENT/3 planına çevirmek için public Qwen LiteRT-LM modeli kullanılır. İndirme ve dosyadan içe aktarma gerçek ilerleme ve zaman aşımıyla izlenir.",14,false);
        info.setPadding(0,dp(12),0,dp(12));root.addView(info);
        status=tv("",15,true);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(8));pp.topMargin=dp(16);root.addView(progress,pp);

        download=button("Önerilen modeli indir");download.setOnClickListener(v->startDownload(true));root.addView(download,buttonLp(18));
        importFile=button("İndirilmiş .litertlm dosyasını içe aktar");importFile.setOnClickListener(v->pickModelFile());root.addView(importFile,buttonLp(8));
        cancel=button("İşlemi iptal et");cancel.setOnClickListener(v->cancelCurrent());cancel.setVisibility(Button.GONE);root.addView(cancel,buttonLp(8));
        close=button("Kapat");close.setOnClickListener(v->finish());root.addView(close,buttonLp(8));
        setContentView(root);
    }

    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private LinearLayout.LayoutParams buttonLp(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));p.topMargin=dp(top);return p;}

    private void refresh(){
        LocalModelRegistry.Model installed=LocalModelRegistry.strongestInstalled(this);
        if(installed!=null){
            model=installed;status.setText("✓ "+LocalModelRegistry.status(this));progress.setIndeterminate(false);progress.setProgress(100);
            download.setText("Model kurulu");download.setEnabled(false);importFile.setEnabled(true);cancel.setVisibility(Button.GONE);return;
        }
        model=LocalModelRegistry.preferred(this);
        double gb=model.expectedBytes/(1024d*1024d*1024d);
        status.setText(LocalModelRegistry.status(this)+"\nİndirme: yaklaşık "+String.format(java.util.Locale.US,"%.2f",gb)+" GB");
        progress.setIndeterminate(false);progress.setProgress(0);download.setText("Önerilen modeli indir");download.setEnabled(true);importFile.setEnabled(true);cancel.setVisibility(Button.GONE);
    }

    private void startDownload(boolean reset){
        if(importing)return;
        if(reset){attemptsForModel=0;fallbackCount=0;model=LocalModelRegistry.preferred(this);}
        enqueueCurrent();
    }

    private void enqueueCurrent(){
        if(LocalModelRegistry.looksInstalled(this,model)){refresh();return;}
        if(!LocalModelRegistry.enoughFreeSpace(this,model)){
            status.setText("Yeterli boş alan yok. Model + çalışma alanı için yaklaşık "+String.format(java.util.Locale.US,"%.1f",(model.expectedBytes+1073741824L)/(1024d*1024d*1024d))+" GB boş alan gerekli.");return;
        }
        try{
            File out=LocalModelRegistry.file(this,model);if(out.exists())out.delete();
            DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);if(dm==null)throw new IllegalStateException("Android indirme yöneticisi yok.");
            DownloadManager.Request r=new DownloadManager.Request(Uri.parse(model.url));
            r.setTitle("Yerel Ajan • "+model.name);r.setDescription("Public Qwen LiteRT-LM indiriliyor");
            r.setAllowedOverMetered(true);r.setAllowedOverRoaming(false);r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setMimeType("application/octet-stream");r.addRequestHeader("User-Agent","YerelAjan/1.0.2 Android");r.addRequestHeader("Accept","application/octet-stream,*/*");
            r.setDestinationInExternalFilesDir(this,"models",model.filename);
            attemptsForModel++;downloadId=dm.enqueue(r);
            download.setEnabled(false);importFile.setEnabled(false);cancel.setVisibility(Button.VISIBLE);progress.setIndeterminate(false);progress.setProgress(0);
            status.setText(model.name+" indiriliyor • deneme "+attemptsForModel);pollDownload();
        }catch(Exception e){status.setText("İndirme başlatılamadı: "+message(e));download.setEnabled(true);importFile.setEnabled(true);cancel.setVisibility(Button.GONE);}
    }

    private final Runnable downloadPoller=this::pollDownload;
    private void pollDownload(){
        if(downloadId<0)return;DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);if(dm==null)return;Cursor c=null;
        try{
            c=dm.query(new DownloadManager.Query().setFilterById(downloadId));if(c==null||!c.moveToFirst()){ui.postDelayed(downloadPoller,1200);return;}
            int st=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));long done=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if(total>0)progress.setProgress((int)Math.min(99,done*100L/total));
            if(st==DownloadManager.STATUS_SUCCESSFUL){downloadId=-1;cancel.setVisibility(Button.GONE);if(LocalModelRegistry.looksInstalled(this,model)){status.setText("✓ "+model.name+" indirildi ve doğrulandı.");progress.setProgress(100);download.setText("Model kuruldu");download.setEnabled(false);importFile.setEnabled(true);}else handleDownloadFailure(-2,"indirilen dosya boyutu modelle uyuşmuyor");return;}
            if(st==DownloadManager.STATUS_FAILED){int reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));downloadId=-1;handleDownloadFailure(reason,reasonText(reason));return;}
            status.setText(model.name+" indiriliyor • "+format(done)+(total>0?" / "+format(total):"")+" • %"+(total>0?(done*100/total):0));ui.postDelayed(downloadPoller,1100);
        }catch(Exception e){ui.postDelayed(downloadPoller,1600);}finally{if(c!=null)c.close();}
    }

    private void handleDownloadFailure(int reason,String detail){
        deletePartial(model);boolean auth=reason==401||reason==403||reason==404;
        if(!auth&&attemptsForModel<2){status.setText(model.name+" başarısız: "+detail+" • otomatik yeniden deneniyor");ui.postDelayed(this::enqueueCurrent,900);return;}
        LocalModelRegistry.Model next=LocalModelRegistry.fallback(model);
        if(next!=null&&fallbackCount<2){fallbackCount++;model=next;attemptsForModel=0;status.setText("İndirme başarısız: "+detail+"\nDaha hafif modele geçiliyor: "+model.name);ui.postDelayed(this::enqueueCurrent,1100);return;}
        progress.setProgress(0);download.setEnabled(true);importFile.setEnabled(true);cancel.setVisibility(Button.GONE);status.setText("Model indirilemedi: "+detail+". Dosyadan içe aktarmayı da kullanabilirsin.");
    }

    private void pickModelFile(){
        if(importing)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        startActivityForResult(i,PICK_MODEL);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_MODEL||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        importing=true;cancelImport=false;importTimedOut=false;lastProgressAt=android.os.SystemClock.elapsedRealtime();
        download.setEnabled(false);importFile.setEnabled(false);cancel.setVisibility(Button.VISIBLE);progress.setIndeterminate(true);status.setText("Model dosyası açılıyor…");
        ui.postDelayed(stallWatchdog,5000);
        new Thread(()->importUri(uri),"agent-model-import").start();
    }

    private final Runnable stallWatchdog=new Runnable(){@Override public void run(){
        if(!importing)return;long idle=android.os.SystemClock.elapsedRealtime()-lastProgressAt;
        if(idle>STALL_TIMEOUT_MS){importTimedOut=true;cancelImport=true;closeActivePfd();status.setText("Dosya sağlayıcısı 45 saniyedir veri göndermiyor. İşlem durduruluyor…");return;}
        ui.postDelayed(this,5000);
    }};

    private void importUri(Uri uri){
        File part=null;
        try{
            ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(uri,"r");activePfd=pfd;if(pfd==null)throw new IllegalStateException("Seçilen dosya açılamadı.");
            long total=pfd.getStatSize();if(total<=0)total=-1;
            LocalModelRegistry.Model target=LocalModelRegistry.closestForSize(total,LocalModelRegistry.preferred(this));if(target==null)target=LocalModelRegistry.preferred(this);
            final LocalModelRegistry.Model targetFinal=target;final long totalFinal=total;
            if(total>0&&!LocalModelRegistry.sizeMatches(target,total))throw new IllegalArgumentException("Dosya boyutu desteklenen Qwen model paketleriyle uyuşmuyor: "+format(total));
            long need=(total>0?total:target.expectedBytes)+512L*1024L*1024L;if(LocalModelRegistry.modelDir(this).getUsableSpace()<need)throw new IllegalStateException("Modeli içe aktarmak için yeterli boş alan yok.");
            part=new File(LocalModelRegistry.modelDir(this),target.filename+".part");activePart=part;if(part.exists())part.delete();
            File finalOut=LocalModelRegistry.file(this,target);if(finalOut.exists())finalOut.delete();
            ui.post(()->{progress.setIndeterminate(totalFinal<=0);progress.setProgress(0);status.setText(targetFinal.name+" içe aktarılıyor • 0.00 GB"+(totalFinal>0?" / "+format(totalFinal):""));});

            long copied=0,lastUi=0;byte[] buf=new byte[4*1024*1024];
            try(InputStream in=new FileInputStream(pfd.getFileDescriptor());FileOutputStream out=new FileOutputStream(part)){
                while(!cancelImport){int n=in.read(buf);if(n<0)break;if(n==0)continue;out.write(buf,0,n);copied+=n;lastProgressAt=android.os.SystemClock.elapsedRealtime();
                    long now=lastProgressAt;if(now-lastUi>300){lastUi=now;final long c=copied;ui.post(()->{status.setText(targetFinal.name+" içe aktarılıyor • "+format(c)+(totalFinal>0?" / "+format(totalFinal)+" • %"+Math.min(99,c*100/totalFinal):""));if(totalFinal>0)progress.setProgress((int)Math.min(99,c*100/totalFinal));});}
                }
                out.getFD().sync();
            }
            closeActivePfd();
            if(cancelImport)throw new IllegalStateException(importTimedOut?"Dosya okuma zaman aşımına uğradı.":"İşlem iptal edildi.");
            if(!LocalModelRegistry.sizeMatches(target,copied))throw new IllegalStateException("Kopyalanan model boyutu doğrulanamadı: "+format(copied));
            if(!part.renameTo(finalOut)){
                try(FileInputStream in=new FileInputStream(part);FileOutputStream out=new FileOutputStream(finalOut)){byte[] b=new byte[4*1024*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);out.getFD().sync();}
                part.delete();
            }
            if(!LocalModelRegistry.looksInstalled(this,target))throw new IllegalStateException("Son model doğrulaması başarısız.");
            model=target;finishImportSuccess(target);
        }catch(Exception e){if(part!=null)part.delete();finishImportError(message(e));}finally{closeActivePfd();activePart=null;}
    }

    private void finishImportSuccess(LocalModelRegistry.Model target){ui.post(()->{importing=false;cancelImport=false;ui.removeCallbacks(stallWatchdog);progress.setIndeterminate(false);progress.setProgress(100);status.setText("✓ "+target.name+" doğrulandı ve hazır.");download.setText("Model kuruldu");download.setEnabled(false);importFile.setEnabled(true);cancel.setVisibility(Button.GONE);});}
    private void finishImportError(String m){ui.post(()->{importing=false;ui.removeCallbacks(stallWatchdog);progress.setIndeterminate(false);progress.setProgress(0);status.setText("Model içe aktarılamadı: "+m);download.setText("Tekrar indir");download.setEnabled(true);importFile.setEnabled(true);cancel.setVisibility(Button.GONE);});}

    private void cancelCurrent(){
        if(importing){cancelImport=true;closeActivePfd();status.setText("İçe aktarma iptal ediliyor…");return;}
        if(downloadId>=0){DownloadManager dm=(DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE);if(dm!=null)dm.remove(downloadId);downloadId=-1;deletePartial(model);status.setText("İndirme iptal edildi.");download.setEnabled(true);importFile.setEnabled(true);cancel.setVisibility(Button.GONE);progress.setProgress(0);}
    }

    private void closeActivePfd(){ParcelFileDescriptor p=activePfd;activePfd=null;if(p!=null)try{p.close();}catch(Exception ignored){}}
    private void deletePartial(LocalModelRegistry.Model m){try{File f=LocalModelRegistry.file(this,m);if(f.exists())f.delete();}catch(Exception ignored){}File p=activePart;if(p!=null&&p.exists())p.delete();}

    private String reasonText(int r){if(r==401)return "HTTP 401 Yetkisiz";if(r==403)return "HTTP 403 Erişim reddedildi";if(r==404)return "HTTP 404 Dosya bulunamadı";if(r>=400&&r<600)return "HTTP "+r;if(r==DownloadManager.ERROR_INSUFFICIENT_SPACE)return "yetersiz depolama";if(r==DownloadManager.ERROR_CANNOT_RESUME)return "indirme sürdürülemedi";if(r==DownloadManager.ERROR_HTTP_DATA_ERROR)return "HTTP veri hatası";return "Android hata "+r;}
    private String format(long n){return String.format(java.util.Locale.US,"%.2f GB",n/(1024d*1024d*1024d));}
    private TextView tv(String s,float size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(28,31,36));if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}

    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);cancelImport=true;closeActivePfd();super.onDestroy();}
}
