package com.imagine.livelingo.business;

public final class Insight {
    public enum Type { DECISION, ACTION, RISK, QUESTION, FOLLOW_UP, FACT }
    public final Type type;
    public final String text;
    public final String owner;
    public final String due;
    public final long atMs;

    public Insight(Type type, String text, String owner, String due, long atMs) {
        this.type = type == null ? Type.FACT : type;
        this.text = text == null ? "" : text;
        this.owner = owner == null ? "" : owner;
        this.due = due == null ? "" : due;
        this.atMs = atMs;
    }
}
