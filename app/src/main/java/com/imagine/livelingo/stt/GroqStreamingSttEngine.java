package com.imagine.livelingo.stt;

import android.content.Context;
import com.imagine.livelingo.DebugTrace;
import com.imagine.livelingo.ai.SecureApiKeyStore;
import com.imagine.livelingo.audio.SpeakerEmbeddingIdentifier;
import com.imagine.livelingo.audio.VoiceProfileAnalyzer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Continuous AudioRecord capture + Groq Whisper with a second logical safety track. */
public final class GroqStreamingSttEngine implements SttEngine {
    private static final String ENDPOINT="https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL="whisper-large-v3-turbo";
    private static final int SAFETY_SECONDS=30;
    private static final int EXTRA_RECHECK_MS=450;
    private final Context context;private final Listener listener;private final SecureApiKeyStore keys;private final SpeakerEmbeddingIdentifier speakerIdentifier;private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final PcmRingBuffer safetyBuffer=new PcmRingBuffer(PcmAudioCapture.SAMPLE_RATE*SAFETY_SECONDS);
    private volatile boolean running;private String forcedLanguage="auto";private PcmAudioCapture capture;private SpeechChunker chunker;

    private static final class Result {
        final String text,language;final double avgLogprob;final long latencyMs;
        Result(String text,String language,double avgLogprob,long latencyMs){this.text=text;this.language=language;this.avgLogprob=avgLogprob;this.latencyMs=latencyMs;}
    }

    public GroqStreamingSttEngine(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;this.keys=new SecureApiKeyStore(this.context);this.speakerIdentifier=new SpeakerEmbeddingIdentifier(this.context);}
    @Override public boolean isAvailable(){String key=keys.load();return key!=null&&key.startsWith("gsk_");}
    @Override public synchronized void setInputLanguage(String code){forcedLanguage=code==null?"auto":code;DebugTrace.logGlobal("STT_LANGUAGE","forced="+forcedLanguage);}
    @Override public synchronized void start(){
        if(running)return;
        if(!isAvailable()){listener.onError("Для непрерывного Groq Whisper нужен ключ gsk_…");return;}
        DebugTrace.logGlobal("STT_START","engine=groq-whisper safety_buffer_s="+SAFETY_SECONDS+" forced="+forcedLanguage);
        safetyBuffer.clear();speakerIdentifier.reset();speakerIdentifier.ensureModelAsync(listener::onStatus);
        chunker=new SpeechChunker((samples,finalChunk)->{
            DebugTrace.logGlobal(finalChunk?"STT_FINAL_CHUNK":"STT_PARTIAL_CHUNK","audio_ms="+(samples.length*1000/PcmAudioCapture.SAMPLE_RATE)+" samples="+samples.length);
            if(finalChunk&&running){
                int extra=PcmAudioCapture.SAMPLE_RATE*EXTRA_RECHECK_MS/1000;
                float[] wide=safetyBuffer.snapshotLast(samples.length+extra);
                network.execute(()->transcribeWithSafety(samples,wide));
            }
        },listener::onSpeechStart);
        capture=new PcmAudioCapture(context,new PcmAudioCapture.Listener(){
            @Override public void onPcm(float[] samples){
                if(!running)return;
                safetyBuffer.append(samples);
                SpeechChunker c=chunker;if(c!=null)c.accept(samples);
            }
            @Override public void onError(String message){DebugTrace.logGlobal("STT_CAPTURE_ERROR",message);listener.onError(message);}
        });
        running=true;listener.onStatus("Groq Whisper · непрерывная запись · safety buffer");capture.start();listener.onReady();
    }

