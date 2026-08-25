package com.imagine.livelingo.stt;

/**
 * Small in-memory circular PCM buffer used as a safety track.
 * One microphone capture feeds both the fast VAD path and this buffer, so there is no second AudioRecord.
 */
public final class PcmRingBuffer {
    private final float[] data;
    private int write,count;
    private long totalWritten;

    public PcmRingBuffer(int capacitySamples){
        if(capacitySamples<=0)throw new IllegalArgumentException("capacitySamples");
        data=new float[capacitySamples];
    }

    public synchronized void append(float[] samples){
        if(samples==null)return;
        for(float v:samples){
            data[write]=v;
            write=(write+1)%data.length;
            if(count<data.length)count++;
            totalWritten++;
        }
    }

    /** Returns up to the requested number of most recent samples, oldest first. */
    public synchronized float[] snapshotLast(int samples){
        int n=Math.max(0,Math.min(samples,count));
        float[] out=new float[n];
        int start=(write-n+data.length)%data.length;
        for(int i=0;i<n;i++)out[i]=data[(start+i)%data.length];
        return out;
    }

    public synchronized int size(){return count;}
    public synchronized long totalWritten(){return totalWritten;}
    public synchronized void clear(){write=0;count=0;totalWritten=0;}
}
