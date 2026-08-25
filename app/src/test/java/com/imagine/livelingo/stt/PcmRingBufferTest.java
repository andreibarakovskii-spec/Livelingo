package com.imagine.livelingo.stt;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmRingBufferTest {
    @Test public void keepsLatestSamplesInOrder(){
        PcmRingBuffer b=new PcmRingBuffer(5);
        b.append(new float[]{1,2,3});
        assertArrayEquals(new float[]{1,2,3},b.snapshotLast(5),0f);
        b.append(new float[]{4,5,6,7});
        assertArrayEquals(new float[]{3,4,5,6,7},b.snapshotLast(5),0f);
        assertArrayEquals(new float[]{5,6,7},b.snapshotLast(3),0f);
        assertEquals(7,b.totalWritten());
    }

    @Test public void clearDropsHistory(){
        PcmRingBuffer b=new PcmRingBuffer(4);
        b.append(new float[]{1,2,3,4});
        b.clear();
        assertEquals(0,b.size());
        assertEquals(0,b.totalWritten());
        assertEquals(0,b.snapshotLast(4).length);
    }
}
