package com.imagine.livelingo.stt;

import java.util.ArrayList;
import java.util.List;

/** Lightweight streaming VAD with pre-roll so the first syllables are not clipped. */
public final class SpeechChunker {
    public interface Listener { void onChunk(float[] samples, boolean finalChunk); }
    private final Listener listener;
    private final List<Float> speech=new ArrayList<>();
    private static final int SAMPLE_RATE=16000;
    private static final int PRE_ROLL=SAMPLE_RATE*30/100; // 300 ms before detected speech
    private static final int END_SILENCE=SAMPLE_RATE*65/100; // 650 ms natural pause
    private static final int MIN_SPEECH=SAMPLE_RATE*30/100; // 300 ms actual voiced audio
    private static final int PARTIAL_INTERVAL=SAMPLE_RATE*11/10; // 1.1 s
    private final float[] preRoll=new float[PRE_ROLL];
    private int preWrite,preCount,silentSamples,voicedSamples,sincePartial;
    private boolean speaking;
    private float noiseFloor=0.008f;

    public SpeechChunker(Listener listener){this.listener=listener;}

    public synchronized void accept(float[] pcm){
        if(pcm==null||pcm.length==0)return;
        double sum=0;for(float v:pcm)sum+=v*v;float rms=(float)Math.sqrt(sum/pcm.length);
        if(!speaking)noiseFloor=noiseFloor*.985f+rms*.015f;
        float startThreshold=Math.max(0.014f,noiseFloor*2.65f);
        float holdThreshold=Math.max(0.010f,noiseFloor*1.75f);
        boolean voice=rms>=(speaking?holdThreshold:startThreshold);

        if(!speaking){
            if(voice){
                speaking=true;silentSamples=0;voicedSamples=pcm.length;sincePartial=pcm.length;
                appendPreRoll();clearPreRoll();append(pcm);
            }else pushPreRoll(pcm);
        }else if(voice){
            silentSamples=0;voicedSamples+=pcm.length;append(pcm);sincePartial+=pcm.length;
        }else{
            append(pcm);silentSamples+=pcm.length;sincePartial+=pcm.length;
        }

        if(speaking&&sincePartial>=PARTIAL_INTERVAL&&voicedSamples>=MIN_SPEECH){listener.onChunk(copy(),false);sincePartial=0;}
        if(speaking&&silentSamples>=END_SILENCE){if(voicedSamples>=MIN_SPEECH)listener.onChunk(copy(),true);resetSpeech();}
    }

    public synchronized void flush(){if(speaking&&voicedSamples>=MIN_SPEECH)listener.onChunk(copy(),true);resetSpeech();clearPreRoll();}

    private void pushPreRoll(float[] pcm){for(float v:pcm){preRoll[preWrite]=v;preWrite=(preWrite+1)%preRoll.length;if(preCount<preRoll.length)preCount++;}}
    private void appendPreRoll(){if(preCount==0)return;int start=(preWrite-preCount+preRoll.length)%preRoll.length;for(int i=0;i<preCount;i++)speech.add(preRoll[(start+i)%preRoll.length]);}
    private void clearPreRoll(){preWrite=0;preCount=0;}
    private void append(float[] pcm){for(float v:pcm)speech.add(v);}
    private float[] copy(){float[] out=new float[speech.size()];for(int i=0;i<out.length;i++)out[i]=speech.get(i);return out;}
    private void resetSpeech(){speech.clear();silentSamples=0;voicedSamples=0;sincePartial=0;speaking=false;}
}
