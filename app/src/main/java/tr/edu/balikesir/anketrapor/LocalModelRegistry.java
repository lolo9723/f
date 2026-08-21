package tr.edu.balikesir.anketrapor;

import android.app.ActivityManager;
import android.content.Context;

import java.io.File;

/** Yerel planlayıcı için cihaz kapasitesine göre model seçimi ve dosya doğrulama. */
final class LocalModelRegistry {
    private LocalModelRegistry() {}

    static final class Model {
        final String id, name, filename, url;
        final long expectedBytes;
        final int minRamGb, maxTokens;
        Model(String id,String name,String filename,String url,long bytes,int minRamGb,int maxTokens){
            this.id=id;this.name=name;this.filename=filename;this.url=url;this.expectedBytes=bytes;this.minRamGb=minRamGb;this.maxTokens=maxTokens;
        }
    }

    /* Gemma gated indirme ister; anonim uygulama indirmesi için Apache-2.0 Qwen paketleri kullanılır. */
    static final Model QWEN3_4B = new Model(
            "qwen3_4b_instruct", "Qwen3 4B Instruct", "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/main/qwen3_4b_instruct_2507_mixed_int4.litertlm?download=true",
            2659062907L, 12, 2048);

    static final Model QWEN3_17B = new Model(
            "qwen3_1_7b", "Qwen3 1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true",
            977184032L, 6, 4096);

    static final Model QWEN3_06B = new Model(
            "qwen3_0_6b", "Qwen3 0.6B", "Qwen3-0.6B.litertlm",
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm?download=true",
            614236160L, 4, 4096);

    private static final Model[] ALL={QWEN3_4B,QWEN3_17B,QWEN3_06B};

    static long totalRamBytes(Context c){
        try{ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();if(am!=null)am.getMemoryInfo(mi);return mi.totalMem;}catch(Exception e){return 0L;}
    }
    static double totalRamGb(Context c){return totalRamBytes(c)/(1024d*1024d*1024d);}

    static Model preferred(Context c){double gb=totalRamGb(c);if(gb>=11.4)return QWEN3_4B;if(gb>=5.4)return QWEN3_17B;return QWEN3_06B;}

    static Model fallback(Model current){
        if(current==null)return QWEN3_06B;
        if(QWEN3_4B.id.equals(current.id))return QWEN3_17B;
        if(QWEN3_17B.id.equals(current.id))return QWEN3_06B;
        return null;
    }

    static File modelDir(Context c){File base=c.getExternalFilesDir("models");if(base==null)base=new File(c.getFilesDir(),"models");if(!base.exists())base.mkdirs();return base;}
    static File file(Context c,Model m){return new File(modelDir(c),m.filename);}

    static boolean sizeMatches(Model m,long n){
        if(m==null||n<=0)return false;
        long lower=(long)(m.expectedBytes*0.80),upper=(long)(m.expectedBytes*1.20);
        return n>=lower&&n<=upper&&n>250L*1024L*1024L;
    }

    static boolean looksInstalled(Context c,Model m){if(m==null)return false;File f=file(c,m);return f.isFile()&&sizeMatches(m,f.length());}

    static Model closestForSize(long bytes,Model preferred){
        if(bytes<=0)return preferred;
        Model best=null;double bestRatio=Double.MAX_VALUE;
        for(Model m:ALL){
            double ratio=Math.abs((double)bytes-m.expectedBytes)/m.expectedBytes;
            if(ratio<bestRatio){bestRatio=ratio;best=m;}
        }
        return bestRatio<=0.20?best:null;
    }

    static Model strongestInstalled(Context c){
        double gb=totalRamGb(c);
        if(gb>=11.4&&looksInstalled(c,QWEN3_4B))return QWEN3_4B;
        if(gb>=5.4&&looksInstalled(c,QWEN3_17B))return QWEN3_17B;
        if(looksInstalled(c,QWEN3_06B))return QWEN3_06B;
        return null;
    }

    static boolean enoughFreeSpace(Context c,Model m){try{return m!=null&&modelDir(c).getUsableSpace()>m.expectedBytes+1024L*1024L*1024L;}catch(Exception e){return false;}}

    static String status(Context c){Model installed=strongestInstalled(c),recommended=preferred(c);String ram=String.format(java.util.Locale.US,"%.1f",totalRamGb(c));if(installed!=null)return installed.name+" kurulu • RAM "+ram+" GB";return "Önerilen: "+recommended.name+" • RAM "+ram+" GB";}

    static boolean selfTest(){
        return QWEN3_4B.expectedBytes>QWEN3_17B.expectedBytes
                &&QWEN3_17B.expectedBytes>QWEN3_06B.expectedBytes
                &&QWEN3_4B.url.startsWith("https://")&&QWEN3_17B.url.startsWith("https://")&&QWEN3_06B.url.startsWith("https://")
                &&QWEN3_4B.filename.endsWith(".litertlm")
                &&fallback(QWEN3_4B)==QWEN3_17B&&fallback(QWEN3_17B)==QWEN3_06B&&fallback(QWEN3_06B)==null
                &&sizeMatches(QWEN3_17B,QWEN3_17B.expectedBytes)
                &&closestForSize(QWEN3_06B.expectedBytes,QWEN3_17B)==QWEN3_06B;
    }
}
