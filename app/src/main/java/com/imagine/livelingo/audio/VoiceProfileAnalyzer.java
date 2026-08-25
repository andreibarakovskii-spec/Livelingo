package com.imagine.livelingo.audio;

/** CPU-only pitch/timbre estimator for 16 kHz mono PCM. No neural model is kept in RAM. */
public final class VoiceProfileAnalyzer {
    private VoiceProfileAnalyzer() {}

    public static VoiceProfile analyze(float[] pcm){
        if(pcm==null || pcm.length<1600) return new VoiceProfile(0f,0f,0f,VoiceProfile.Band.NEUTRAL);
        int n=Math.min(pcm.length,16000*3);
        double energy=0; int zero=0;
        for(int i=1;i<n;i++){
            float x=pcm[i]; energy+=x*x;
            if((pcm[i-1]<0 && x>=0)||(pcm[i-1]>=0 && x<0)) zero++;
        }
        float rms=(float)Math.sqrt(energy/Math.max(1,n));
        float brightness=(float)zero/Math.max(1,n);
        float pitch=estimatePitch(pcm,n,16000);
        VoiceProfile.Band band=pitch>0 && pitch<145?VoiceProfile.Band.LOW:(pitch>205?VoiceProfile.Band.HIGH:VoiceProfile.Band.NEUTRAL);
        return new VoiceProfile(pitch,rms,brightness,band);
    }

    private static float estimatePitch(float[] x,int n,int sr){
        int minLag=sr/300, maxLag=Math.min(sr/70,n/2);
        double best=-1; int bestLag=0;
        // Decimate correlation work by stepping every 2 samples to keep conference RAM/CPU low.
        for(int lag=minLag;lag<=maxLag;lag+=2){
            double num=0,a=0,b=0;
            for(int i=0;i+lag<n;i+=2){
                double p=x[i],q=x[i+lag]; num+=p*q; a+=p*p; b+=q*q;
            }
            double den=Math.sqrt(a*b)+1e-9;
            double score=num/den;
            if(score>best){best=score;bestLag=lag;}
        }
        if(bestLag==0 || best<0.25) return 0f;
        return (float)sr/bestLag;
    }
}
