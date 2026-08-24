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
import org.json.JSONObject;

/** Continuous AudioRecord capture + Groq Whisper. Android SpeechRecognizer is not used. */
public final class GroqStreamingSttEngine implements SttEngine {
    private static final String ENDPOINT="https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String MODEL="whisper-large-v3-turbo";
    private final Context context;private final Listener listener;private final SecureApiKeyStore keys;private final SpeakerEmbeddingIdentifier speakerIdentifier;private final ExecutorService network=Executors.newSingleThreadExecutor();
    private volatile boolean running;private String forcedLanguage="auto";private PcmAudioCapture capture;private SpeechChunker chunker;

    public GroqStreamingSttEngine(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;this.keys=new SecureApiKeyStore(this.context);this.speakerIdentifier=new SpeakerEmbeddingIdentifier(this.context);}
    @Override public boolean isAvailable(){String key=keys.load();return key!=null&&key.startsWith("gsk_");}
    @Override public synchronized void setInputLanguage(String code){forcedLanguage=code==null?"auto":code;DebugTrace.logGlobal("STT_LANGUAGE","forced="+forcedLanguage);}
    @Override public synchronized void start(){
        if(running)return;
        if(!isAvailable()){listener.onError("Для непрерывного Groq Whisper нужен ключ gsk_…");return;}
        DebugTrace.logGlobal("STT_START","engine=groq-whisper forced="+forcedLanguage);
        speakerIdentifier.reset();speakerIdentifier.ensureModelAsync(listener::onStatus);
        chunker=new SpeechChunker((samples,finalChunk)->{
            DebugTrace.logGlobal(finalChunk?"STT_FINAL_CHUNK":"STT_PARTIAL_CHUNK","audio_ms="+(samples.length*1000/PcmAudioCapture.SAMPLE_RATE)+" samples="+samples.length);
            if(finalChunk&&running)network.execute(()->transcribe(samples));
        },listener::onSpeechStart);
        capture=new PcmAudioCapture(context,new PcmAudioCapture.Listener(){@Override public void onPcm(float[] samples){SpeechChunker c=chunker;if(running&&c!=null)c.accept(samples);}@Override public void onError(String message){DebugTrace.logGlobal("STT_CAPTURE_ERROR",message);listener.onError(message);}});
        running=true;listener.onStatus("Groq Whisper · непрерывная запись");capture.start();listener.onReady();
    }

    private void transcribe(float[] samples){
        if(!running||samples==null||samples.length==0)return;
        long started=System.currentTimeMillis();String key=keys.load();if(key==null||!key.startsWith("gsk_")){listener.onError("Groq API-ключ недоступен");return;}HttpURLConnection c=null;
        try{
            int speaker=speakerIdentifier.identify(samples,PcmAudioCapture.SAMPLE_RATE);if(speaker>0){DebugTrace.logGlobal("SPEAKER_ID","speaker="+speaker);listener.onSpeakerId(speaker);}listener.onVoiceProfile(VoiceProfileAnalyzer.analyze(samples));
            byte[] wav=wav(samples);DebugTrace.logGlobal("STT_UPLOAD","bytes="+wav.length+" audio_ms="+(samples.length*1000/PcmAudioCapture.SAMPLE_RATE)+" forced="+forcedLanguage);
            String boundary="----LiveLingo"+UUID.randomUUID().toString().replace("-","");c=(HttpURLConnection)new URL(ENDPOINT).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(15_000);c.setReadTimeout(60_000);c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            try(OutputStream out=c.getOutputStream()){field(out,boundary,"model",MODEL);field(out,boundary,"response_format","verbose_json");String lang=shortLang(forcedLanguage);if(lang!=null)field(out,boundary,"language",lang);out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"meeting.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(wav);out.write("\r\n".getBytes(StandardCharsets.UTF_8));out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));}
            int code=c.getResponseCode();String raw=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());long latency=System.currentTimeMillis()-started;if(!running)return;
            if(code<200||code>=300){DebugTrace.logGlobal("STT_HTTP_ERROR","code="+code+" ms="+latency+" body="+trim(raw));listener.onError("Groq Whisper HTTP "+code+": "+trim(raw));return;}
            JSONObject j=new JSONObject(raw);String text=j.optString("text","").trim();String language=j.optString("language","").trim();DebugTrace.logGlobal("STT_RESULT","ms="+latency+" language="+language+" chars="+text.length()+" text="+preview(text));if(text.isBlank())return;listener.onFinal(text,language.isBlank()?forcedLanguage:language);
        }catch(Exception e){DebugTrace.logGlobal("STT_ERROR","ms="+(System.currentTimeMillis()-started)+" "+safe(e.getMessage()));if(running)listener.onError("Groq Whisper: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}finally{if(c!=null)c.disconnect();}
    }

    private static void field(OutputStream out,String boundary,String name,String value)throws Exception{out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));}
    private static String shortLang(String tag){if(tag==null||tag.isBlank()||"auto".equalsIgnoreCase(tag))return null;return tag.split("[-_]")[0].toLowerCase();}
    private static String read(InputStream in)throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    private static String trim(String s){if(s==null)return "ошибка";return s.length()>240?s.substring(0,240)+"…":s;}
    private static String preview(String s){if(s==null)return "";String x=s.replace('\n',' ').trim();return x.length()>120?x.substring(0,120)+"…":x;}
    private static String safe(String s){return s==null?"":s.replace('\n',' ');}
    private static byte[] wav(float[] samples)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream(44+samples.length*2);int data=samples.length*2,total=36+data,rate=PcmAudioCapture.SAMPLE_RATE,byteRate=rate*2;ascii(out,"RIFF");le32(out,total);ascii(out,"WAVEfmt ");le32(out,16);le16(out,1);le16(out,1);le32(out,rate);le32(out,byteRate);le16(out,2);le16(out,16);ascii(out,"data");le32(out,data);for(float f:samples){int v=Math.max(-32768,Math.min(32767,Math.round(f*32767f)));le16(out,v&0xffff);}return out.toByteArray();}
    private static void ascii(ByteArrayOutputStream o,String s)throws Exception{o.write(s.getBytes(StandardCharsets.US_ASCII));}private static void le16(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);}private static void le32(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}
    @Override public synchronized void stop(){if(!running)return;DebugTrace.logGlobal("STT_STOP","engine=groq-whisper");running=false;if(chunker!=null)chunker.flush();if(capture!=null)capture.stop();capture=null;chunker=null;}
    @Override public synchronized void close(){stop();speakerIdentifier.close();network.shutdownNow();}
}
