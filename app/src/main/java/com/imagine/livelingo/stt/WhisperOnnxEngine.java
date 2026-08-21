package com.imagine.livelingo.stt;

import android.content.Context;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point for the local Whisper/ONNX pipeline.
 * Captures 16 kHz PCM, segments speech locally, and forwards chunks to the decoder.
 * The actual ONNX decoder implementation is isolated behind WhisperOnnxDecoder so
 * model/runtime changes do not affect SessionRuntime or the UI.
 */
public final class WhisperOnnxEngine implements SttEngine {
    private final Context context;
    private final Listener listener;
    private final ExecutorService decodeExecutor=Executors.newSingleThreadExecutor();
    private String forcedLanguage="auto";
    private boolean running;
    private PcmAudioCapture capture;
    private SpeechChunker chunker;
    private WhisperOnnxDecoder decoder;

    private static final String[] REQUIRED={
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_decoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_cache_initializer_batch.onnx",
            "Whisper_detokenizer.onnx"
    };

    public WhisperOnnxEngine(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}
    private File modelDir(){return new File(context.getFilesDir(),"models/whisper");}

    @Override public boolean isAvailable(){File dir=modelDir();for(String name:REQUIRED)if(!new File(dir,name).isFile())return false;return true;}
    @Override public void setInputLanguage(String code){forcedLanguage=code==null?"auto":code;}

    @Override public synchronized void start(){
        if(running)return;
        if(!isAvailable()){listener.onError("Локальная модель LiveLingo AI ещё не установлена");return;}
        try{decoder=new WhisperOnnxDecoder(modelDir());}
        catch(Exception e){listener.onError("Не удалось открыть локальную AI-модель: "+e.getMessage());return;}
        chunker=new SpeechChunker((samples,finalChunk)->decodeExecutor.execute(()->decode(samples,finalChunk)));
        capture=new PcmAudioCapture(context,new PcmAudioCapture.Listener(){
            @Override public void onPcm(float[] samples){SpeechChunker c=chunker;if(running&&c!=null)c.accept(samples);}
            @Override public void onError(String message){listener.onError(message);}
        });
        running=true;listener.onStatus("LiveLingo AI: локальная модель готова");capture.start();listener.onReady();
    }

    private void decode(float[] samples,boolean finalChunk){
        if(!running||decoder==null)return;
        try{
            WhisperOnnxDecoder.Result r=decoder.transcribe(samples,forcedLanguage);
            if(r==null||r.text==null||r.text.isBlank())return;
            if(finalChunk)listener.onFinal(r.text,r.language);else listener.onPartial(r.text,r.language);
        }catch(Exception e){listener.onError("Ошибка локального распознавания: "+e.getMessage());}
    }

    @Override public synchronized void stop(){
        if(!running)return;running=false;
        if(chunker!=null)chunker.flush();
        if(capture!=null)capture.stop();
        capture=null;chunker=null;
        if(decoder!=null){decoder.close();decoder=null;}
    }
    @Override public synchronized void close(){stop();decodeExecutor.shutdownNow();}
    public String inputLanguage(){return forcedLanguage;}
}
