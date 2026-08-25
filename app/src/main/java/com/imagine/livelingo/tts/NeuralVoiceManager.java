package com.imagine.livelingo.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import com.imagine.livelingo.DebugTrace;
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

/** Lazy local neural TTS: Kokoro for English and Supertonic 3 for multilingual speech. */
public final class NeuralVoiceManager {
    public interface Listener { void onStatus(String message); void onDownloadProgress(int percent); }
    public interface Fallback { void speak(String text,String language,boolean finalChunk,int voiceProfile); }
    public interface SelfTestCallback { void onResult(boolean ok,String details); }

    private static final long MIN_KOKORO_BYTES=300_000_000L;
    private static final long RELEASE_AFTER_MS=180_000L;
    private static final String KOKORO_NAME="kokoro-en-v0_19";
    private static final String KOKORO_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2";
    private static final String SUPERTONIC_NAME="sherpa-onnx-supertonic-3-tts-int8-2026-05-11";
    private static final String SUPERTONIC_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2";

    private final Context context;private final Listener listener;private final Fallback fallback;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean closed,speaking;private MediaPlayer player;

    public NeuralVoiceManager(Context context,Listener listener,Fallback fallback){this.context=context.getApplicationContext();this.listener=listener;this.fallback=fallback;}
    private File baseDir(){return new File(context.getFilesDir(),"models/tts");}
    public File modelDir(){return new File(baseDir(),KOKORO_NAME);}
    public File modelFile(){return new File(modelDir(),"model.onnx");}
    public File multilingualDir(){return new File(baseDir(),SUPERTONIC_NAME);}
    public boolean isInstalled(){return modelFile().isFile()&&modelFile().length()>=MIN_KOKORO_BYTES&&new File(modelDir(),"voices.bin").isFile()&&new File(modelDir(),"tokens.txt").isFile()&&new File(modelDir(),"espeak-ng-data").isDirectory();}
    public boolean isMultilingualInstalled(){File d=multilingualDir();return new File(d,"duration_predictor.int8.onnx").isFile()&&new File(d,"text_encoder.int8.onnx").isFile()&&new File(d,"vector_estimator.int8.onnx").isFile()&&new File(d,"vocoder.int8.onnx").isFile()&&new File(d,"tts.json").isFile()&&new File(d,"unicode_indexer.bin").isFile()&&new File(d,"voice.bin").isFile();}
    public long modelSizeBytes(){return modelFile().isFile()?modelFile().length():0L;}
    public boolean isSpeaking(){return speaking;}

    public void downloadModel(){downloadPack(KOKORO_NAME,KOKORO_URL,"Kokoro · английские AI-голоса",()->isInstalled());}
    public void downloadMultilingualModel(){downloadPack(SUPERTONIC_NAME,SUPERTONIC_URL,"Supertonic 3 · 31 язык",()->isMultilingualInstalled());}

    /** Loads the multilingual model and performs real synthesis, not just a file-presence check. */
    public void selfTestMultilingual(String preferredLanguage,SelfTestCallback cb){
        if(closed)return;worker.execute(()->{
            long started=System.currentTimeMillis();boolean ok=false;String detail;
            try{
                if(!isMultilingualInstalled())throw new IllegalStateException("Supertonic 3 не установлен");
                postStatus("AI Voice · прогрев Supertonic…");postProgress(15);
                if(!SupertonicBridge.ensureLoaded(multilingualDir().getAbsolutePath()))throw new IllegalStateException("модель не загрузилась в память");
                postProgress(45);
                byte[] ru=SupertonicBridge.synthesize(multilingualDir().getAbsolutePath(),"Проверка русского голоса.","ru",1);
                if(ru==null||ru.length<1000)throw new IllegalStateException("русский тест не создал звук");
                postProgress(68);
                byte[] en=SupertonicBridge.synthesize(multilingualDir().getAbsolutePath(),"Voice test ready.","en",0);
                if(en==null||en.length<1000)throw new IllegalStateException("английский тест не создал звук");
                postProgress(82);
                String p=shortLang(preferredLanguage);if(!"ru".equals(p)&&!"en".equals(p)&&SupertonicBridge.isSupportedLanguage(p)){
                    byte[] extra=SupertonicBridge.synthesize(multilingualDir().getAbsolutePath(),sampleFor(p),p,1);
                    if(extra==null||extra.length<1000)throw new IllegalStateException(p+" тест не создал звук");
                }
                postProgress(100);ok=true;detail="RU ✓ · EN ✓"+(p!=null&&!"ru".equals(p)&&!"en".equals(p)?" · "+p.toUpperCase()+" ✓":"")+" · "+(System.currentTimeMillis()-started)+" мс";
                DebugTrace.logGlobal("TTS_SELFTEST","ok=true "+detail);postStatus("Supertonic готов · "+detail);
            }catch(Throwable e){detail=safe(e.getMessage());DebugTrace.logGlobal("TTS_SELFTEST","ok=false error="+detail);postStatus("Supertonic не готов · "+detail);}
            final boolean result=ok;final String d=detail;if(cb!=null)main.post(()->cb.onResult(result,d));
        });
    }

