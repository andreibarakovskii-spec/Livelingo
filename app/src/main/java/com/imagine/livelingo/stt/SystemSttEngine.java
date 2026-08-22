package com.imagine.livelingo.stt;

import android.content.Context;
import com.imagine.livelingo.LiveSpeechRecognizer;

public final class SystemSttEngine implements SttEngine, LiveSpeechRecognizer.Listener {
    private final LiveSpeechRecognizer recognizer;
    private final Listener listener;

    public SystemSttEngine(Context context, Listener listener) {
        this.listener = listener;
        this.recognizer = new LiveSpeechRecognizer(context, this);
    }

    @Override public boolean isAvailable() { return recognizer.isOnDeviceAvailable(); }
    @Override public void setInputLanguage(String code) { recognizer.setInputLanguage(code); }
    @Override public void start() { recognizer.start(); }
    @Override public void stop() { recognizer.stop(); }
    @Override public void close() { recognizer.stop(); }

    @Override public void onText(String text, boolean isFinal, String detectedLanguage) {
        if (isFinal) listener.onFinal(text, detectedLanguage);
        else listener.onPartial(text, detectedLanguage);
    }
    @Override public void onStatus(String status) {
        if ("Говорите".equals(status)) listener.onReady();
        listener.onStatus(status);
    }
    @Override public void onSpeechStart() { listener.onSpeechStart(); }
    @Override public void onError(String error) { listener.onError(error); }
}
