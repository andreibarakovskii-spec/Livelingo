package com.imagine.livelingo.business;

import android.content.Context;
import com.imagine.livelingo.security.EncryptedMeetingVault;
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
    private EncryptedMeetingVault vault;

    public void attachVault(Context context) { vault = new EncryptedMeetingVault(context); }
    public void start() { startedAt = System.currentTimeMillis(); entries.clear(); }
    public void add(String speaker, String sourceLanguage, String original, String translated) {
        if (startedAt == 0) start();
        entries.add(new Entry(System.currentTimeMillis() - startedAt, speaker, sourceLanguage, original, translated));
    }
    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public long durationMs() { return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt; }

    public String saveEncrypted(String title, String report) throws Exception {
        if (vault == null) throw new IllegalStateException("Encrypted vault is not attached");
        StringBuilder payload = new StringBuilder();
        payload.append("durationMs=").append(durationMs()).append('\n');
        payload.append("report\n").append(report == null ? "" : report).append("\n\ntranscript\n");
        for (Entry e : entries) {
            payload.append(e.elapsedMs).append('\t')
                    .append(escape(e.speaker)).append('\t')
                    .append(escape(e.sourceLanguage)).append('\t')
                    .append(escape(e.original)).append('\t')
                    .append(escape(e.translated)).append('\n');
        }
        return vault.save(title == null ? "Встреча" : title, payload.toString());
    }

    public EncryptedMeetingVault.StoredMeeting loadEncrypted(String id) throws Exception {
        if (vault == null) throw new IllegalStateException("Encrypted vault is not attached");
        return vault.load(id);
    }

    public List<String> encryptedMeetingIds() {
        return vault == null ? Collections.emptyList() : vault.ids();
    }

    public void deleteEncrypted(String id) { if (vault != null) vault.delete(id); }
    public void wipeEncrypted() { if (vault != null) vault.wipeAll(); }
    public void reset() { startedAt = 0; entries.clear(); }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
}
