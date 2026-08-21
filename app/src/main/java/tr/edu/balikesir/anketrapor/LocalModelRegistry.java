package tr.edu.balikesir.anketrapor;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;

import java.io.File;

/** Yerel planlayıcı için cihaz kapasitesine göre model seçimi ve dosya doğrulama. */
final class LocalModelRegistry {
    private LocalModelRegistry() {}

    static final class Model {
        final String id, name, filename, url;
        final long expectedBytes;
        final int minRamGb;
        Model(String id,String name,String filename,String url,long bytes,int minRamGb){this.id=id;this.name=name;this.filename=filename;this.url=url;this.expectedBytes=bytes;this.minRamGb=minRamGb;}
    }

    static final Model GEMMA4_E4B = new Model(
            "gemma4_e4b", "Gemma 4 E4B", "gemma-4-E4B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm?download=true",
            3659530240L, 12);
    static final Model GEMMA4_E2B = new Model(
            "gemma4_e2b", "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true",
            2588147712L, 8);
    static final Model GEMMA3_1B = new Model(
            "gemma3_1b", "Gemma 3 1B", "gemma3-1b-it-int4.litertlm",
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/42d538a932e8d5b12e6b3b455f5572560bd60b2c/gemma3-1b-it-int4.litertlm?download=true",
            584417280L, 6);

    static long totalRamBytes(Context c){
        try{
            ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo(); if(am!=null)am.getMemoryInfo(mi); return mi.totalMem;
        }catch(Exception e){return 0L;}
    }
    static double totalRamGb(Context c){ return totalRamBytes(c)/(1024d*1024d*1024d); }

    static Model preferred(Context c){
        double gb=totalRamGb(c);
        if(gb>=11.4) return GEMMA4_E4B;
        if(gb>=7.4) return GEMMA4_E2B;
        return GEMMA3_1B;
    }

    static File modelDir(Context c){
        File base=c.getExternalFilesDir("models");
        if(base==null)base=new File(c.getFilesDir(),"models");
        if(!base.exists())base.mkdirs();
        return base;
    }
    static File file(Context c,Model m){return new File(modelDir(c),m.filename);}

    static boolean looksInstalled(Context c,Model m){
        File f=file(c,m); if(!f.isFile())return false;
        long n=f.length(); long lower=(long)(m.expectedBytes*0.97), upper=(long)(m.expectedBytes*1.05);
        return n>=lower&&n<=upper;
    }

    static Model strongestInstalled(Context c){
        if(looksInstalled(c,GEMMA4_E4B)&&totalRamGb(c)>=11.4)return GEMMA4_E4B;
        if(looksInstalled(c,GEMMA4_E2B)&&totalRamGb(c)>=7.4)return GEMMA4_E2B;
        if(looksInstalled(c,GEMMA3_1B))return GEMMA3_1B;
        return null;
    }

    static boolean enoughFreeSpace(Context c,Model m){
        try{return modelDir(c).getUsableSpace()>m.expectedBytes+768L*1024L*1024L;}catch(Exception e){return false;}
    }

    static String status(Context c){
        Model installed=strongestInstalled(c); Model recommended=preferred(c);
        String ram=String.format(java.util.Locale.US,"%.1f",totalRamGb(c));
        if(installed!=null)return installed.name+" kurulu • RAM "+ram+" GB";
        return "Önerilen: "+recommended.name+" • RAM "+ram+" GB";
    }

    static boolean selfTest(){
        return GEMMA4_E4B.expectedBytes>GEMMA4_E2B.expectedBytes && GEMMA4_E4B.minRamGb>GEMMA4_E2B.minRamGb && GEMMA3_1B.filename.endsWith(".litertlm");
    }
}
