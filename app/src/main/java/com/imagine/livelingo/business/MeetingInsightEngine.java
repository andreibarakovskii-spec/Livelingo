package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MeetingInsightEngine {
    private final Set<String> seen = new LinkedHashSet<>();

    public List<Insight> analyze(String utterance, long atMs) {
        List<Insight> out = new ArrayList<>();
        if (utterance == null) return out;
        String text = utterance.trim();
        if (text.length() < 6) return out;
        String l = text.toLowerCase(Locale.ROOT);

        add(out, atMs, l, text, Insight.Type.DECISION, "решили", "решаем", "договорились", "утвердили", "we decided", "agreed", "approved");
        add(out, atMs, l, text, Insight.Type.ACTION, "нужно", "надо", "сделать", "подготовить", "отправить", "проверить", "action item", "need to", "have to", "should", "send", "prepare");
        add(out, atMs, l, text, Insight.Type.RISK, "риск", "проблем", "блокер", "задерж", "опас", "risk", "blocker", "issue", "delay");
        add(out, atMs, l, text, Insight.Type.QUESTION, "?", "вопрос", "уточнить", "непонятно", "question", "clarify");
        add(out, atMs, l, text, Insight.Type.FOLLOW_UP, "следующ", "вернемся", "вернёмся", "обсудим позже", "follow up", "next time", "revisit", "circle back");
        return out;
    }

    private void add(List<Insight> out, long atMs, String lower, String original, Insight.Type type, String... keys) {
        for (String k : keys) {
            if (lower.contains(k.toLowerCase(Locale.ROOT))) {
                String id = type.name() + "|" + original;
                if (seen.add(id)) out.add(new Insight(type, original, "", extractDue(lower), atMs));
                return;
            }
        }
    }

    private String extractDue(String t) {
        if (t.contains("сегодня") || t.contains("today")) return "today";
        if (t.contains("завтра") || t.contains("tomorrow")) return "tomorrow";
        if (t.contains("пятниц") || t.contains("friday")) return "Friday";
        if (t.contains("понедельник") || t.contains("monday")) return "Monday";
        return "";
    }

    public void reset() { seen.clear(); }
}
