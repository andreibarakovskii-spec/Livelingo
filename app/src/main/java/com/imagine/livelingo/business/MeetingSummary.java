package com.imagine.livelingo.business;

import java.util.ArrayList;
import java.util.List;

public final class MeetingSummary {
    public String title = "Meeting summary";
    public final List<String> highlights = new ArrayList<>();
    public final List<Insight> decisions = new ArrayList<>();
    public final List<Insight> actions = new ArrayList<>();
    public final List<Insight> risks = new ArrayList<>();
    public final List<Insight> questions = new ArrayList<>();
    public final List<Insight> followUps = new ArrayList<>();
}
