package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MeetingRecord {
    public static final class Line {
        public final long timestampMs;
        public final String speaker;
        public final String original;
        public final String translated;
        public Line(long timestampMs, String speaker, String original, String translated) {
            this.timestampMs = timestampMs;
            this.speaker = speaker;
            this.original = original;
            this.translated = translated;
        }
    }

    private final long startedAtMs = System.currentTimeMillis();
    private final List<Line> lines = new ArrayList<>();

    public void add(String speaker, String original, String translated) {
        lines.add(new Line(System.currentTimeMillis() - startedAtMs, speaker, original, translated));
    }

    public List<Line> lines() { return Collections.unmodifiableList(lines); }
    public long durationMs() { return Math.max(0, System.currentTimeMillis() - startedAtMs); }
    public int utteranceCount() { return lines.size(); }
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (sb.length() > 0) sb.append('\n');
            if (l.speaker != null && !l.speaker.isBlank()) sb.append(l.speaker).append(": ");
            sb.append(l.original == null ? "" : l.original);
            if (l.translated != null && !l.translated.isBlank() && !l.translated.equals(l.original)) sb.append("\n→ ").append(l.translated);
        }
        return sb.toString().trim();
    }
}
