package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local, privacy-preserving post-processing for encrypted meeting payloads. */
public final class MeetingContentTools {
    private MeetingContentTools() {}

    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}])[-+]?\\d+(?:[.,]\\d+)?\\s*(?:%|₽|руб|р|k|тыс|млн|шт|дн|день|дня|нед|час|мин)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final class Entry {
        final long ms; final String speaker, lang, original, translated;
        Entry(long ms, String speaker, String lang, String original, String translated) {
            this.ms = ms; this.speaker = speaker; this.lang = lang; this.original = original; this.translated = translated;
        }
    }

    public static String searchImportant(String payload, String query) {
        if (payload == null || payload.isBlank()) return "Нет данных встречи";
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        List<Entry> entries = parseEntries(payload);

        if (!entries.isEmpty()) {
            for (Entry e : entries) {
                String text = clean(e.original + (e.translated.isBlank() ? "" : " " + e.translated));
                String lower = text.toLowerCase(Locale.ROOT);
                boolean important = isImportantText(text) || NUMBER.matcher(text).find();
                if (q.isEmpty()) {
                    if (important) hits.add(time(e.ms) + " · " + safeSpeaker(e.speaker) + " · " + text);
                } else if (lower.contains(q)) {
                    hits.add(time(e.ms) + " · " + safeSpeaker(e.speaker) + " · " + text);
                }
            }
        } else {
            boolean inTranscript = false;
            for (String raw : payload.split("\\n")) {
                String line = raw.trim();
                if (line.equalsIgnoreCase("transcript")) { inTranscript = true; continue; }
                if (line.isBlank() || line.startsWith("durationMs=")) continue;
                String lower = line.toLowerCase(Locale.ROOT);
                boolean important = isImportantLine(line);
                if (q.isEmpty()) { if (important) hits.add(clean(line)); }
                else if ((important || inTranscript) && lower.contains(q)) hits.add(clean(line));
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
        List<Entry> entries = parseEntries(payload);
        List<String> reportLines = parseReportLines(payload);
        if (entries.isEmpty() && reportLines.isEmpty()) return "Недостаточно данных для презентации";

        Map<String, Integer> speakerTurns = speakerTurns(entries);
        List<String> decisions = collectByKeywords(entries, reportLines, "решили", "договорились", "утвердили", "approved", "agreed", "decided");
        List<String> actions = collectByKeywords(entries, reportLines, "нужно", "надо", "сделать", "подготовить", "отправить", "проверить", "need to", "should", "action item");
        List<String> risks = collectByKeywords(entries, reportLines, "риск", "проблем", "блокер", "задерж", "ошиб", "risk", "issue", "blocker", "delay");
        List<String> questions = collectByKeywords(entries, reportLines, "?", "вопрос", "уточнить", "непонятно", "question", "clarify");
        List<String> metrics = collectMetrics(entries, reportLines);
        List<String> topics = topTopics(entries, reportLines);
        List<String> quotes = strongQuotes(entries);

        String story = chooseStory(decisions, actions, risks, questions, metrics, topics, entries.size());
        StringBuilder p = new StringBuilder();
        int n = 1;
        slide(p, n++, "hero", headline(story, topics), body("Тип истории: " + storyLabel(story), firstNonEmpty(topicLine(topics), first(reportLines, "Встреча без явной темы"))));

        if (!metrics.isEmpty()) {
            slide(p, n++, "hero-number", "Главные цифры разговора", bullets(metrics, 5));
        }
        if (!topics.isEmpty()) {
            slide(p, n++, "topic-map", "Карта разговора", topicMap(topics, speakerTurns));
        }
        if (!decisions.isEmpty()) {
            slide(p, n++, "decision", "Что решили", bullets(decisions, 5));
        }
        if (!actions.isEmpty()) {
            slide(p, n++, "action-board", "План действий", bullets(actions, 6));
        }
        if (!risks.isEmpty() || !questions.isEmpty()) {
            List<String> merged = new ArrayList<>(); merged.addAll(risks); merged.addAll(questions);
            slide(p, n++, "risk-matrix", "Риски и открытые вопросы", bullets(merged, 6));
        }
        if (!quotes.isEmpty()) {
            slide(p, n++, "quote", "Ключевые реплики", bullets(quotes, 3));
        }
        if (n <= 4 && !reportLines.isEmpty()) {
            slide(p, n++, "summary", "Смысл встречи", bullets(reportLines, 5));
        }
        slide(p, n, "timeline", "Следующий шаг", nextStep(actions, decisions, risks));
        return p.toString().trim();
    }

    public static String buildConversationMap(String payload) {
        List<Entry> entries = parseEntries(payload);
        List<String> topics = topTopics(entries, parseReportLines(payload));
        if (entries.isEmpty() && topics.isEmpty()) return "Карта разговора появится после стенограммы";
        StringBuilder sb = new StringBuilder("КАРТА РАЗГОВОРА\n");
        int i = 1;
        for (String t : topics) sb.append(i++).append(". ").append(t).append('\n');
        Map<String, Integer> speakers = speakerTurns(entries);
        if (!speakers.isEmpty()) {
            sb.append("\nУчастники\n");
            for (Map.Entry<String, Integer> e : speakers.entrySet()) sb.append("• ").append(e.getKey()).append(" · ").append(e.getValue()).append(" реплик\n");
        }
        return sb.toString().trim();
    }

    private static List<Entry> parseEntries(String payload) {
        List<Entry> out = new ArrayList<>();
        boolean inTranscript = false;
        for (String raw : payload.split("\\n")) {
            String line = raw.trim();
            if (line.equalsIgnoreCase("transcript")) { inTranscript = true; continue; }
            if (!inTranscript || line.isBlank()) continue;
            String[] p = line.split("\\t", -1);
            if (p.length < 4) continue;
            long ms = parseLong(p[0]);
            String speaker = unescape(p.length > 1 ? p[1] : "");
            String lang = unescape(p.length > 2 ? p[2] : "");
            String original = unescape(p.length > 3 ? p[3] : "");
            String translated = unescape(p.length > 4 ? p[4] : "");
            if (!original.isBlank() || !translated.isBlank()) out.add(new Entry(ms, speaker, lang, original, translated));
        }
        return out;
    }

    private static List<String> parseReportLines(String payload) {
        List<String> out = new ArrayList<>();
        boolean inReport = false;
        for (String raw : payload.split("\\n")) {
            String line = raw.trim();
            if (line.equalsIgnoreCase("report")) { inReport = true; continue; }
            if (line.equalsIgnoreCase("transcript")) break;
            if (!inReport || line.isBlank() || line.startsWith("durationMs=")) continue;
            out.add(clean(line));
        }
        return out;
    }

    private static Map<String, Integer> speakerTurns(List<Entry> entries) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Entry e : entries) m.put(safeSpeaker(e.speaker), m.getOrDefault(safeSpeaker(e.speaker), 0) + 1);
        return m;
    }

    private static List<String> collectByKeywords(List<Entry> entries, List<String> reportLines, String... keys) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Entry e : entries) addIfKeyword(out, clean(e.original), keys);
        for (String r : reportLines) addIfKeyword(out, clean(r), keys);
        return limit(out, 8);
    }

    private static void addIfKeyword(Set<String> out, String text, String... keys) {
        String l = text.toLowerCase(Locale.ROOT);
        for (String k : keys) if (l.contains(k.toLowerCase(Locale.ROOT))) { out.add(shorten(text, 190)); return; }
    }

    private static List<String> collectMetrics(List<Entry> entries, List<String> reportLines) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Entry e : entries) addNumbers(out, e.original, e.ms, e.speaker);
        for (String r : reportLines) addNumbers(out, r, -1, "");
        return limit(out, 7);
    }

    private static void addNumbers(Set<String> out, String text, long ms, String speaker) {
        if (text == null) return;
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return;
        String prefix = ms >= 0 ? time(ms) + " · " + safeSpeaker(speaker) + " · " : "";
        out.add(prefix + shorten(clean(text), 170));
    }

    private static List<String> topTopics(List<Entry> entries, List<String> reportLines) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (Entry e : entries) countWords(freq, e.original);
        for (String r : reportLines) countWords(freq, r);
        List<Map.Entry<String, Integer>> all = new ArrayList<>(freq.entrySet());
        all.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : all) {
            if (out.size() >= 7) break;
            out.add(e.getKey());
        }
        return out;
    }

    private static void countWords(Map<String, Integer> freq, String text) {
        if (text == null) return;
        for (String raw : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").split("\\s+")) {
            String w = raw.trim();
            if (w.length() < 5 || stop(w)) continue;
            freq.put(w, freq.getOrDefault(w, 0) + 1);
        }
    }

    private static List<String> strongQuotes(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            String t = clean(e.original);
            if (t.length() < 25) continue;
            if (isImportantText(t) || NUMBER.matcher(t).find()) out.add("“" + shorten(t, 150) + "” — " + safeSpeaker(e.speaker) + ", " + time(e.ms));
            if (out.size() >= 4) break;
        }
        return out;
    }

    private static String chooseStory(List<String> decisions, List<String> actions, List<String> risks, List<String> questions, List<String> metrics, List<String> topics, int turns) {
        if (!metrics.isEmpty() && (!decisions.isEmpty() || !actions.isEmpty())) return "business_case";
        if (!risks.isEmpty() && !actions.isEmpty()) return "risk_to_plan";
        if (topics.size() >= 5 && decisions.isEmpty()) return "brainstorm";
        if (!questions.isEmpty() && decisions.isEmpty()) return "discovery";
        if (turns > 18) return "meeting_summary";
        return "short_brief";
    }

    private static String storyLabel(String s) {
        if ("business_case".equals(s)) return "цифры → варианты → решение";
        if ("risk_to_plan".equals(s)) return "риск → действие";
        if ("brainstorm".equals(s)) return "темы → идеи → выбор";
        if ("discovery".equals(s)) return "вопросы → уточнения";
        if ("meeting_summary".equals(s)) return "обсуждение → итоги";
        return "короткий бриф";
    }

    private static String headline(String story, List<String> topics) {
        String topic = topics.isEmpty() ? "итоги разговора" : topics.get(0);
        if ("business_case".equals(story)) return "Что решили по теме: " + topic;
        if ("risk_to_plan".equals(story)) return "Как закрываем риск: " + topic;
        if ("brainstorm".equals(story)) return "Карта идей: " + topic;
        if ("discovery".equals(story)) return "Что нужно уточнить: " + topic;
        return "Итоги встречи: " + topic;
    }

    private static String topicLine(List<String> topics) { return topics.isEmpty() ? "" : "Фокус: " + String.join(" · ", topics.subList(0, Math.min(5, topics.size()))); }
    private static String topicMap(List<String> topics, Map<String, Integer> speakers) {
        StringBuilder sb = new StringBuilder();
        for (String t : topics) sb.append("◦ ").append(t).append('\n');
        if (!speakers.isEmpty()) {
            sb.append("\nГолоса в обсуждении:\n");
            for (Map.Entry<String, Integer> e : speakers.entrySet()) sb.append("• ").append(e.getKey()).append(" · ").append(e.getValue()).append(" реплик\n");
        }
        return sb.toString().trim();
    }

    private static String nextStep(List<String> actions, List<String> decisions, List<String> risks) {
        if (!actions.isEmpty()) return bullets(actions, Math.min(4, actions.size()));
        if (!decisions.isEmpty()) return "• Проверить выполнение решения\n" + bullets(decisions, Math.min(3, decisions.size()));
        if (!risks.isEmpty()) return "• Закрыть главный риск\n" + bullets(risks, Math.min(3, risks.size()));
        return "• Зафиксировать итог встречи\n• Назначить ответственного\n• Вернуться к открытым вопросам";
    }

    private static boolean isImportantLine(String line) { return isImportantText(line); }
    private static boolean isImportantText(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return line.startsWith("✓") || line.startsWith("☐") || line.startsWith("⚠") || line.startsWith("?") || line.startsWith("→")
                || l.contains("решение") || l.contains("задача") || l.contains("риск") || l.contains("вопрос") || l.contains("следующий шаг")
                || l.contains("нужно") || l.contains("надо") || l.contains("договорились") || l.contains("решили") || l.contains("проблем");
    }

    private static String clean(String line) {
        String s = line == null ? "" : line.replace("\\t", " ").replace('\t', ' ').trim();
        return s.replaceAll("\\s+", " ");
    }

    private static String bullets(List<String> items, int max) {
        if (items.isEmpty()) return "• не указано";
        StringBuilder b = new StringBuilder();
        int n = Math.min(max, items.size());
        for (int i = 0; i < n; i++) { if (b.length() > 0) b.append('\n'); b.append("• ").append(items.get(i)); }
        return b.toString();
    }

    private static String body(String a, String b) { return "• " + a + (b == null || b.isBlank() ? "" : "\n• " + b); }
    private static String first(List<String> items, String fallback) { return items.isEmpty() ? fallback : shorten(items.get(0), 220); }
    private static String firstNonEmpty(String a, String b) { return a != null && !a.isBlank() ? a : b; }
    private static void slide(StringBuilder out, int n, String visual, String title, String body) {
        if (out.length() > 0) out.append("\n\n");
        out.append("СЛАЙД ").append(n).append(" · ").append(title).append('\n');
        out.append("Визуал: ").append(visual).append('\n').append(body == null ? "" : body.trim());
    }

    private static List<String> limit(Set<String> set, int max) { List<String> out = new ArrayList<>(); for (String s : set) { if (!s.isBlank()) out.add(s); if (out.size() >= max) break; } return out; }
    private static String safeSpeaker(String s) { return s == null || s.isBlank() ? "Спикер" : s; }
    private static long parseLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; } }
    private static String time(long ms) { long total = Math.max(0, ms) / 1000; return String.format(Locale.US, "%02d:%02d", total / 60, total % 60); }
    private static String shorten(String s, int max) { String c = clean(s); return c.length() <= max ? c : c.substring(0, Math.max(0, max - 1)) + "…"; }
    private static String unescape(String s) { return s == null ? "" : s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\"); }
    private static boolean stop(String w) {
        return w.equals("котор") || w.equals("чтобы") || w.equals("можно") || w.equals("нужно") || w.equals("будет") || w.equals("сегодня") || w.equals("просто") || w.equals("очень") || w.equals("здесь") || w.equals("there") || w.equals("about") || w.equals("should") || w.equals("would") || w.equals("meeting");
    }
}