    private static String sampleFor(String lang){
        if("de".equals(lang))return "Stimme ist bereit.";if("fr".equals(lang))return "La voix est prête.";if("es".equals(lang))return "La voz está lista.";if("it".equals(lang))return "La voce è pronta.";if("pt".equals(lang))return "A voz está pronta.";if("uk".equals(lang))return "Голос готовий.";return "Voice test ready.";
    }

    private interface PackCheck{boolean ok();}
    private void downloadPack(String name,String url,String title,PackCheck check){if(closed)return;worker.execute(()->{
        File base=baseDir();if(!base.exists())base.mkdirs();File archive=new File(base,name+".tar.bz2.part");File staging=new File(base,name+"-staging");
        try{postStatus("Скачиваю "+title+"…");download(url,archive);if(staging.exists())deleteTree(staging);staging.mkdirs();postStatus("Распаковываю "+title+"…");extract(archive,staging);File extracted=new File(staging,name);if(!extracted.isDirectory())throw new IllegalStateException("папка модели не найдена в архиве");File dst=new File(base,name);if(dst.exists())deleteTree(dst);if(!extracted.renameTo(dst))copyTree(extracted,dst);archive.delete();deleteTree(staging);if(!check.ok())throw new IllegalStateException("пакет модели неполный");postProgress(100);postStatus(title+" готов");}
        catch(Throwable e){archive.delete();deleteTree(staging);postStatus(title+" не установлен: "+safe(e.getMessage()));}
    });}

