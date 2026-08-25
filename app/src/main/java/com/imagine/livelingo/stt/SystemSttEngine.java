package com.imagine.livelingo.stt;

import android.content.Context;
import com.imagine.livelingo.DebugTrace;
import com.imagine.livelingo.LiveSpeechRecognizer;
import com.imagine.livelingo.ai.SecureApiKeyStore;

/**
 * Compatibility STT wrapper. When a Groq key is present we prefer continuous
 * AudioRecord + Whisper even in Translate/Conversation, avoiding Android recognizer gaps.
 */
public final class SystemSttEngine implements SttEngine, LiveSpeechRecognizer.Listener {
    private final LiveSpeechRecognizer recognizer;
    private final Listener listener;
    private final GroqStreamingSttEngine continuous;
    private final SecureApiKeyStore keys;
    private String inputLanguage="auto";
    private boolean usingContinuous;

    public SystemSttEngine(Context context, Listener listener) {
        this.listener = listener;
        this.recognizer = new LiveSpeechRecognizer(context, this);
        this.continuous = new GroqStreamingSttEngine(context, listener);
        this.keys = new SecureApiKeyStore(context.getApplicationContext());
    }

    @Override public boolean isAvailable() { return continuous.isAvailable() || recognizer.isOnDeviceAvailable(); }
    @Override public void setInputLanguage(String code) {
        inputLanguage=code==null?"auto":code;
        recognizer.setInputLanguage(inputLanguage);
        continuous.setInputLanguage(inputLanguage);
    }
    @Override public void start() {
        String key=keys.load();
        usingContinuous=key!=null&&key.startsWith("gsk_")&&continuous.isAvailable();
        DebugTrace.logGlobal("STT_ROUTE","wrapper=system selected="+(usingContinuous?"groq-whisper-continuous":"android-speech")+" forced="+inputLanguage);
        if(usingContinuous)continuous.start();else recognizer.start();
    }
    @Override public void stop() { if(usingContinuous)continuous.stop();else recognizer.stop();usingContinuous=false; }
    @Override public void close() { try{recognizer.stop();}catch(Exception ignored){}continuous.close(); }

    @Override public void onText(String text, boolean isFinal, String detectedLanguage) {
        DebugTrace.logGlobal(isFinal?"ANDROID_FINAL":"ANDROID_PARTIAL","lang="+detectedLanguage+" text="+preview(text));
        if (isFinal) listener.onFinal(text, detectedLanguage);
        else listener.onPartial(text, detectedLanguage);
    }
    @Override public void onStatus(String status) {
        if ("Говорите".equals(status)) listener.onReady();
        listener.onStatus(status);
    }
    @Override public void onSpeechStart() { listener.onSpeechStart(); }
    @Override public void onError(String error) { DebugTrace.logGlobal("ANDROID_STT_ERROR",error);listener.onError(error); }
    private static String preview(String s){if(s==null)return "";String x=s.replace('\n',' ').trim();return x.length()>120?x.substring(0,120)+"…":x;}
}
