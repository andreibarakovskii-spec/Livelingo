package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MeetingInsightEngine {
    public static final class Insight {
        public final String type;
        public final String text;
        public Insight(String type, String text) { this.type = type; this.text = text; }
    }

    private final Set<String> seen = new LinkedHashSet<>();

    public List<Insight> extract(String utterance) {
        List<Insight> out = new ArrayList<>();
        if (utterance == null) return out;
        String text = utterance.trim();
        if (text.length() < 8) return out;
        String l = text.toLowerCase(Locale.ROOT);

        add(out, l, text, "decision", "решили", "решаем", "договорились", "утвердили", "we decided", "agreed", "approved");
        add(out, l, text, "action", "нужно", "надо", "сделать", "подготовить", "отправить", "проверить", "action item", "need to", "have to", "should", "send", "prepare");
        add(out, l, text, "risk", "риск", "проблем", "блокер", "задерж", "опас", "risk", "blocker", "issue", "delay");
        add(out, l, text, "question", "?", "вопрос", "уточнить", "непонятно", "question", "clarify");
        add(out, l, text, "followup", "следующ", "вернемся", "обсудим позже", "follow up", "next time", "revisit");
        return out;
    }

    private void add(List<Insight> out, String lower, String original, String type, String... keys) {
        for (String k : keys) {
            if (lower.contains(k.toLowerCase(Locale.ROOT))) {
                String id = type + "|" + original;
                if (seen.add(id)) out.add(new Insight(type, original));
                return;
            }
        }
    }

    public void reset() { seen.clear(); }
}
