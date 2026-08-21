package com.imagine.livelingo.business;

import java.util.List;

public final class MeetingReportBuilder {
    private MeetingReportBuilder() {}

    public static String build(MeetingSessionStore store, List<MeetingInsightEngine.Insight> insights) {
        StringBuilder sb = new StringBuilder();
        long sec = store.durationMs() / 1000L;
        sb.append("Итоги встречи\n");
        sb.append(String.format("%02d:%02d", sec / 60, sec % 60)).append(" · ").append(store.entries().size()).append(" реплик\n\n");
        appendSection(sb, "Решения", insights, "decision", "✓ ");
        appendSection(sb, "Задачи", insights, "action", "☐ ");
        appendSection(sb, "Риски", insights, "risk", "⚠ ");
        appendSection(sb, "Вопросы", insights, "question", "? ");
        appendSection(sb, "Следующие шаги", insights, "followup", "→ ");
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

    private static void appendSection(StringBuilder sb, String title, List<MeetingInsightEngine.Insight> insights, String type, String prefix) {
        boolean any = false;
        for (MeetingInsightEngine.Insight i : insights) {
            if (type.equals(i.type)) {
                if (!any) { sb.append(title).append("\n"); any = true; }
                sb.append(prefix).append(i.text).append("\n");
            }
        }
        if (any) sb.append("\n");
    }
}
