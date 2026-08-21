package com.imagine.livelingo.stt;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SpeechChunkerTest {
    private static float[] block(int n,float amplitude){
        float[] x=new float[n];
        for(int i=0;i<n;i++) x[i]=amplitude;
        return x;
    }

    @Test public void emitsPartialDuringLongSpeech(){
        List<boolean[]> events=new ArrayList<>();
        List<Integer> sizes=new ArrayList<>();
        SpeechChunker c=new SpeechChunker((samples,fin)->{events.add(new boolean[]{fin});sizes.add(samples.length);});
        c.accept(block(16000,0.08f));
        c.accept(block(8000,0.08f));
        assertFalse(events.isEmpty());
        assertFalse(events.get(0)[0]);
        assertTrue(sizes.get(0)>=16000);
    }

    @Test public void silenceFinalizesSpeech(){
        List<Boolean> finals=new ArrayList<>();
        SpeechChunker c=new SpeechChunker((samples,fin)->finals.add(fin));
        c.accept(block(16000,0.08f));
        c.accept(block(8000,0f));
        assertTrue(finals.contains(Boolean.TRUE));
    }

    @Test public void shortNoiseDoesNotCreateFinalUtterance(){
        List<Boolean> finals=new ArrayList<>();
        SpeechChunker c=new SpeechChunker((samples,fin)->finals.add(fin));
        c.accept(block(3000,0.08f));
        c.accept(block(8000,0f));
        assertFalse(finals.contains(Boolean.TRUE));
    }

    @Test public void flushFinalizesActiveSpeech(){
        List<Boolean> finals=new ArrayList<>();
        SpeechChunker c=new SpeechChunker((samples,fin)->finals.add(fin));
        c.accept(block(7000,0.08f));
        c.flush();
        assertEquals(1,finals.size());
        assertTrue(finals.get(0));
    }

    @Test public void silenceWithoutSpeechDoesNothing(){
        List<Boolean> finals=new ArrayList<>();
        SpeechChunker c=new SpeechChunker((samples,fin)->finals.add(fin));
        c.accept(block(16000,0f));
        assertTrue(finals.isEmpty());
    }
}
