package com.imagine.livelingo.business;

import java.util.List;

public final class MeetingReportBuilder {
    private MeetingReportBuilder() {}

    public static String build(MeetingSessionStore store, List<Insight> insights) {
        StringBuilder sb = new StringBuilder();
        long sec = store.durationMs() / 1000L;
        sb.append("Итоги встречи\n");
        sb.append(String.format("%02d:%02d", sec / 60, sec % 60)).append(" · ").append(store.entries().size()).append(" реплик\n\n");
        appendSection(sb, "Решения", insights, Insight.Type.DECISION, "✓ ");
        appendSection(sb, "Задачи", insights, Insight.Type.ACTION, "☐ ");
        appendSection(sb, "Риски", insights, Insight.Type.RISK, "⚠ ");
        appendSection(sb, "Вопросы", insights, Insight.Type.QUESTION, "? ");
        appendSection(sb, "Следующие шаги", insights, Insight.Type.FOLLOW_UP, "→ ");
        sb.append("Стенограмма\n");
        for (MeetingSessionStore.Entry e : store.entries()) {
            long s = e.elapsedMs / 1000L;
            sb.append(String.format("[%02d:%02d] ", s / 60, s % 60));
            if (e.speaker != null && !e.speaker.isBlank()) sb.append(e.speaker).append(": ");
            sb.append(e.original == null ? "" : e.original);
            if (e.translated != null && !e.translated.isBlank() && !e.translated.equals(e.original)) sb.append("\n↳ ").append(e.translated);
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    private static void appendSection(StringBuilder sb, String title, List<Insight> insights, Insight.Type type, String prefix) {
        boolean any = false;
        for (Insight i : insights) {
            if (type == i.type) {
                if (!any) { sb.append(title).append("\n"); any = true; }
                sb.append(prefix).append(i.text);
                if (i.owner != null && !i.owner.isBlank()) sb.append(" · ").append(i.owner);
                if (i.due != null && !i.due.isBlank()) sb.append(" · ").append(i.due);
                sb.append("\n");
            }
        }
        if (any) sb.append("\n");
    }
}
