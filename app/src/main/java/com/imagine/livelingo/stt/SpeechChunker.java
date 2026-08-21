package com.imagine.livelingo.stt;

import java.util.ArrayList;
import java.util.List;

/** Lightweight VAD/chunker for streaming ASR: emits speech chunks after silence. */
public final class SpeechChunker {
    public interface Listener { void onChunk(float[] samples, boolean finalChunk); }
    private final Listener listener;
    private final List<Float> speech=new ArrayList<>();
    private int silentSamples;
    private boolean speaking;
    private float noiseFloor=0.008f;
    private static final int SAMPLE_RATE=16000;
    private static final int END_SILENCE=SAMPLE_RATE*45/100; // 450 ms
    private static final int MIN_SPEECH=SAMPLE_RATE*35/100; // 350 ms
    private static final int PARTIAL_INTERVAL=SAMPLE_RATE*14/10; // 1.4 s
    private int sincePartial;

    public SpeechChunker(Listener listener){this.listener=listener;}

    public synchronized void accept(float[] pcm){
        if(pcm==null||pcm.length==0)return;
        double sum=0;for(float v:pcm)sum+=v*v;float rms=(float)Math.sqrt(sum/pcm.length);
        if(!speaking)noiseFloor=noiseFloor*.98f+rms*.02f;
        float threshold=Math.max(0.015f,noiseFloor*2.8f);
        boolean voice=rms>=threshold;
        if(voice){speaking=true;silentSamples=0;append(pcm);sincePartial+=pcm.length;}
        else if(speaking){append(pcm);silentSamples+=pcm.length;sincePartial+=pcm.length;}
        if(speaking&&sincePartial>=PARTIAL_INTERVAL&&speech.size()>=MIN_SPEECH){listener.onChunk(copy(),false);sincePartial=0;}
        if(speaking&&silentSamples>=END_SILENCE){if(speech.size()>=MIN_SPEECH)listener.onChunk(copy(),true);reset();}
    }

    public synchronized void flush(){if(speaking&&speech.size()>=MIN_SPEECH)listener.onChunk(copy(),true);reset();}
    private void append(float[] pcm){for(float v:pcm)speech.add(v);}
    private float[] copy(){float[] out=new float[speech.size()];for(int i=0;i<out.length;i++)out[i]=speech.get(i);return out;}
    private void reset(){speech.clear();silentSamples=0;sincePartial=0;speaking=false;}
}