package com.imagine.livelingo.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lazy local Kokoro controller using the official sherpa-onnx model pack. */
public final class NeuralVoiceManager {
    public interface Listener { void onStatus(String message); void onDownloadProgress(int percent); }
    public interface Fallback { void speak(String text, boolean finalChunk, int voiceProfile); }

    private static final long MIN_MODEL_BYTES = 300_000_000L;
    private static final long RELEASE_AFTER_MS = 35_000L;
    private static final String MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2";
    private final Context context; private final Listener listener; private final Fallback fallback;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(); private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed; private MediaPlayer player;

    public NeuralVoiceManager(Context context,Listener listener,Fallback fallback){this.context=context.getApplicationContext();this.listener=listener;this.fallback=fallback;}
    public File modelDir(){return new File(context.getFilesDir(),"models/tts/kokoro-en-v0_19");}
    public File modelFile(){return new File(modelDir(),"model.onnx");}
    public boolean isInstalled(){return modelFile().isFile()&&modelFile().length()>=MIN_MODEL_BYTES&&new File(modelDir(),"voices.bin").isFile()&&new File(modelDir(),"tokens.txt").isFile()&&new File(modelDir(),"espeak-ng-data").isDirectory();}
    public long modelSizeBytes(){return modelFile().isFile()?modelFile().length():0L;}

    public void downloadModel(){if(closed)return;worker.execute(()->{
        File base=new File(context.getFilesDir(),"models/tts"); if(!base.exists())base.mkdirs();
        File archive=new File(base,"kokoro-en-v0_19.tar.bz2.part"); File staging=new File(base,"kokoro-staging");
        try{
            postStatus("Скачиваю Kokoro · 11 натуральных голосов…"); download(archive);
            if(staging.exists())deleteTree(staging); staging.mkdirs(); postStatus("Распаковываю нейроголос…"); extract(archive,staging);
            File extracted=new File(staging,"kokoro-en-v0_19"); if(!new File(extracted,"model.onnx").isFile())throw new IllegalStateException("модель в архиве не найдена");
            File dst=modelDir(); if(dst.exists())deleteTree(dst); if(!extracted.renameTo(dst)){copyTree(extracted,dst);}
            archive.delete(); deleteTree(staging); if(!isInstalled())throw new IllegalStateException("пакет Kokoro неполный");
            postProgress(100); postStatus("Kokoro готов · 11 офлайн-голосов");
        }catch(Throwable e){archive.delete();deleteTree(staging);postStatus("Kokoro не установлен: "+safe(e.getMessage()));}
    });}

    private void download(File dst)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(MODEL_URL).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(true);c.connect();
        int code=c.getResponseCode();if(code<200||code>=400)throw new IllegalStateException("HTTP "+code);long total=c.getContentLengthLong(),done=0;byte[] buf=new byte[256*1024];int last=-1;
        try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(dst)){int n;while((n=in.read(buf))>0){if(closed)throw new InterruptedException();out.write(buf,0,n);done+=n;if(total>0){int p=(int)Math.min(95,(done*95)/total);if(p!=last){last=p;postProgress(p);}}}out.getFD().sync();}finally{c.disconnect();}
        if(dst.length()<50_000_000L)throw new IllegalStateException("архив скачан не полностью");
    }

    private static void extract(File archive,File root)throws Exception{
        String rootPath=root.getCanonicalPath()+File.separator;
        try(TarArchiveInputStream tar=new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(archive))))){
            TarArchiveEntry e;byte[] buf=new byte[256*1024];while((e=tar.getNextTarEntry())!=null){File out=new File(root,e.getName());String cp=out.getCanonicalPath();if(!cp.startsWith(rootPath))throw new SecurityException("invalid archive path");if(e.isDirectory()){out.mkdirs();continue;}File p=out.getParentFile();if(p!=null)p.mkdirs();try(FileOutputStream f=new FileOutputStream(out)){int n;while((n=tar.read(buf))>0)f.write(buf,0,n);}}
        }
    }

    public void speak(String text,String language,boolean finalChunk,int voiceProfile){
        if(closed||text==null||text.isBlank())return;
        if(!"en".equals(shortLang(language))||!isInstalled()){fallback.speak(text,finalChunk,voiceProfile);return;}
        final String clean=text.trim();final int sid=voiceProfile<=0?10:(voiceProfile>=2?3:6);
        worker.execute(()->{if(closed)return;try{String remaining=clean;while(!remaining.isBlank()&&!closed){int cut=Math.min(180,remaining.length());if(cut<remaining.length()){int ws=remaining.lastIndexOf(' ',cut);if(ws>80)cut=ws;}String part=remaining.substring(0,cut).trim();remaining=remaining.substring(cut).trim();byte[] wav=KokoroBridge.synthesize(modelDir().getAbsolutePath(),part,sid);if(wav==null||wav.length<64)throw new IllegalStateException("пустой аудиорезультат");playWav(wav);}scheduleRelease();}catch(Throwable e){postStatus("Kokoro: "+safe(e.getMessage())+" · резервный голос");fallback.speak(clean,finalChunk,voiceProfile);scheduleRelease();}});
    }

    private void playWav(byte[] wav)throws Exception{File f=File.createTempFile("ll-voice-",".wav",context.getCacheDir());try(FileOutputStream o=new FileOutputStream(f)){o.write(wav);}final Object lock=new Object();final boolean[] done={false};main.post(()->{try{if(player!=null)try{player.release();}catch(Exception ignored){}player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());player.setDataSource(f.getAbsolutePath());player.setOnCompletionListener(mp->{synchronized(lock){done[0]=true;lock.notifyAll();}try{mp.release();}catch(Exception ignored){}if(player==mp)player=null;f.delete();});player.setOnErrorListener((mp,w,e)->{synchronized(lock){done[0]=true;lock.notifyAll();}f.delete();return false;});player.prepare();player.start();}catch(Exception e){synchronized(lock){done[0]=true;lock.notifyAll();}f.delete();}});synchronized(lock){long until=System.currentTimeMillis()+30000;while(!done[0]&&!closed&&System.currentTimeMillis()<until)lock.wait(500);}f.delete();}
    private final Runnable releaser=()->worker.execute(()->{try{KokoroBridge.release();postStatus("Нейроголос освобождён из памяти");}catch(Throwable ignored){}});
    private void scheduleRelease(){main.removeCallbacks(releaser);main.postDelayed(releaser,RELEASE_AFTER_MS);} public void stop(){main.removeCallbacks(releaser);main.post(()->{if(player!=null){try{player.stop();player.release();}catch(Exception ignored){}player=null;}});} public void close(){closed=true;stop();worker.shutdownNow();KokoroBridge.release();}
    private void postStatus(String s){if(listener!=null)main.post(()->listener.onStatus(s));}private void postProgress(int p){if(listener!=null)main.post(()->listener.onDownloadProgress(p));}
    private static String shortLang(String t){if(t==null)return null;int i=t.indexOf('-');return(i>0?t.substring(0,i):t).toLowerCase();}private static String safe(String s){return s==null||s.isBlank()?"ошибка":s;}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File c:a)deleteTree(c);}f.delete();}
    private static void copyTree(File src,File dst)throws Exception{if(src.isDirectory()){dst.mkdirs();File[] a=src.listFiles();if(a!=null)for(File c:a)copyTree(c,new File(dst,c.getName()));}else{File p=dst.getParentFile();if(p!=null)p.mkdirs();try(InputStream in=new FileInputStream(src);FileOutputStream out=new FileOutputStream(dst)){byte[] b=new byte[256*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}}}
}