    private void download(String url,File dst)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(true);c.connect();int code=c.getResponseCode();if(code<200||code>=400)throw new IllegalStateException("HTTP "+code);long total=c.getContentLengthLong(),done=0;byte[] buf=new byte[256*1024];int last=-1;try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(dst)){int n;while((n=in.read(buf))>0){if(closed)throw new InterruptedException();out.write(buf,0,n);done+=n;if(total>0){int p=(int)Math.min(95,(done*95)/total);if(p!=last){last=p;postProgress(p);}}}out.getFD().sync();}finally{c.disconnect();}if(dst.length()<20_000_000L)throw new IllegalStateException("архив скачан не полностью");}
    private static void extract(File archive,File root)throws Exception{String rootPath=root.getCanonicalPath()+File.separator;try(TarArchiveInputStream tar=new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(archive))))){TarArchiveEntry e;byte[] buf=new byte[256*1024];while((e=tar.getNextTarEntry())!=null){File out=new File(root,e.getName());String cp=out.getCanonicalPath();if(!cp.startsWith(rootPath))throw new SecurityException("invalid archive path");if(e.isDirectory()){out.mkdirs();continue;}File p=out.getParentFile();if(p!=null)p.mkdirs();try(FileOutputStream f=new FileOutputStream(out)){int n;while((n=tar.read(buf))>0)f.write(buf,0,n);}}}}

    public void speak(String text,String language,boolean finalChunk,int voiceProfile){
        if(closed||text==null||text.isBlank())return;final String clean=naturalize(text.trim());final String lang=shortLang(language);boolean useSupertonic=isMultilingualInstalled()&&SupertonicBridge.isSupportedLanguage(lang);boolean useKokoro=!useSupertonic&&"en".equals(lang)&&isInstalled();if(!useKokoro&&!useSupertonic){fallback.speak(clean,language,finalChunk,voiceProfile);return;}
        worker.execute(()->{if(closed)return;long started=System.currentTimeMillis();try{DebugTrace.logGlobal("TTS_REQUEST","engine="+(useSupertonic?"supertonic":"kokoro")+" lang="+lang+" chars="+clean.length());String remaining=clean;while(!remaining.isBlank()&&!closed){int cut=Math.min(145,remaining.length());if(cut<remaining.length()){int ws=remaining.lastIndexOf(' ',cut);if(ws>65)cut=ws;}String part=remaining.substring(0,cut).trim();remaining=remaining.substring(cut).trim();byte[] wav;if(useKokoro){SupertonicBridge.release();int sid=voiceProfile<=0?10:(voiceProfile>=2?3:6);wav=KokoroBridge.synthesize(modelDir().getAbsolutePath(),part,sid);}else{KokoroBridge.release();wav=SupertonicBridge.synthesize(multilingualDir().getAbsolutePath(),part,lang,voiceProfile);}if(wav==null||wav.length<64)throw new IllegalStateException("пустой аудиорезультат");DebugTrace.logGlobal("TTS_AUDIO_READY","engine="+(useSupertonic?"supertonic":"kokoro")+" lang="+lang+" ms="+(System.currentTimeMillis()-started)+" bytes="+wav.length);playWav(wav);}scheduleRelease();DebugTrace.logGlobal("TTS_OK","lang="+lang+" total_ms="+(System.currentTimeMillis()-started));}catch(Throwable e){speaking=false;DebugTrace.logGlobal("TTS_ERROR","lang="+lang+" error="+safe(e.getMessage()));postStatus("AI Voice: "+safe(e.getMessage())+" · резервный голос");fallback.speak(clean,language,finalChunk,voiceProfile);scheduleRelease();}});
    }

    /** Small punctuation/prosody normalization. Keeps meaning unchanged while avoiding flat robotic phrasing. */
    private static String naturalize(String s){String x=s.replaceAll("\\s+"," ").trim();x=x.replace("...","…");x=x.replaceAll("\\s+([,;:.!?])","$1");return x;}

    private void playWav(byte[] wav)throws Exception{
        File f=File.createTempFile("ll-voice-",".wav",context.getCacheDir());try(FileOutputStream o=new FileOutputStream(f)){o.write(wav);}final Object lock=new Object();final boolean[] done={false};
        main.post(()->{try{if(player!=null)try{player.release();}catch(Exception ignored){}player=new MediaPlayer();player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());player.setDataSource(f.getAbsolutePath());player.setOnCompletionListener(mp->{speaking=false;synchronized(lock){done[0]=true;lock.notifyAll();}try{mp.release();}catch(Exception ignored){}if(player==mp)player=null;f.delete();});player.setOnErrorListener((mp,w,e)->{speaking=false;synchronized(lock){done[0]=true;lock.notifyAll();}f.delete();return false;});player.prepare();speaking=true;player.start();}catch(Exception e){speaking=false;synchronized(lock){done[0]=true;lock.notifyAll();}f.delete();}});
        synchronized(lock){long until=System.currentTimeMillis()+30000;while(!done[0]&&!closed&&System.currentTimeMillis()<until)lock.wait(500);}speaking=false;f.delete();
    }
    private final Runnable releaser=()->worker.execute(()->{try{KokoroBridge.release();SupertonicBridge.release();postStatus("AI Voice освобождён из памяти");}catch(Throwable ignored){}});
    private void scheduleRelease(){main.removeCallbacks(releaser);main.postDelayed(releaser,RELEASE_AFTER_MS);}
    public void stop(){speaking=false;main.removeCallbacks(releaser);main.post(()->{if(player!=null){try{player.stop();player.release();}catch(Exception ignored){}player=null;}});}
    public void close(){closed=true;speaking=false;stop();worker.shutdownNow();KokoroBridge.release();SupertonicBridge.release();}
    private void postStatus(String s){if(listener!=null)main.post(()->listener.onStatus(s));}private void postProgress(int p){if(listener!=null)main.post(()->listener.onDownloadProgress(p));}
    private static String shortLang(String t){if(t==null)return "en";int i=t.indexOf('-');String l=(i>0?t.substring(0,i):t).toLowerCase();return l.isBlank()?"en":l;}
    private static String safe(String s){return s==null||s.isBlank()?"ошибка":s;}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File c:a)deleteTree(c);}f.delete();}
    private static void copyTree(File src,File dst)throws Exception{if(src.isDirectory()){dst.mkdirs();File[] a=src.listFiles();if(a!=null)for(File c:a)copyTree(c,new File(dst,c.getName()));}else{File p=dst.getParentFile();if(p!=null)p.mkdirs();try(InputStream in=new FileInputStream(src);FileOutputStream out=new FileOutputStream(dst)){byte[] b=new byte[256*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);}}}
}
