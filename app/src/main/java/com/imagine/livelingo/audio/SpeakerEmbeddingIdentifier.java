package com.imagine.livelingo.audio;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stable anonymous speaker identification based on sherpa-onnx 3D-Speaker embeddings.
 * The ~38 MB model is downloaded once and kept app-private; it is not bundled into the APK.
 */
public final class SpeakerEmbeddingIdentifier {
    public interface Status { void onStatus(String text); }
    private static final String MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx";
    private static final long MIN_MODEL_BYTES=20L*1024L*1024L;
    private static final int MAX_SPEAKERS=8;
    private static final float MATCH_THRESHOLD=0.62f;

    private final Context context;
    private final ExecutorService downloader=Executors.newSingleThreadExecutor();
    private final List<float[]> centroids=new ArrayList<>();
    private final List<Integer> counts=new ArrayList<>();
    private SpeakerEmbeddingExtractor extractor;
    private volatile boolean downloading;

    public SpeakerEmbeddingIdentifier(Context context){this.context=context.getApplicationContext();}
    private File dir(){File d=new File(context.getFilesDir(),"models/speaker");if(!d.exists())d.mkdirs();return d;}
    private File model(){return new File(dir(),"embedding.onnx");}
    public boolean isInstalled(){File f=model();return f.isFile()&&f.length()>=MIN_MODEL_BYTES;}

    public synchronized void reset(){centroids.clear();counts.clear();}

    public void ensureModelAsync(Status status){
        if(isInstalled()){if(status!=null)status.onStatus("Speaker AI · готов");return;}
        if(downloading)return;downloading=true;
        downloader.execute(()->{
            File tmp=new File(dir(),"embedding.onnx.part");
            try{
                if(status!=null)status.onStatus("Speaker AI · загрузка модели ~38 МБ…");
                HttpURLConnection c=openFollowingRedirects(MODEL_URL);
                int code=c.getResponseCode();if(code<200||code>=300)throw new IllegalStateException("HTTP "+code);
                try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(tmp)){
                    byte[] b=new byte[128*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);
                }finally{c.disconnect();}
                if(tmp.length()<MIN_MODEL_BYTES)throw new IllegalStateException("модель скачалась не полностью");
                File dst=model();if(dst.exists())dst.delete();if(!tmp.renameTo(dst))throw new IllegalStateException("не удалось сохранить модель");
                if(status!=null)status.onStatus("Speaker AI · точное определение спикеров готово");
            }catch(Exception e){tmp.delete();if(status!=null)status.onStatus("Speaker AI · не удалось загрузить: "+safe(e.getMessage()));}
            finally{downloading=false;}
        });
    }

    /** Returns 1-based stable speaker id, or 0 when the neural model cannot score this fragment. */
    public synchronized int identify(float[] samples,int sampleRate){
        if(samples==null||samples.length<sampleRate/2||!isInstalled())return 0;
        try{
            SpeakerEmbeddingExtractor ex=extractor();
            OnlineStream stream=ex.createStream();
            try{
                stream.acceptWaveform(samples,sampleRate);stream.inputFinished();
                if(!ex.isReady(stream))return 0;
                float[] embedding=ex.compute(stream);if(embedding==null||embedding.length==0)return 0;
                normalize(embedding);return assign(embedding);
            }finally{stream.release();}
        }catch(Throwable ignored){return 0;}
    }

    private SpeakerEmbeddingExtractor extractor(){
        if(extractor==null){
            SpeakerEmbeddingExtractorConfig cfg=SpeakerEmbeddingExtractorConfig.builder().setModel(model().getAbsolutePath()).setNumThreads(2).setDebug(false).build();
            extractor=new SpeakerEmbeddingExtractor(cfg);
        }
        return extractor;
    }

    private int assign(float[] e){
        if(centroids.isEmpty()){centroids.add(e.clone());counts.add(1);return 1;}
        int best=-1;float bestScore=-1f;
        for(int i=0;i<centroids.size();i++){float s=cosine(e,centroids.get(i));if(s>bestScore){bestScore=s;best=i;}}
        if(bestScore<MATCH_THRESHOLD&&centroids.size()<MAX_SPEAKERS){centroids.add(e.clone());counts.add(1);return centroids.size();}
        if(best<0)best=0;
        int n=counts.get(best);float alpha=n<3?0.32f:0.14f;float[] c=centroids.get(best);
        for(int i=0;i<c.length&&i<e.length;i++)c[i]=c[i]*(1f-alpha)+e[i]*alpha;normalize(c);counts.set(best,n+1);
        return best+1;
    }

    public synchronized void close(){if(extractor!=null){extractor.release();extractor=null;}downloader.shutdownNow();}

    private static float cosine(float[] a,float[] b){double dot=0,aa=0,bb=0;int n=Math.min(a.length,b.length);for(int i=0;i<n;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}return aa<=0||bb<=0?-1f:(float)(dot/Math.sqrt(aa*bb));}
    private static void normalize(float[] a){double s=0;for(float v:a)s+=v*v;if(s<=0)return;float d=(float)Math.sqrt(s);for(int i=0;i<a.length;i++)a[i]/=d;}
    private static String safe(String s){return s==null||s.isBlank()?"ошибка":s;}
    private static HttpURLConnection openFollowingRedirects(String first)throws Exception{
        URL u=new URL(first);for(int i=0;i<6;i++){HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","LiveLingo/1.0");int code=c.getResponseCode();if(code>=300&&code<400){String loc=c.getHeaderField("Location");c.disconnect();if(loc==null)throw new IllegalStateException("redirect without location");u=new URL(u,loc);continue;}return c;}throw new IllegalStateException("too many redirects");
    }
}
