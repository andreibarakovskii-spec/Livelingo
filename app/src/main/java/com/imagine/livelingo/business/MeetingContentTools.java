package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local, privacy-preserving post-processing for encrypted meeting payloads. */
public final class MeetingContentTools {
    private MeetingContentTools() {}

    public static String searchImportant(String payload, String query) {
        if (payload == null || payload.isBlank()) return "Нет данных встречи";
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        boolean inTranscript = false;
        for (String raw : payload.split("\\n")) {
            String line = raw.trim();
            if (line.equalsIgnoreCase("transcript")) { inTranscript = true; continue; }
            if (line.isBlank() || line.startsWith("durationMs=")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            boolean important = isImportantLine(line);
            if (q.isEmpty()) {
                if (important) hits.add(clean(line));
            } else if ((important || inTranscript) && lower.contains(q)) {
                hits.add(clean(line));
            }
        }
        if (hits.isEmpty()) return q.isEmpty() ? "Важные моменты пока не найдены" : "По запросу ничего не найдено";
        StringBuilder out = new StringBuilder();
        int max = Math.min(40, hits.size());
        for (int i = 0; i < max; i++) {
            if (out.length() > 0) out.append('\n');
            out.append("• ").append(hits.get(i));
        }
        if (hits.size() > max) out.append("\n… ещё ").append(hits.size() - max);
        return out.toString();
    }

    public static String buildPresentationDraft(String payload) {
        if (payload == null || payload.isBlank()) return "Сначала откройте встречу";
        List<String> decisions = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        List<String> followUps = new ArrayList<>();
        List<String> summary = new ArrayList<>();

        boolean inReport = false;
        for (String raw : payload.split("\\n")) {
            String line = raw.trim();
            if (line.equalsIgnoreCase("report")) { inReport = true; continue; }
            if (line.equalsIgnoreCase("transcript")) break;
            if (!inReport || line.isBlank()) continue;
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("✓") || l.contains("решение:")) decisions.add(clean(line));
            else if (l.startsWith("☐") || l.contains("задача:")) actions.add(clean(line));
            else if (l.startsWith("⚠") || l.contains("риск:")) risks.add(clean(line));
            else if (l.startsWith("?") || l.contains("вопрос:")) questions.add(clean(line));
            else if (l.startsWith("→") || l.contains("следующий шаг:")) followUps.add(clean(line));
            else if (!line.startsWith("durationMs=")) summary.add(clean(line));
        }

        StringBuilder p = new StringBuilder();
        slide(p, 1, "Итоги встречи", first(summary, "Краткое резюме и главные договорённости"));
        slide(p, 2, "Ключевые решения", bullets(decisions, "Решения не отмечены"));
        slide(p, 3, "Задачи и ответственность", bullets(actions, "Задачи не отмечены"));
        slide(p, 4, "Риски и открытые вопросы", mergeBullets(risks, questions, "Риски и вопросы не отмечены"));
        slide(p, 5, "Следующие шаги", bullets(followUps, "Следующие шаги не отмечены"));
        return p.toString().trim();
    }

    private static boolean isImportantLine(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return line.startsWith("✓") || line.startsWith("☐") || line.startsWith("⚠") || line.startsWith("?") || line.startsWith("→")
                || l.contains("решение:") || l.contains("задача:") || l.contains("риск:") || l.contains("вопрос:") || l.contains("следующий шаг:");
    }

    private static String clean(String line) {
        String s = line.replace("\\t", " ").replace('\t', ' ').trim();
        return s.replaceAll("\\s+", " ");
    }

    private static String first(List<String> items, String fallback) {
        if (items.isEmpty()) return fallback;
        String s = items.get(0);
        return s.length() > 280 ? s.substring(0, 277) + "…" : s;
    }

    private static String bullets(List<String> items, String fallback) {
        if (items.isEmpty()) return "• " + fallback;
        StringBuilder b = new StringBuilder();
        int max = Math.min(6, items.size());
        for (int i = 0; i < max; i++) {
            if (b.length() > 0) b.append('\n');
            b.append("• ").append(items.get(i));
        }
        return b.toString();
    }

    private static String mergeBullets(List<String> a, List<String> b, String fallback) {
        List<String> all = new ArrayList<>(a); all.addAll(b);
        return bullets(all, fallback);
    }

    private static void slide(StringBuilder out, int n, String title, String body) {
        if (out.length() > 0) out.append("\n\n");
        out.append("СЛАЙД ").append(n).append(" · ").append(title).append('\n').append(body);
    }
}
