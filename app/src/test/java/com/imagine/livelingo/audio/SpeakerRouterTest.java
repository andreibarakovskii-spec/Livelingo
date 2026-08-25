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
    @Test public void createsThirdParticipantInsteadOfForcingIntoFirstTwo(){
        SpeakerRouter r=new SpeakerRouter();
        assertEquals(1,r.assign(new VoiceProfile(105,.08f,.020f,VoiceProfile.Band.LOW)).speaker);
        assertEquals(2,r.assign(new VoiceProfile(235,.07f,.060f,VoiceProfile.Band.HIGH)).speaker);
        assertEquals(3,r.assign(new VoiceProfile(170,.12f,.095f,VoiceProfile.Band.NEUTRAL)).speaker);
        assertEquals(3,r.assign(new VoiceProfile(176,.115f,.092f,VoiceProfile.Band.NEUTRAL)).speaker);
        assertEquals(3,r.speakerCount());
    }
    @Test public void returnsConfidenceForAmbiguousProfile(){
        SpeakerRouter r=new SpeakerRouter();
        r.assign(new VoiceProfile(110,.08f,.025f,VoiceProfile.Band.LOW));
        r.assign(new VoiceProfile(225,.08f,.060f,VoiceProfile.Band.HIGH));
        SpeakerRouter.Match m=r.assign(new VoiceProfile(166,.08f,.043f,VoiceProfile.Band.NEUTRAL));
        assertTrue(m.speaker>=1);
        assertTrue(m.confidence>=0.0&&m.confidence<=1.0);
    }
}