    private void transcribeWithSafety(float[] fast,float[] wide){
        if(!running)return;
        try{
            int speaker=speakerIdentifier.identify(fast,PcmAudioCapture.SAMPLE_RATE);if(speaker>0){DebugTrace.logGlobal("SPEAKER_ID","speaker="+speaker);listener.onSpeakerId(speaker);}listener.onVoiceProfile(VoiceProfileAnalyzer.analyze(fast));
            Result first=request(fast,"FAST_RESULT");
            if(!running||first==null||first.text.isBlank())return;
            boolean suspicious=isSuspicious(first,fast.length);
            if(!suspicious||wide==null||wide.length<=fast.length){
                DebugTrace.logGlobal("FINAL_RESULT","source=fast text="+preview(first.text));
                listener.onFinal(first.text,lang(first));return;
            }
            DebugTrace.logGlobal("RECHECK_TRIGGER","reason="+reason(first,fast.length)+" fast_ms="+(fast.length*1000/PcmAudioCapture.SAMPLE_RATE)+" wide_ms="+(wide.length*1000/PcmAudioCapture.SAMPLE_RATE));
            listener.onPartial(first.text,lang(first));
            Result second=request(wide,"RECHECK_RESULT");
            Result chosen=choose(first,second);
            DebugTrace.logGlobal("FINAL_RESULT","source="+(chosen==second?"recheck":"fast")+" fast="+preview(first.text)+" final="+preview(chosen.text));
            if(running)listener.onFinal(chosen.text,lang(chosen));
        }catch(Exception e){DebugTrace.logGlobal("STT_ERROR",safe(e.getMessage()));if(running)listener.onError("Groq Whisper: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
    }

    private Result request(float[] samples,String event)throws Exception{
        if(!running)return null;String key=keys.load();if(key==null||!key.startsWith("gsk_"))throw new IllegalStateException("Groq API-ключ недоступен");
        long started=System.currentTimeMillis();HttpURLConnection c=null;
        try{
            byte[] audio=wav(samples);String boundary="----LiveLingo"+UUID.randomUUID().toString().replace("-","");
            DebugTrace.logGlobal("STT_UPLOAD","stage="+event+" bytes="+audio.length+" audio_ms="+(samples.length*1000/PcmAudioCapture.SAMPLE_RATE)+" forced="+forcedLanguage);
            c=(HttpURLConnection)new URL(ENDPOINT).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(15_000);c.setReadTimeout(60_000);c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            try(OutputStream out=c.getOutputStream()){
                field(out,boundary,"model",MODEL);field(out,boundary,"response_format","verbose_json");String lang=shortLang(forcedLanguage);if(lang!=null)field(out,boundary,"language",lang);
                out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(audio);out.write("\r\n".getBytes(StandardCharsets.UTF_8));out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code=c.getResponseCode();String raw=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());long latency=System.currentTimeMillis()-started;
            if(code<200||code>=300){DebugTrace.logGlobal("STT_HTTP_ERROR","stage="+event+" code="+code+" ms="+latency+" body="+trim(raw));throw new IllegalStateException("HTTP "+code+": "+trim(raw));}
            JSONObject j=new JSONObject(raw);String text=j.optString("text","").trim();String language=j.optString("language","").trim();double logprob=avgLogprob(j.optJSONArray("segments"));
            DebugTrace.logGlobal(event,"ms="+latency+" language="+language+" avg_logprob="+fmt(logprob)+" words="+wordCount(text)+" text="+preview(text));
            return new Result(text,language,logprob,latency);
        }finally{if(c!=null)c.disconnect();}
    }

    private static boolean isSuspicious(Result r,int samples){
        int words=wordCount(r.text);long ms=samples*1000L/PcmAudioCapture.SAMPLE_RATE;
        return words<=4||ms<1800||(!Double.isNaN(r.avgLogprob)&&r.avgLogprob<-0.55);
    }
    private static String reason(Result r,int samples){
        if(wordCount(r.text)<=4)return "short_text";
        if(samples*1000L/PcmAudioCapture.SAMPLE_RATE<1800)return "short_audio";
        return "low_confidence";
    }
    private static Result choose(Result fast,Result recheck){
        if(recheck==null||recheck.text==null||recheck.text.isBlank())return fast;
        if(normalize(fast.text).equals(normalize(recheck.text)))return fast;
        int fw=wordCount(fast.text),rw=wordCount(recheck.text);
        double fc=fast.avgLogprob,rc=recheck.avgLogprob;
        boolean confidenceOk=Double.isNaN(rc)||Double.isNaN(fc)||rc>=fc-0.15;
        if(confidenceOk&&rw>=fw&&recheck.text.length()>=fast.text.length())return recheck;
        return fast;
    }
    private String lang(Result r){return r.language==null||r.language.isBlank()?forcedLanguage:r.language;}
    private static double avgLogprob(JSONArray a){if(a==null||a.length()==0)return Double.NaN;double sum=0;int n=0;for(int i=0;i<a.length();i++){JSONObject s=a.optJSONObject(i);if(s!=null&&s.has("avg_logprob")){sum+=s.optDouble("avg_logprob",0);n++;}}return n==0?Double.NaN:sum/n;}
    private static int wordCount(String s){if(s==null||s.trim().isEmpty())return 0;return s.trim().split("\\s+").length;}
    private static String normalize(String s){return s==null?"":s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private static String fmt(double v){return Double.isNaN(v)?"na":String.format(java.util.Locale.US,"%.3f",v);}
    private static void field(OutputStream out,String boundary,String name,String value)throws Exception{out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));}
    private static String shortLang(String tag){if(tag==null||tag.isBlank()||"auto".equalsIgnoreCase(tag))return null;return tag.split("[-_]")[0].toLowerCase();}
    private static String read(InputStream in)throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    private static String trim(String s){if(s==null)return "ошибка";return s.length()>240?s.substring(0,240)+"…":s;}
    private static String preview(String s){if(s==null)return "";String x=s.replace('\n',' ').trim();return x.length()>120?x.substring(0,120)+"…":x;}
    private static String safe(String s){return s==null?"":s.replace('\n',' ');}
    private static byte[] wav(float[] samples)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream(44+samples.length*2);int data=samples.length*2,total=36+data,rate=PcmAudioCapture.SAMPLE_RATE,byteRate=rate*2;ascii(out,"RIFF");le32(out,total);ascii(out,"WAVEfmt ");le32(out,16);le16(out,1);le16(out,1);le32(out,rate);le32(out,byteRate);le16(out,2);le16(out,16);ascii(out,"data");le32(out,data);for(float f:samples){int v=Math.max(-32768,Math.min(32767,Math.round(f*32767f)));le16(out,v&0xffff);}return out.toByteArray();}
    private static void ascii(ByteArrayOutputStream o,String s)throws Exception{o.write(s.getBytes(StandardCharsets.US_ASCII));}private static void le16(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);}private static void le32(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}
    @Override public synchronized void stop(){
        if(!running)return;DebugTrace.logGlobal("STT_STOP","engine=groq-whisper");
        // Flush while running so the final pending phrase can still be queued.
        if(chunker!=null)chunker.flush();
        running=false;if(capture!=null)capture.stop();capture=null;chunker=null;safetyBuffer.clear();
    }
    @Override public synchronized void close(){stop();speakerIdentifier.close();network.shutdownNow();}
}
