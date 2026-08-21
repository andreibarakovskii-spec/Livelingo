package com.imagine.livelingo.business;

public final class MeetingSummaryEngine {
    public MeetingSummary build(MeetingSession session) {
        MeetingSummary summary = new MeetingSummary();
        if (session == null) return summary;
        int highlightBudget = 5;
        for (MeetingSession.Utterance u : session.utterances()) {
            if (highlightBudget > 0 && u.original.length() >= 24) {
                summary.highlights.add(u.translated.isBlank() ? u.original : u.translated);
                highlightBudget--;
            }
        }
        for (Insight i : session.insights()) {
            switch (i.type) {
                case DECISION -> summary.decisions.add(i);
                case ACTION -> summary.actions.add(i);
                case RISK -> summary.risks.add(i);
                case QUESTION -> summary.questions.add(i);
                case FOLLOW_UP -> summary.followUps.add(i);
                default -> { }
            }
        }
        return summary;
    }
}
