package com.imagine.livelingo.stt;

import android.content.Context;
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
    private final Context context;
    private final Listener listener;
    private final SecureApiKeyStore keys;
    private final SpeakerEmbeddingIdentifier speakerIdentifier;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private String forcedLanguage="auto";
    private PcmAudioCapture capture;
    private SpeechChunker chunker;

    public GroqStreamingSttEngine(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;this.keys=new SecureApiKeyStore(this.context);this.speakerIdentifier=new SpeakerEmbeddingIdentifier(this.context);
    }

    @Override public boolean isAvailable(){String key=keys.load();return key!=null&&key.startsWith("gsk_");}
    @Override public synchronized void setInputLanguage(String code){forcedLanguage=code==null?"auto":code;}

    @Override public synchronized void start(){
        if(running)return;
        if(!isAvailable()){listener.onError("Для непрерывного Groq Whisper нужен ключ gsk_…");return;}
        speakerIdentifier.reset();speakerIdentifier.ensureModelAsync(listener::onStatus);
        chunker=new SpeechChunker((samples,finalChunk)->{if(finalChunk&&running)network.execute(()->transcribe(samples));},listener::onSpeechStart);
        capture=new PcmAudioCapture(context,new PcmAudioCapture.Listener(){
            @Override public void onPcm(float[] samples){SpeechChunker c=chunker;if(running&&c!=null)c.accept(samples);}
            @Override public void onError(String message){listener.onError(message);}
        });
        running=true;listener.onStatus("Groq Whisper · непрерывная запись");capture.start();listener.onReady();
    }

    private void transcribe(float[] samples){
        if(!running||samples==null||samples.length==0)return;
        String key=keys.load();if(key==null||!key.startsWith("gsk_")){listener.onError("Groq API-ключ недоступен");return;}
        HttpURLConnection c=null;
        try{
            // Speaker identity is calculated from the exact same acoustic segment before its text is emitted.
            int speaker=speakerIdentifier.identify(samples,PcmAudioCapture.SAMPLE_RATE);
            listener.onVoiceProfile(VoiceProfileAnalyzer.analyze(samples));
            if(speaker>0)listener.onSpeakerId(speaker);

            byte[] wav=wav(samples);
            String boundary="----LiveLingo"+UUID.randomUUID().toString().replace("-","");
            c=(HttpURLConnection)new URL(ENDPOINT).openConnection();
            c.setRequestMethod("POST");c.setConnectTimeout(15_000);c.setReadTimeout(60_000);c.setDoOutput(true);
            c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            try(OutputStream out=c.getOutputStream()){
                field(out,boundary,"model",MODEL);field(out,boundary,"response_format","verbose_json");
                String lang=shortLang(forcedLanguage);if(lang!=null)field(out,boundary,"language",lang);
                out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"meeting.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(wav);out.write("\r\n".getBytes(StandardCharsets.UTF_8));out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code=c.getResponseCode();String raw=read(code>=200&&code<300?c.getInputStream():c.getErrorStream());
            if(!running)return;if(code<200||code>=300){listener.onError("Groq Whisper HTTP "+code+": "+trim(raw));return;}
            JSONObject j=new JSONObject(raw);String text=j.optString("text","").trim();String language=j.optString("language","").trim();if(text.isBlank())return;
            listener.onFinal(text,language.isBlank()?forcedLanguage:language);
        }catch(Exception e){if(running)listener.onError("Groq Whisper: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
        finally{if(c!=null)c.disconnect();}
    }

    private static void field(OutputStream out,String boundary,String name,String value)throws Exception{out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));}
    private static String shortLang(String tag){if(tag==null||tag.isBlank()||"auto".equalsIgnoreCase(tag))return null;return tag.split("[-_]")[0].toLowerCase();}
    private static String read(InputStream in)throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    private static String trim(String s){if(s==null)return "ошибка";return s.length()>240?s.substring(0,240)+"…":s;}
    private static byte[] wav(float[] samples)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream(44+samples.length*2);int data=samples.length*2,total=36+data,rate=PcmAudioCapture.SAMPLE_RATE,byteRate=rate*2;ascii(out,"RIFF");le32(out,total);ascii(out,"WAVEfmt ");le32(out,16);le16(out,1);le16(out,1);le32(out,rate);le32(out,byteRate);le16(out,2);le16(out,16);ascii(out,"data");le32(out,data);for(float f:samples){int v=Math.max(-32768,Math.min(32767,Math.round(f*32767f)));le16(out,v&0xffff);}return out.toByteArray();}
    private static void ascii(ByteArrayOutputStream o,String s)throws Exception{o.write(s.getBytes(StandardCharsets.US_ASCII));}
    private static void le16(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);}
    private static void le32(ByteArrayOutputStream o,int v){o.write(v&255);o.write((v>>>8)&255);o.write((v>>>16)&255);o.write((v>>>24)&255);}

    @Override public synchronized void stop(){if(!running)return;running=false;if(chunker!=null)chunker.flush();if(capture!=null)capture.stop();capture=null;chunker=null;}
    @Override public synchronized void close(){stop();speakerIdentifier.close();network.shutdownNow();}
}
