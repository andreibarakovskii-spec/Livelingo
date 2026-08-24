package com.imagine.livelingo.audio;

import java.util.ArrayList;
import java.util.List;

/** Lightweight fallback router; neural embedding ids can override the next assignment. */
public final class SpeakerRouter {
    private static final int MAX_SPEAKERS=8;
    private static final double MATCH_THRESHOLD=0.28;
    private static final ThreadLocal<Integer> FORCED_NEXT=new ThreadLocal<>();

    /** Called by STT engines immediately before onVoiceProfile for a neural speaker match. */
    public static void forceNextSpeaker(int speaker){if(speaker>0)FORCED_NEXT.set(speaker);}

    public static final class Match {
        public final int speaker;public final VoiceProfile profile;public final double confidence;public final boolean possibleOverlap;
        Match(int speaker,VoiceProfile profile,double confidence,boolean possibleOverlap){this.speaker=speaker;this.profile=profile;this.confidence=confidence;this.possibleOverlap=possibleOverlap;}
    }

    private final List<VoiceProfile> profiles=new ArrayList<>();
    public synchronized void reset(){profiles.clear();FORCED_NEXT.remove();}
    public synchronized int speakerCount(){return profiles.size();}

    public synchronized Match assign(VoiceProfile p){
        if(p==null)p=new VoiceProfile(0,0,0,VoiceProfile.Band.NEUTRAL);
        Integer forced=FORCED_NEXT.get();FORCED_NEXT.remove();
        if(forced!=null&&forced>0){
            while(profiles.size()<forced)profiles.add(new VoiceProfile(0,0,0,VoiceProfile.Band.NEUTRAL));
            VoiceProfile old=profiles.get(forced-1);profiles.set(forced-1,old.pitchHz<=0?p:blend(old,p));
            return new Match(forced,p,.98,false);
        }
        if(profiles.isEmpty()){profiles.add(p);return new Match(1,p,1.0,false);}

        int best=-1,second=-1;double bestDistance=Double.MAX_VALUE,secondDistance=Double.MAX_VALUE;
        for(int i=0;i<profiles.size();i++){
            VoiceProfile candidate=profiles.get(i);if(candidate.pitchHz<=0)continue;
            double d=distance(candidate,p);
            if(d<bestDistance){second=best;secondDistance=bestDistance;best=i;bestDistance=d;}else if(d<secondDistance){second=i;secondDistance=d;}
        }
        if(best<0){profiles.set(0,p);return new Match(1,p,.5,false);}
        if(bestDistance>MATCH_THRESHOLD&&profiles.size()<MAX_SPEAKERS&&reliable(p)){profiles.add(p);return new Match(profiles.size(),p,Math.max(.55,1.0-bestDistance),false);}
        boolean overlap=second>=0&&bestDistance>.16&&Math.abs(secondDistance-bestDistance)<.055;
        if(!overlap)profiles.set(best,blend(profiles.get(best),p));
        return new Match(best+1,p,Math.max(0.0,Math.min(1.0,1.0-bestDistance)),overlap);
    }

    private static boolean reliable(VoiceProfile p){return p.pitchHz>55f&&p.pitchHz<420f&&p.rms>.008f;}
    private static VoiceProfile blend(VoiceProfile old,VoiceProfile now){float pitch=old.pitchHz<=0?now.pitchHz:(now.pitchHz<=0?old.pitchHz:old.pitchHz*.84f+now.pitchHz*.16f);float rms=old.rms*.86f+now.rms*.14f;float bright=old.brightness*.86f+now.brightness*.14f;VoiceProfile.Band band=pitch>0&&pitch<145?VoiceProfile.Band.LOW:(pitch>205?VoiceProfile.Band.HIGH:VoiceProfile.Band.NEUTRAL);return new VoiceProfile(pitch,rms,bright,band);}
    private static double distance(VoiceProfile x,VoiceProfile y){double p=(x.pitchHz<=0||y.pitchHz<=0)?.30:Math.min(1.0,Math.abs(x.pitchHz-y.pitchHz)/135.0);double e=Math.min(1.0,Math.abs(x.rms-y.rms)/.18);double z=Math.min(1.0,Math.abs(x.brightness-y.brightness)/.075);double band=(x.band==y.band)?0.0:.12;return Math.min(1.0,p*.68+e*.09+z*.18+band*.05);}
}
