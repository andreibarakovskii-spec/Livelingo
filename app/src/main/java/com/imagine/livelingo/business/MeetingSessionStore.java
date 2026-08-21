package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MeetingSessionStore {
    public static final class Entry {
        public final long elapsedMs;
        public final String speaker;
        public final String sourceLanguage;
        public final String original;
        public final String translated;
        public Entry(long elapsedMs, String speaker, String sourceLanguage, String original, String translated) {
            this.elapsedMs = elapsedMs; this.speaker = speaker; this.sourceLanguage = sourceLanguage;
            this.original = original; this.translated = translated;
        }
    }

    private long startedAt;
    private final List<Entry> entries = new ArrayList<>();

    public void start() { startedAt = System.currentTimeMillis(); entries.clear(); }
    public void add(String speaker, String sourceLanguage, String original, String translated) {
        if (startedAt == 0) start();
        entries.add(new Entry(System.currentTimeMillis() - startedAt, speaker, sourceLanguage, original, translated));
    }
    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public long durationMs() { return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt; }
    public void reset() { startedAt = 0; entries.clear(); }
}
