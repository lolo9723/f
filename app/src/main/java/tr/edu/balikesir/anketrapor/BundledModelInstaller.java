package tr.edu.balikesir.anketrapor;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** APK içindeki modeli ağ kullanmadan, SHA-256 doğrulayarak atomik biçimde hazırlar. */
final class BundledModelInstaller {
    private BundledModelInstaller() {}

    interface Progress { void onProgress(long done,long total); }

    static boolean assetMetadataValid(Context c){
        if(c==null||!LocalModelRegistry.selfTest())return false;
        AssetFileDescriptor afd=null;
        try{
            afd=c.getAssets().openFd(LocalModelRegistry.BUNDLED_ASSET);
            return afd.getLength()==LocalModelRegistry.BUNDLED_BYTES;
        }catch(Exception e){return false;}
        finally{if(afd!=null)try{afd.close();}catch(Exception ignored){}}
    }

    static File ensureInstalled(Context c, Progress progress) throws Exception {
        LocalModelRegistry.Model m=LocalModelRegistry.BUNDLED;
        if(LocalModelRegistry.looksInstalled(c,m)){
            if(progress!=null)progress.onProgress(m.expectedBytes,m.expectedBytes);
            return LocalModelRegistry.file(c,m);
        }
        if(!assetMetadataValid(c))throw new IllegalStateException("APK içindeki model dosyası eksik veya boyutu hatalı.");
        if(!LocalModelRegistry.enoughFreeSpace(c,m))throw new IllegalStateException("Yerel modeli hazırlamak için yeterli boş alan yok.");

        File dir=LocalModelRegistry.modelDir(c);
        File out=LocalModelRegistry.file(c,m);
        File marker=LocalModelRegistry.marker(c,m);
        File part=new File(dir,m.filename+".part");
        if(part.exists()&&!part.delete())throw new IllegalStateException("Eski geçici model dosyası silinemedi.");
        if(marker.exists())marker.delete();
        if(out.exists()&&!out.delete())throw new IllegalStateException("Eski model dosyası silinemedi.");

        MessageDigest md=MessageDigest.getInstance("SHA-256");
        long copied=0L;
        byte[] buf=new byte[4*1024*1024];
        try(InputStream in=c.getAssets().open(LocalModelRegistry.BUNDLED_ASSET,AssetManager.ACCESS_STREAMING);
            FileOutputStream fos=new FileOutputStream(part)){
            int n;
            while((n=in.read(buf))!=-1){
                if(n==0)continue;
                fos.write(buf,0,n);md.update(buf,0,n);copied+=n;
                if(progress!=null)progress.onProgress(copied,m.expectedBytes);
            }
            fos.flush();fos.getFD().sync();
        }catch(Exception e){part.delete();throw e;}

        if(copied!=m.expectedBytes){part.delete();throw new IllegalStateException("Gömülü model kopyası eksik: "+copied+" / "+m.expectedBytes);}
        String digest=hex(md.digest());
        if(!LocalModelRegistry.BUNDLED_SHA256.equalsIgnoreCase(digest)){
            part.delete();throw new SecurityException("Gömülü model SHA-256 doğrulaması başarısız.");
        }

        if(!part.renameTo(out)){
            try(InputStream in=new FileInputStream(part);FileOutputStream fos=new FileOutputStream(out)){
                int n;while((n=in.read(buf))!=-1){if(n>0)fos.write(buf,0,n);}fos.flush();fos.getFD().sync();
            }
            if(!part.delete())part.deleteOnExit();
        }
        writeMarker(marker);
        if(!LocalModelRegistry.looksInstalled(c,m)){
            out.delete();marker.delete();throw new IllegalStateException("Yerel model son doğrulamadan geçmedi.");
        }
        return out;
    }

    static boolean fullVerifyInstalled(Context c){
        LocalModelRegistry.Model m=LocalModelRegistry.BUNDLED;
        File f=LocalModelRegistry.file(c,m);
        if(!f.isFile()||f.length()!=m.expectedBytes)return false;
        try(InputStream in=new FileInputStream(f)){
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] b=new byte[8*1024*1024];int n;
            while((n=in.read(b))!=-1)if(n>0)md.update(b,0,n);
            boolean ok=LocalModelRegistry.BUNDLED_SHA256.equalsIgnoreCase(hex(md.digest()));
            if(ok)writeMarker(LocalModelRegistry.marker(c,m));
            return ok;
        }catch(Exception e){return false;}
    }

    private static void writeMarker(File marker)throws Exception{
        byte[] data=(LocalModelRegistry.BUNDLED_SHA256+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(marker,false)){
            out.write(data);out.flush();out.getFD().sync();
        }
    }

    static String hex(byte[] bytes){
        StringBuilder sb=new StringBuilder(bytes.length*2);
        for(byte b:bytes)sb.append(String.format(java.util.Locale.ROOT,"%02x",b&0xff));
        return sb.toString();
    }

    static boolean selfTest(){
        return "00ff".equals(hex(new byte[]{0x00,(byte)0xff}))
                &&LocalModelRegistry.selfTest()
                &&LocalModelRegistry.BUNDLED_BYTES>300_000_000L;
    }
}
