package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Görünür, ayrı :web prosesinde çalışan genel DOM araştırma motoru. */
public class WebResearchActivity extends Activity {
    public static final String EXTRA_SPEC="web_spec", EXTRA_RECEIVER="web_receiver";
    public static final int RESULT_ERROR=-1, RESULT_PARTIAL=0, RESULT_FULL=1;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<String> candidates=new ArrayList<>();
    private final Set<String> seenCandidates=new HashSet<>(), seenDetails=new HashSet<>();
    private final List<String[]> rows=new ArrayList<>();
    private WebView web; private TextView status,counter; private ProgressBar progress; private Button openFile;
    private ResultReceiver receiver; private JSONObject spec; private JSONArray queries,fields,allowedDomains,linkContains,mustContain,mustNotContain;
    private int queryIndex,candidateIndex,pagesVisited,targetCount,maxPages; private String filename,lastPath="",phase="idle";
    private boolean pageHandled,usingFallback,finished,resultSent;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); receiver=getIntent().getParcelableExtra(EXTRA_RECEIVER);
        try{
            String raw=getIntent().getStringExtra(EXTRA_SPEC);if(raw==null||raw.trim().isEmpty())throw new IllegalArgumentException("Web görev tanımı yok.");
            JSONObject input=new JSONObject(raw);spec=resolveTemplates(input);prepareSpec();buildUi();startResearch();
        }catch(Exception e){buildUi();finishWithError("Görev başlatılamadı: "+msg(e));}
    }

    private JSONObject resolveTemplates(JSONObject input){
        try{
            Bundle snap=getContentResolver().call(VmStateProvider.URI,"snapshot",null,null);
            String raw=snap==null?"{}":snap.getString("json","{}");
            AgentVm vm=new AgentVm();vm.restore(new JSONObject(raw));
            return AgentTemplateResolver.resolveObject(input,vm);
        }catch(Exception e){return input;}
    }

    private void prepareSpec(){
        queries=arr(spec.optJSONArray("queries")); if(queries.length()==0)throw new IllegalArgumentException("Arama sorgusu yok.");
        fields=arr(spec.optJSONArray("fields"));allowedDomains=arr(spec.optJSONArray("allowed_domains"));linkContains=arr(spec.optJSONArray("link_contains"));mustContain=arr(spec.optJSONArray("must_contain"));mustNotContain=arr(spec.optJSONArray("must_not_contain"));
        targetCount=clamp(spec.optInt("target_count",10),1,50);maxPages=clamp(spec.optInt("max_pages",40),1,120);filename=spec.optString("filename","Yerel_Ajan_Web_Sonuc.xlsx");if(!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx"))filename+=".xlsx";
    }

    private void buildUi(){
        if(status!=null)return;LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.WHITE);
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.VERTICAL);head.setPadding(dp(16),dp(14),dp(16),dp(10));head.addView(tv("Yerel Ajan • Web Araştırma",20,true));
        status=tv("Hazırlanıyor…",14,false);status.setPadding(0,dp(6),0,0);head.addView(status);counter=tv("0 / 0",13,true);counter.setPadding(0,dp(4),0,0);head.addView(counter);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6));pp.topMargin=dp(8);head.addView(progress,pp);
        openFile=new Button(this);openFile.setAllCaps(false);openFile.setText("Excel'i Aç");openFile.setVisibility(View.GONE);openFile.setOnClickListener(v->openOutputFile());LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));bp.topMargin=dp(8);head.addView(openFile,bp);
        root.addView(head,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        web=new WebView(this);WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setLoadsImagesAutomatically(true);ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);ws.setSavePassword(false);ws.setAllowFileAccess(false);ws.setAllowContentAccess(false);CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){Uri u=r==null?null:r.getUrl();return u==null||!isSafePublicUrl(u.toString(),false);}
            @Override public void onPageFinished(WebView v,String url){super.onPageFinished(v,url);if(finished||pageHandled)return;pageHandled=true;handler.postDelayed(()->handleLoaded(url),850);}
        });root.addView(web,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));setContentView(root);
    }

    private void startResearch(){queryIndex=0;candidateIndex=0;pagesVisited=0;rows.clear();candidates.clear();seenCandidates.clear();seenDetails.clear();usingFallback=false;finished=false;lastPath="";updateCounter();loadSearch();}
    private void loadSearch(){
        if(finished)return;if(rows.size()>=targetCount){finishSuccess();return;}if(pagesVisited>=maxPages){finishPartial("Maksimum sayfa sınırına ulaşıldı.");return;}
        if(queryIndex>=queries.length()){if(!usingFallback&&spec.optBoolean("allow_search_fallback",true)){usingFallback=true;queryIndex=0;}else{finishPartial("Arama sorguları tamamlandı.");return;}}
        String q=queries.optString(queryIndex++,"").trim();if(q.isEmpty()){loadSearch();return;}candidates.clear();candidateIndex=0;
        String url;if(!usingFallback){url="https://www.google.com/search?num=20&filter=0&q="+Uri.encode(q);setStatus("Google'da aranıyor: "+shortText(q,62));}else{url="https://www.bing.com/search?count=30&q="+Uri.encode(q);setStatus("Yedek arama: "+shortText(q,62));}
        phase="search";load(url);
    }
    private void loadDetail(String url){if(finished)return;if(rows.size()>=targetCount){finishSuccess();return;}if(pagesVisited>=maxPages){finishPartial("Maksimum sayfa sınırına ulaşıldı.");return;}if(!isSafePublicUrl(url,true)){nextCandidate();return;}setStatus("Sayfa doğrulanıyor • "+(rows.size()+1)+"/"+targetCount);phase="detail";load(url);}
    private void load(String url){
        if(finished)return;pageHandled=false;pagesVisited++;updateProgress();web.loadUrl(url);final int stamp=pagesVisited;
        handler.postDelayed(()->{if(!finished&&stamp==pagesVisited&&!pageHandled){pageHandled=true;if("search".equals(phase))loadSearch();else nextCandidate();}},15000);
    }
    private void handleLoaded(String url){if(finished)return;if("search".equals(phase))extractSearchLinks();else if("detail".equals(phase))extractDetail();}

    private void extractSearchLinks(){
        String js="(function(){var r=[],a=document.querySelectorAll('a');for(var i=0;i<a.length;i++){var h=a[i].href||'',t=(a[i].innerText||a[i].textContent||'').trim();if(!h)continue;try{var u=new URL(h,location.href);if((u.hostname.indexOf('google.')>=0||u.hostname==='www.google.com')&&u.pathname==='/url'){var q=u.searchParams.get('q');if(q)h=q;}else h=u.href;}catch(e){}r.push({h:h,t:t});}return JSON.stringify(r);})()";
        web.evaluateJavascript(js,value->{
            try{JSONArray a=new JSONArray(decodeJs(value));for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String h=x.optString("h","");String c=canonical(h);if(isCandidateUrl(h)&&seenCandidates.add(c))candidates.add(h);}}catch(Exception ignored){}
            if(candidates.isEmpty())loadSearch();else{candidateIndex=0;nextCandidate();}
        });
    }
    private void nextCandidate(){if(finished)return;while(candidateIndex<candidates.size()){String u=candidates.get(candidateIndex++),c=canonical(u);if(!seenDetails.add(c))continue;loadDetail(u);return;}loadSearch();}
    private void extractDetail(){
        String js="(function(){return JSON.stringify({url:location.href,title:document.title||'',text:document.body?document.body.innerText:''});})()";
        web.evaluateJavascript(js,value->{try{JSONObject p=new JSONObject(decodeJs(value));evaluatePage(p.optString("url",web.getUrl()),p.optString("title",""),p.optString("text",""));}catch(Exception ignored){}if(rows.size()>=targetCount)finishSuccess();else nextCandidate();});
    }

    private void evaluatePage(String url,String title,String text){
        if(!isCandidateUrl(url)||text==null||text.trim().length()<80)return;String normalized=norm(title+"\n"+text);
        for(int i=0;i<mustContain.length();i++)if(!normalized.contains(norm(mustContain.optString(i))))return;
        String[] dead={"ilan yayında değil","ilan yayinda degil","ilan bulunamadı","ilan bulunamadi","satıldı","satildi","kaldırıldı","kaldirildi","not available","sold out"};for(String b:dead)if(normalized.contains(norm(b)))return;
        for(int i=0;i<mustNotContain.length();i++)if(normalized.contains(norm(mustNotContain.optString(i))))return;
        String[] row=new String[fields.length()+2];row[0]=cleanTitle(title);
        for(int i=0;i<fields.length();i++){JSONObject f=fields.optJSONObject(i);if(f==null)return;String v=extractField(text+"\n"+title,f);if(v==null)return;row[i+1]=v;}
        row[row.length-1]=canonical(url);for(String[] r:rows)if(r[r.length-1].equals(row[row.length-1]))return;rows.add(row);setStatus("Uygun sonuç bulundu: "+rows.size()+"/"+targetCount);updateCounter();
    }

    private String extractField(String text,JSONObject f){
        JSONArray rx=f.optJSONArray("regex");if(rx==null||rx.length()==0)return null;String raw=null;
        for(int i=0;i<rx.length()&&raw==null;i++){try{Matcher m=Pattern.compile(rx.optString(i),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.MULTILINE).matcher(text);if(m.find())raw=m.groupCount()>=1?m.group(1):m.group();}catch(Exception ignored){}}
        if(raw==null||raw.trim().isEmpty())return null;String type=f.optString("type","text");
        if("int".equalsIgnoreCase(type)||"number".equalsIgnoreCase(type)||"decimal".equalsIgnoreCase(type)){
            Double n=parseNumber(raw);if(n==null)return null;if(f.has("min")&&n<f.optDouble("min"))return null;if(f.has("max_exclusive")&&n>=f.optDouble("max_exclusive"))return null;if(f.has("max")&&n>f.optDouble("max"))return null;
            if("int".equalsIgnoreCase(type))return String.valueOf(Math.round(n));return stripNumber(n);
        }
        return raw.trim().replaceAll("\\s+"," ");
    }

    /** Türkçe/İngilizce binlik ve ondalık yazımları; 1,2 bin / 1.2k / 2 milyon gibi kısaltmaları işler. */
    static Double parseNumber(String raw){
        if(raw==null)return null;String lower=raw.toLowerCase(new Locale("tr","TR")).trim();double mult=1d;
        if(lower.matches(".*\\b(milyar|billion|bn)\\b.*"))mult=1_000_000_000d;else if(lower.matches(".*\\b(milyon|million|mn)\\b.*"))mult=1_000_000d;else if(lower.matches(".*\\b(bin|thousand|k)\\b.*"))mult=1_000d;
        Matcher m=Pattern.compile("[-+]?[0-9][0-9.,\\s]*").matcher(lower);if(!m.find())return null;String s=m.group().replace(" ","").trim();if(s.isEmpty())return null;
        int ld=s.lastIndexOf('.'),lc=s.lastIndexOf(',');
        if(ld>=0&&lc>=0){int dec=Math.max(ld,lc);char d=s.charAt(dec);char thousands=d=='.'?',':'.';s=s.replace(String.valueOf(thousands),"");if(d==',')s=s.replace(',','.');}
        else if(lc>=0){int digits=s.length()-lc-1;if(digits==3&&mult==1d&&s.indexOf(',')==lc)s=s.replace(",","");else s=s.replace(',','.');}
        else if(ld>=0){int digits=s.length()-ld-1;if(digits==3&&mult==1d&&s.indexOf('.')==ld)s=s.replace(".","");}
        try{return Double.parseDouble(s)*mult;}catch(Exception e){return null;}
    }
    private static String stripNumber(double n){if(Math.abs(n-Math.rint(n))<1e-9)return String.valueOf((long)Math.rint(n));return String.format(Locale.US,"%.6f",n).replaceAll("0+$","").replaceAll("\\.$","");}

    private boolean isCandidateUrl(String url){if(!isSafePublicUrl(url,true))return false;String l=url.toLowerCase(Locale.ROOT);if(linkContains.length()>0){boolean ok=false;for(int i=0;i<linkContains.length();i++)if(l.contains(linkContains.optString(i).toLowerCase(Locale.ROOT))){ok=true;break;}if(!ok)return false;}return true;}
    private boolean isSafePublicUrl(String url,boolean requireAllowed){
        try{Uri u=Uri.parse(url);String scheme=u.getScheme(),host=u.getHost();if(!"https".equalsIgnoreCase(scheme)||host==null)return false;String h=host.toLowerCase(Locale.ROOT);
            if(h.equals("localhost")||h.endsWith(".local")||h.equals("127.0.0.1")||h.equals("0.0.0.0")||h.equals("::1")||h.matches("10\\..*")||h.matches("192\\.168\\..*")||h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))return false;
            String nh=norm(h);String[] blocked={"bank","banka","garanti","akbank","ziraat","isbank","yapikredi","qnb","halkbank","vakifbank","paypal","stripe","auth","password"};for(String b:blocked)if(nh.contains(norm(b)))return false;
            if(!requireAllowed||allowedDomains.length()==0)return true;String hh=h.replaceFirst("^www\\.","");for(int i=0;i<allowedDomains.length();i++){String d=allowedDomains.optString(i,"").toLowerCase(Locale.ROOT).replaceFirst("^www\\.","");if(hh.equals(d)||hh.endsWith("."+d))return true;}return false;
        }catch(Exception e){return false;}
    }

    private void finishSuccess(){finishToExcel(true,"Hedef tamamlandı: "+rows.size()+"/"+targetCount+" sonuç doğrulandı.");}
    private void finishPartial(String why){finishToExcel(false,why+" Doğrulanan: "+rows.size()+"/"+targetCount+".");}
    private void finishToExcel(boolean full,String message){
        if(finished)return;finished=true;handler.removeCallbacksAndMessages(null);
        try{String[] headers=new String[fields.length()+2];headers[0]="Başlık";for(int i=0;i<fields.length();i++){JSONObject f=fields.optJSONObject(i);headers[i+1]=f==null?"Alan"+(i+1):f.optString("name","Alan"+(i+1));}headers[headers.length-1]="Link";
            lastPath=SimpleXlsxWriter.write(this,filename,headers,rows,headers.length-1);String m=(full?"✓ ":"⚠ ")+message+"\nExcel: "+lastPath;setStatus(m);updateCounter();progress.setProgress(100);openFile.setVisibility(View.VISIBLE);sendResult(full?RESULT_FULL:RESULT_PARTIAL,m);
        }catch(Exception e){finishWithError("Excel oluşturulamadı: "+msg(e));}
    }
    private void finishWithError(String m){finished=true;handler.removeCallbacksAndMessages(null);if(status!=null)status.setText("✕ "+m);if(counter!=null)counter.setText("Görev durdu");if(progress!=null)progress.setProgress(100);sendResult(RESULT_ERROR,m);}
    private JSONArray rowsJson(){JSONArray out=new JSONArray();for(String[] r:rows){JSONObject o=new JSONObject();try{o.put("Başlık",r.length>0?r[0]:"");for(int i=0;i<fields.length();i++){JSONObject f=fields.optJSONObject(i);String name=f==null?"Alan"+(i+1):f.optString("name","Alan"+(i+1));o.put(name,r.length>i+1?r[i+1]:"");}o.put("Link",r.length>0?r[r.length-1]:"");}catch(Exception ignored){}out.put(o);}return out;}
    private void sendResult(int code,String message){if(resultSent||receiver==null)return;resultSent=true;Bundle b=new Bundle();b.putString("message",message);b.putString("filename",filename);b.putString("path",lastPath);b.putInt("found",rows.size());b.putInt("target",targetCount);b.putString("rows_json",rowsJson().toString());try{receiver.send(code,b);}catch(Exception ignored){}}

    private void openOutputFile(){
        try{if(android.os.Build.VERSION.SDK_INT>=29){ContentResolver cr=getContentResolver();String[] p={MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME};try(Cursor c=cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,p,MediaStore.MediaColumns.DISPLAY_NAME+"=?",new String[]{filename},MediaStore.MediaColumns.DATE_ADDED+" DESC")){if(c!=null&&c.moveToFirst()){long id=c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));Uri u=Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,String.valueOf(id));Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(u,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);return;}}}setStatus("Excel hazır: "+lastPath);}catch(Exception e){setStatus("Excel hazır: "+lastPath);}
    }

    private void updateCounter(){if(counter!=null)counter.setText("Doğrulanan: "+rows.size()+"/"+targetCount+" • Sayfa: "+pagesVisited+"/"+maxPages);}
    private void updateProgress(){updateCounter();if(progress!=null)progress.setProgress(Math.min(95,(int)Math.round(pagesVisited*100d/Math.max(1,maxPages))));}
    private void setStatus(String s){if(status!=null)status.setText(s);}
    private String decodeJs(String value)throws Exception{Object x=new JSONTokener(value==null?"\"\"":value).nextValue();return x instanceof String?(String)x:String.valueOf(x);}
    private String canonical(String u){try{Uri x=Uri.parse(u);return new Uri.Builder().scheme("https").authority(x.getHost()).path(x.getPath()).encodedQuery(x.getEncodedQuery()).build().toString();}catch(Exception e){return u;}}
    private String cleanTitle(String t){return t==null?"":t.replaceAll("\\s+"," ").trim();}
    private String norm(String s){return SafetyPolicy.normalize(s==null?"":s);}
    private String shortText(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n-1)+"…");}
    private String msg(Exception e){return e==null?"bilinmeyen hata":(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
    private TextView tv(String s,float sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(Color.rgb(30,33,38));if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);t.setGravity(Gravity.START);return t;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static JSONArray arr(JSONArray a){return a==null?new JSONArray():a;}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    @Override public void onBackPressed(){if(!finished&&web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}

    static boolean selfTestNumbers(){
        try{return Math.abs(parseNumber("4,6")-4.6)<.001&&Math.abs(parseNumber("1,2 bin")-1200)<.001&&Math.abs(parseNumber("12.500")-12500)<.001&&Math.abs(parseNumber("19.500 km")-19500)<.001;}catch(Exception e){return false;}
    }
}
