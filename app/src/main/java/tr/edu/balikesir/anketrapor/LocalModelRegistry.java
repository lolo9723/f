package tr.edu.balikesir.anketrapor;

import android.app.ActivityManager;
import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 1.0.4: ağdan model seçmez; APK içindeki doğrulanmış no-think modeli kullanır. */
final class LocalModelRegistry {
    private LocalModelRegistry() {}

    static final String BUNDLED_ASSET = "models/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm";
    static final String BUNDLED_SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139";
    static final long BUNDLED_BYTES = 347_251_840L;

    static final class Model {
        final String id, name, filename;
        final long expectedBytes;
        final int minRamGb, maxTokens;
        Model(String id,String name,String filename,long bytes,int minRamGb,int maxTokens){
            this.id=id;this.name=name;this.filename=filename;this.expectedBytes=bytes;this.minRamGb=minRamGb;this.maxTokens=maxTokens;
        }
    }

    static final Model BUNDLED = new Model(
            "qwen3_06b_nothink_int4",
            "Qwen3 0.6B No-Think INT4",
            "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
            BUNDLED_BYTES,
            4,
            1200);

    static long totalRamBytes(Context c){
        try{
            ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo();
            if(am!=null)am.getMemoryInfo(mi);
            return mi.totalMem;
        }catch(Exception e){return 0L;}
    }

    static double totalRamGb(Context c){return totalRamBytes(c)/(1024d*1024d*1024d);}

    /** Güç yerine kararlılık: 1.0.4'ün doğal dil planlayıcısı her cihazda aynı doğrulanmış modeli kullanır. */
    static Model preferred(Context c){return BUNDLED;}

    static File modelDir(Context c){
        File base=c.getExternalFilesDir("models");
        if(base==null)base=new File(c.getNoBackupFilesDir(),"models");
        if(!base.exists())base.mkdirs();
        return base;
    }

    static File file(Context c,Model m){return new File(modelDir(c),m.filename);}
    static File marker(Context c,Model m){return new File(modelDir(c),m.filename+".verified");}

    static boolean sizeMatches(Model m,long n){return m!=null&&n==m.expectedBytes;}

    static boolean looksInstalled(Context c,Model m){
        if(m==null)return false;
        File f=file(c,m), marker=marker(c,m);
        if(!f.isFile()||!sizeMatches(m,f.length())||!marker.isFile())return false;
        try{
            String s=Files.readString(marker.toPath(),StandardCharsets.UTF_8).trim();
            return BUNDLED_SHA256.equalsIgnoreCase(s);
        }catch(Exception e){return false;}
    }

    static Model strongestInstalled(Context c){return looksInstalled(c,BUNDLED)?BUNDLED:null;}

    static boolean enoughFreeSpace(Context c,Model m){
        try{return m!=null&&modelDir(c).getUsableSpace()>m.expectedBytes+160L*1024L*1024L;}
        catch(Exception e){return false;}
    }

    static String status(Context c){
        String ram=String.format(java.util.Locale.US,"%.1f",totalRamGb(c));
        if(looksInstalled(c,BUNDLED))return BUNDLED.name+" hazır • RAM "+ram+" GB";
        return "Gömülü "+BUNDLED.name+" hazırlanacak • RAM "+ram+" GB";
    }

    static boolean selfTest(){
        return BUNDLED.expectedBytes==347_251_840L
                &&BUNDLED.maxTokens>0&&BUNDLED.maxTokens<=1280
                &&BUNDLED.filename.endsWith(".litertlm")
                &&BUNDLED_ASSET.endsWith(BUNDLED.filename)
                &&BUNDLED_SHA256.matches("[0-9a-f]{64}")
                &&BUNDLED_SHA256.equals("2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139");
    }
}
