package com.imagine.livelingo.audio;

import static org.junit.Assert.*;
import org.junit.Test;

public class SpeakerRouterTest {
    @Test public void keepsSimilarVoiceInSameSlot(){
        SpeakerRouter r=new SpeakerRouter();
        assertEquals(1,r.assign(new VoiceProfile(112,.08f,.03f,VoiceProfile.Band.LOW)).speaker);
        assertEquals(1,r.assign(new VoiceProfile(118,.09f,.031f,VoiceProfile.Band.LOW)).speaker);
    }
    @Test public void separatesClearlyDifferentVoices(){
        SpeakerRouter r=new SpeakerRouter();
        assertEquals(1,r.assign(new VoiceProfile(108,.08f,.025f,VoiceProfile.Band.LOW)).speaker);
        assertEquals(2,r.assign(new VoiceProfile(235,.07f,.055f,VoiceProfile.Band.HIGH)).speaker);
        assertEquals(1,r.assign(new VoiceProfile(114,.08f,.027f,VoiceProfile.Band.LOW)).speaker);
        assertEquals(2,r.assign(new VoiceProfile(228,.075f,.052f,VoiceProfile.Band.HIGH)).speaker);
    }
}
