package com.imagine.livelingo.stt;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class StreamingHypothesisStabilizerTest {
    @Test public void emitsOnlyStablePrefixAcrossPartials(){
        StreamingHypothesisStabilizer s=new StreamingHypothesisStabilizer();
        assertEquals("",s.accept("I didn't think",false));
        assertEquals("I didn't",s.accept("I didn't think he",false));
        assertEquals("I didn't think he",s.accept("I didn't think he would",false));
    }

    @Test public void ignoresChangedUnstableTail(){
        StreamingHypothesisStabilizer s=new StreamingHypothesisStabilizer();
        assertEquals("",s.accept("we need ship friday",false));
        assertEquals("we need ship",s.accept("we need ship monday",false));
        assertEquals("",s.accept("we need send monday",false));
    }

    @Test public void finalAlwaysWinsAndResetsComparison(){
        StreamingHypothesisStabilizer s=new StreamingHypothesisStabilizer();
        s.accept("hello from",false);
        assertEquals("Hello from Berlin",s.accept("Hello from Berlin",true));
        assertEquals("",s.accept("new phrase starts",false));
    }

    @Test public void resetClearsPriorHypothesis(){
        StreamingHypothesisStabilizer s=new StreamingHypothesisStabilizer();
        s.accept("one two three",false);
        s.reset();
        assertEquals("",s.accept("one two four",false));
    }

    @Test public void whitespaceIsNormalized(){
        StreamingHypothesisStabilizer s=new StreamingHypothesisStabilizer();
        s.accept("good   morning team",false);
        assertEquals("good morning",s.accept("good morning everyone",false));
    }
}
