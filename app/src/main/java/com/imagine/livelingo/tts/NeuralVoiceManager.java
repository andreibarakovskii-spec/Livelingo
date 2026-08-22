package com.imagine.livelingo.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lazy Kokoro controller. The 326 MB model is never bundled into the APK and is loaded
 * only while neural speech is actually requested. It is released after 45s of inactivity.
 */
public final class NeuralVoiceManager {
    public interface Listener { void onStatus(String message); void onDownloadProgress(int percent); }
    public interface Fallback { void speak(String text, boolean finalChunk, int voiceProfile); }

    private static final long MIN_MODEL_BYTES = 250_000_000L;
    private static final long RELEASE_AFTER_MS = 45_000L;
    private static final String MODEL_URL = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model.onnx?download=true";

    private final Context context;
    private final Listener listener;
    private final Fallback fallback;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private MediaPlayer player;

    public NeuralVoiceManager(Context context, Listener listener, Fallback fallback) {
        this.context = context.getApplicationContext(); this.listener = listener; this.fallback = fallback;
    }

    public File modelFile() { return new File(new File(context.getFilesDir(), "models/tts/kokoro"), "model.onnx"); }
    public boolean isInstalled() { File f=modelFile(); return f.isFile() && f.length() >= MIN_MODEL_BYTES; }
    public long modelSizeBytes() { File f=modelFile(); return f.isFile()?f.length():0L; }

    public void downloadModel() {
        if (closed) return;
        worker.execute(() -> {
            File dst=modelFile(); File dir=dst.getParentFile(); if(dir!=null&&!dir.exists())dir.mkdirs();
            File part=new File(dst.getAbsolutePath()+".part");
            try {
                postStatus("Скачиваю нейроголос Kokoro…");
                HttpURLConnection c=(HttpURLConnection)new URL(MODEL_URL).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true); c.connect();
                int code=c.getResponseCode(); if(code<200||code>=400)throw new IllegalStateException("HTTP "+code);
                long total=c.getContentLengthLong(); long done=0; byte[] buf=new byte[256*1024]; int last=-1;
                try(InputStream in=c.getInputStream(); FileOutputStream out=new FileOutputStream(part)){
                    int n; while((n=in.read(buf))>0){if(closed)throw new InterruptedException();out.write(buf,0,n);done+=n;if(total>0){int p=(int)Math.min(99,(done*100)/total);if(p!=last){last=p;postProgress(p);}}}
                    out.getFD().sync();
                } finally { c.disconnect(); }
                if(part.length()<MIN_MODEL_BYTES)throw new IllegalStateException("Файл модели неполный");
                if(dst.exists())dst.delete(); if(!part.renameTo(dst))throw new IllegalStateException("Не удалось сохранить модель");
                postProgress(100); postStatus("Kokoro установлен · нейроголос готов");
            } catch(Throwable e) { part.delete(); postStatus("Не удалось скачать Kokoro: "+safe(e.getMessage())); }
        });
    }

    public void speak(String text, String language, boolean finalChunk, int voiceProfile) {
        if (closed || text==null || text.isBlank()) return;
        // Free Kokoro Android voice pack is English. For other languages or low-profile
        // speaker we use the tuned system fallback until the multilingual pack is installed.
        if (!"en".equals(shortLang(language)) || voiceProfile==0 || !isInstalled()) {
            fallback.speak(text, finalChunk, voiceProfile); return;
        }
        final String clean=text.trim();
        worker.execute(() -> {
            if(closed)return;
            try {
                String remaining=clean;
                while(!remaining.isBlank()&&!closed){int cut=Math.min(180,remaining.length());if(cut<remaining.length()){int ws=remaining.lastIndexOf(' ',cut);if(ws>80)cut=ws;}String part=remaining.substring(0,cut).trim();remaining=remaining.substring(cut).trim();
                    byte[] wav=KokoroBridge.synthesize(context,modelFile().getAbsolutePath(),part); if(wav==null||wav.length<64)throw new IllegalStateException("пустой аудиорезультат"); playWav(wav); }
                scheduleRelease();
            } catch(Throwable e) { postStatus("Kokoro: "+safe(e.getMessage())+" · использую системный голос"); fallback.speak(clean,finalChunk,voiceProfile); scheduleRelease(); }
        });
    }

    private void playWav(byte[] wav) throws Exception {
        File f=File.createTempFile("ll-voice-",".wav",context.getCacheDir()); try(FileOutputStream o=new FileOutputStream(f)){o.write(wav);} wav=null;
        final Object lock=new Object(); final boolean[] done={false};
        main.post(() -> { try { if(player!=null){try{player.release();}catch(Exception ignored){}} player=new MediaPlayer(); player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()); player.setDataSource(f.getAbsolutePath()); player.setOnCompletionListener(mp->{synchronized(lock){done[0]=true;lock.notifyAll();}try{mp.release();}catch(Exception ignored){} if(player==mp)player=null;f.delete();}); player.setOnErrorListener((mp,w,e)->{synchronized(lock){done[0]=true;lock.notifyAll();}f.delete();return false;}); player.prepare(); player.start(); } catch(Exception e){synchronized(lock){done[0]=true;lock.notifyAll();}f.delete();} });
        synchronized(lock){long until=System.currentTimeMillis()+30_000L;while(!done[0]&&!closed&&System.currentTimeMillis()<until)lock.wait(500L);} f.delete();
    }

    private final Runnable releaser=()->worker.execute(()->{try{KokoroBridge.release();postStatus("Нейроголос выгружен из памяти");}catch(Throwable ignored){}});
    private void scheduleRelease(){main.removeCallbacks(releaser);main.postDelayed(releaser,RELEASE_AFTER_MS);}
    public void stop(){main.removeCallbacks(releaser);main.post(()->{if(player!=null){try{player.stop();player.release();}catch(Exception ignored){}player=null;}});}
    public void close(){closed=true;stop();worker.shutdownNow();try{KokoroBridge.release();}catch(Throwable ignored){}}
    private void postStatus(String s){if(listener!=null)main.post(()->listener.onStatus(s));}
    private void postProgress(int p){if(listener!=null)main.post(()->listener.onDownloadProgress(p));}
    private static String shortLang(String t){if(t==null)return null;int i=t.indexOf('-');return (i>0?t.substring(0,i):t).toLowerCase();}
    private static String safe(String s){return s==null||s.isBlank()?"ошибка":s;}
}
