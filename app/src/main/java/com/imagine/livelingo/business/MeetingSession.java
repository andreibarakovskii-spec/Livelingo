package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MeetingSession {
    public static final class Utterance {
        public final long startedAtMs;
        public final String speaker;
        public final String sourceLanguage;
        public final String original;
        public final String translated;
        public Utterance(long startedAtMs, String speaker, String sourceLanguage, String original, String translated) {
            this.startedAtMs = startedAtMs;
            this.speaker = speaker == null ? "" : speaker;
            this.sourceLanguage = sourceLanguage == null ? "" : sourceLanguage;
            this.original = original == null ? "" : original;
            this.translated = translated == null ? "" : translated;
        }
    }

    private final long createdAtMs = System.currentTimeMillis();
    private final List<Utterance> utterances = new ArrayList<>();
    private final List<Insight> insights = new ArrayList<>();

    public synchronized void addUtterance(Utterance u) { if (u != null && !u.original.isBlank()) utterances.add(u); }
    public synchronized void addInsight(Insight i) { if (i != null) insights.add(i); }
    public synchronized List<Utterance> utterances() { return Collections.unmodifiableList(new ArrayList<>(utterances)); }
    public synchronized List<Insight> insights() { return Collections.unmodifiableList(new ArrayList<>(insights)); }
    public long createdAtMs() { return createdAtMs; }
}
