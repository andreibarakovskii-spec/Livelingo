package com.imagine.livelingo.stt;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Captures mono 16 kHz PCM16 audio for local ASR. */
public final class PcmAudioCapture {
    public interface Listener { void onPcm(float[] samples); void onError(String message); }
    public static final int SAMPLE_RATE = 16000;
    private final Context context;
    private final Listener listener;
    private volatile boolean running;
    private AudioRecord record;
    private Thread thread;

    public PcmAudioCapture(Context context, Listener listener){this.context=context.getApplicationContext();this.listener=listener;}

    public synchronized void start(){
        if(running)return;
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){listener.onError("Нет доступа к микрофону");return;}
        int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0){listener.onError("Не удалось подготовить аудиобуфер");return;}
        int bytes=Math.max(min, SAMPLE_RATE/2*2);
        record=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bytes*2);
        if(record.getState()!=AudioRecord.STATE_INITIALIZED){record.release();record=null;listener.onError("Не удалось открыть микрофон");return;}
        running=true;record.startRecording();
        thread=new Thread(()->loop(bytes/2),"livelingo-pcm");thread.start();
    }

    private void loop(int shortsPerRead){
        short[] buf=new short[shortsPerRead];
        while(running){
            int n=record.read(buf,0,buf.length,AudioRecord.READ_BLOCKING);
            if(n>0){float[] out=new float[n];for(int i=0;i<n;i++)out[i]=buf[i]/32768f;listener.onPcm(out);} 
            else if(n<0){listener.onError("Ошибка чтения микрофона: "+n);break;}
        }
    }

    public synchronized void stop(){
        running=false;
        if(record!=null){try{record.stop();}catch(Exception ignored){}record.release();record=null;}
        if(thread!=null){try{thread.join(300);}catch(InterruptedException ignored){}thread=null;}
    }
}