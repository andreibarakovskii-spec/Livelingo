package com.imagine.livelingo.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SpokenDiff {
    private final int safetyTailWords; private final List<String> spoken = new ArrayList<>();
    public SpokenDiff(int safetyTailWords) { this.safetyTailWords = Math.max(0, safetyTailWords); }
    public synchronized String acceptPartial(String translation, boolean isFinal) {
        List<String> words = tokenize(translation); int stableCount = isFinal ? words.size() : Math.max(0, words.size() - safetyTailWords);
        List<String> stable = words.subList(0, stableCount); int common = 0;
        while (common < spoken.size() && common < stable.size() && spoken.get(common).equalsIgnoreCase(stable.get(common))) common++;
        if (common < spoken.size() || stable.size() <= spoken.size()) return "";
        List<String> delta = stable.subList(spoken.size(), stable.size()); spoken.addAll(delta); return String.join(" ", delta).trim();
    }
    public synchronized String flushFinal(String translation) {
        List<String> words = tokenize(translation); int common = 0;
        while (common < spoken.size() && common < words.size() && spoken.get(common).equalsIgnoreCase(words.get(common))) common++;
        if (common < spoken.size() || words.size() <= spoken.size()) return "";
        List<String> delta = words.subList(spoken.size(), words.size()); spoken.addAll(delta); return String.join(" ", delta).trim();
    }
    public synchronized void reset() { spoken.clear(); }
    public synchronized int spokenWordCount() { return spoken.size(); }
    private static List<String> tokenize(String text) {
        String clean = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty()) return new ArrayList<>(); return new ArrayList<>(Arrays.asList(clean.split(" ")));
    }
}
