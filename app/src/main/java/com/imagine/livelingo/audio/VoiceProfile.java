package com.imagine.livelingo.audio;

/** Lightweight acoustic profile used for stable speaker routing without identifying a person. */
public final class VoiceProfile {
    public enum Band { LOW, NEUTRAL, HIGH }
    public final float pitchHz;
    public final float rms;
    public final float brightness;
    public final Band band;

    public VoiceProfile(float pitchHz,float rms,float brightness,Band band){
        this.pitchHz=pitchHz; this.rms=rms; this.brightness=brightness; this.band=band;
    }
}
