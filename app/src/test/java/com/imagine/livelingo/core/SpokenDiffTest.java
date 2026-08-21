package com.imagine.livelingo.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpokenDiffTest {
    @Test public void speaksOnlyStableSuffix() { SpokenDiff d = new SpokenDiff(1); assertEquals("Доброе утро", d.acceptPartial("Доброе утро я", false)); assertEquals("я хотел", d.acceptPartial("Доброе утро я хотел спросить", false)); assertEquals("спросить", d.acceptPartial("Доброе утро я хотел спросить", true)); }
    @Test public void doesNotRepeatOnSamePartial() { SpokenDiff d = new SpokenDiff(1); assertEquals("Hello", d.acceptPartial("Hello world", false)); assertEquals("", d.acceptPartial("Hello world", false)); }
    @Test public void resetStartsNewUtterance() { SpokenDiff d = new SpokenDiff(0); assertEquals("Привет мир", d.acceptPartial("Привет мир", true)); d.reset(); assertEquals("Привет мир", d.acceptPartial("Привет мир", true)); }
    @Test public void revisionDoesNotReplayOldWords() { SpokenDiff d = new SpokenDiff(0); assertEquals("I want coffee", d.acceptPartial("I want coffee", true)); assertEquals("", d.acceptPartial("I need coffee now", true)); }
}
