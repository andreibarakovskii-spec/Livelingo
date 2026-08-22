package com.imagine.livelingo.audio;

/** Keeps two stable anonymous speaker slots for conversation/meeting modes. */
public final class SpeakerRouter {
    public static final class Match {
        public final int speaker; // 1 or 2
        public final VoiceProfile profile;
        Match(int speaker,VoiceProfile profile){this.speaker=speaker;this.profile=profile;}
    }

    private VoiceProfile a,b;
    public synchronized void reset(){a=null;b=null;}

    public synchronized Match assign(VoiceProfile p){
        if(p==null) p=new VoiceProfile(0,0,0,VoiceProfile.Band.NEUTRAL);
        if(a==null){a=p;return new Match(1,p);}
        if(b==null){
            // Don't create a second speaker from a tiny variation of the same voice.
            if(distance(a,p)<0.22){a=blend(a,p);return new Match(1,p);}
            b=p;return new Match(2,p);
        }
        double da=distance(a,p),db=distance(b,p);
        if(da<=db){a=blend(a,p);return new Match(1,p);}else{b=blend(b,p);return new Match(2,p);}
    }

    private static VoiceProfile blend(VoiceProfile old,VoiceProfile now){
        float pitch=old.pitchHz<=0?now.pitchHz:(now.pitchHz<=0?old.pitchHz:old.pitchHz*.78f+now.pitchHz*.22f);
        float rms=old.rms*.8f+now.rms*.2f;
        float bright=old.brightness*.8f+now.brightness*.2f;
        VoiceProfile.Band band=pitch>0&&pitch<145?VoiceProfile.Band.LOW:(pitch>205?VoiceProfile.Band.HIGH:VoiceProfile.Band.NEUTRAL);
        return new VoiceProfile(pitch,rms,bright,band);
    }

    private static double distance(VoiceProfile x,VoiceProfile y){
        double p;
        if(x.pitchHz<=0||y.pitchHz<=0)p=0.25; else p=Math.min(1.0,Math.abs(x.pitchHz-y.pitchHz)/140.0);
        double e=Math.min(1.0,Math.abs(x.rms-y.rms)/0.20);
        double z=Math.min(1.0,Math.abs(x.brightness-y.brightness)/0.08);
        return p*.72+e*.10+z*.18;
    }
}
